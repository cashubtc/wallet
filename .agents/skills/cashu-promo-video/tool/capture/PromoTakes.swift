import ObjectiveC
import XCTest

// THROWAWAY — promo film capture takes. capture/worktree.sh installs this
// over CashuWalletUITests/MainTabUITests.swift in a throwaway worktree (that
// file is already in the Xcode project, so nothing needs registering). The
// source of truth is .agents/skills/cashu-promo-video/tool/capture/
// PromoTakes.swift. Never merge it into the app's test target.
//
// Deliberately NOT a UITestBase subclass: that base sets
// UITEST_DISABLE_ANIMATIONS=1 and CI_INTEGRATION_TEST=1, which film a wallet
// with no motion and no payment services. This launches the app the way a
// user does.

/// XCUITest waits for the app to go idle before every query and tap. The
/// onboarding field and the receive sheet never go idle, so each tap would
/// stall for the full timeout and wreck the take's pacing. Takes are paced by
/// explicit sleeps instead, so the idle wait is switched off.
private enum Quiescence {
    static func disable() {
        guard let cls = NSClassFromString("XCUIApplicationProcess") else { return }
        let one: @convention(block) (AnyObject, Bool) -> Void = { _, _ in }
        let two: @convention(block) (AnyObject, Bool, Bool) -> Void = { _, _, _ in }
        let pairs: [(String, Any)] = [
            ("waitForQuiescenceIncludingAnimationsIdle:", one),
            ("waitForQuiescenceIncludingAnimationsIdle:isPreEvent:", two),
        ]
        for (name, block) in pairs {
            if let method = class_getInstanceMethod(cls, NSSelectorFromString(name)) {
                method_setImplementation(method, imp_implementationWithBlock(block))
            }
        }
    }
}

final class PromoTakes: XCTestCase {
    private var app: XCUIApplication!
    private var marks: [[String: Any]] = []
    private var outDir: URL {
        URL(fileURLWithPath: ProcessInfo.processInfo.environment["PROMO_OUT"] ?? NSTemporaryDirectory())
    }

    override class func setUp() {
        super.setUp()
        Quiescence.disable()
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = [
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            // The Receive sheet reads the clipboard on open; with this on, a
            // system "Allow Paste" prompt can land mid-take.
            "-settings.autoPasteEcashReceive", "<false/>",
        ]
    }

    override func tearDownWithError() throws {
        let data = try JSONSerialization.data(withJSONObject: marks, options: [.prettyPrinted])
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let take = name.components(separatedBy: " ").last!.dropLast()
        try data.write(to: outDir.appendingPathComponent("\(take)-marks.json"))
    }

    // MARK: Helpers

    /// Wall-clock marks; the recording's PTS are wall-accurate, so these map
    /// straight onto take frames.
    private func mark(_ label: String) {
        marks.append(["label": label, "time": Date().timeIntervalSince1970])
    }

    private func hold(_ seconds: Double) { Thread.sleep(forTimeInterval: seconds) }

    private func tap(_ element: XCUIElement, _ label: String, timeout: Double = 20) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(label) never appeared")
        mark("tap:\(label)")
        element.tap()
    }

    private func still(_ name: String) throws {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try png.write(to: outDir.appendingPathComponent("\(name).png"))
        mark("still:\(name)")
    }

    private func launch(_ env: [String: String] = [:]) {
        app.launchEnvironment = env
        mark("launch")
        app.launch()
        mark("launched")
    }

    /// Types on the app's keypad at a human pace. Keys are located once, then
    /// tapped by coordinate — resolving an element per tap costs ~1 s, which
    /// would film as one digit per second.
    private func keypad(_ digits: String, pace: Double = 0.32) {
        var centers: [Character: CGVector] = [:]
        for d in Set(digits) {
            let key = app.buttons[String(d)]
            XCTAssertTrue(key.waitForExistence(timeout: 10), "key \(d) never appeared")
            let f = key.frame
            centers[d] = CGVector(dx: f.midX, dy: f.midY)
        }
        let origin = app.coordinate(withNormalizedOffset: .zero)
        for d in digits {
            mark("key-\(d)")
            origin.withOffset(centers[d]!).tap()
            hold(pace)
        }
    }

    private func firstExisting(_ candidates: [XCUIElement], timeout: Double = 10) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let hit = candidates.first(where: { $0.exists }) { return hit }
            hold(0.2)
        }
        return nil
    }

    private func waitEnabled(_ element: XCUIElement, timeout: Double = 30) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists && element.isEnabled && element.isHittable { return }
            hold(0.2)
        }
    }

    /// Hero + payments, one continuous wallet: cold launch → welcome →
    /// Create Wallet → masked seed → local mint → handoff → home → receive
    /// 5,000 → send 1,000 ecash → Cashu Request.
    /// Onboarding onto the local test mint → home. Returns once home shows.
    private func onboardLocal(hold welcomeHold: Double = 3) {
        hold(welcomeHold)
        tap(app.buttons["onboarding-create-wallet"], "create", timeout: 60)
        hold(2.2)
        tap(app.buttons["onboarding-ack-seed"], "ack")
        hold(0.5)
        tap(app.buttons["onboarding-saved-seed"], "saved")
        hold(1.2)
        tap(app.buttons["onboarding-add-custom-mint"], "add-by-url")
        hold(0.6)
        let field = app.textFields["onboarding-custom-mint-field"]
        tap(field, "mint-field")
        hold(0.6)
        field.typeText(ProcessInfo.processInfo.environment["MINT"] ?? "http://127.0.0.1:3340")
        hold(0.6)
        let cont = app.buttons["onboarding-continue"]
        // With a hardware keyboard attached there's no software "Done"; the
        // primary button reads "Add mint" while the field has text.
        if app.keyboards.buttons["Done"].exists && app.keyboards.buttons["Done"].isHittable {
            tap(app.keyboards.buttons["Done"], "done")
        } else {
            tap(cont, "add-mint")
        }
        hold(2.5)
        waitEnabled(cont)
        tap(cont, "continue")
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 90), "never reached the wallet")
        mark("home")
    }

    /// Face ID on the Simulator: the session enrolls a face, and matches it
    /// from the host when this file appears (restore-session.sh).
    private func requestFaceMatch() {
        try? Data().write(to: outDir.appendingPathComponent("faceid-request"))
    }

    private func dryStill(_ name: String) throws {
        if ProcessInfo.processInfo.environment["DRY"] == "1" { try still(name) }
    }

    /// Copies the token bubble phone A posted to Kate Bell's thread, so the
    /// clipboard comes from Messages, as on a real phone. With paste allowed,
    /// iOS names the source in a banner: "Cashu pasted from Messages" (a
    /// `simctl pbcopy` would read "…from CoreSimulatorBridge").
    private func copyTokenFromMessages() throws {
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        mark("to-messages")
        messages.activate()
        hold(2.0)
        let bubbles = messages.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'cashu'"))
        if !bubbles.firstMatch.waitForExistence(timeout: 2) {
            // On the thread list: Kate Bell's is the chat film's thread.
            let thread = messages.cells.containing(NSPredicate(format: "label CONTAINS 'Kate' OR label CONTAINS '564-8583'")).firstMatch
            tap(thread, "thread", timeout: 5)
            hold(1.8)
        }
        XCTAssertTrue(bubbles.firstMatch.waitForExistence(timeout: 5), "no token bubble in the thread")
        try dryStill("bubble")
        mark("press-bubble")
        bubbles.element(boundBy: bubbles.count - 1).press(forDuration: 0.9)
        hold(0.8)
        try dryStill("bubble-menu")
        guard let copy = firstExisting([messages.buttons["Copy"], messages.menuItems["Copy"], messages.staticTexts["Copy"]], timeout: 5) else {
            XCTFail("no Copy in the bubble's menu"); return
        }
        tap(copy, "copy-bubble")
        hold(1.0)
    }

    /// A freshly erased Simulator shows the QuickPath tip over the keyboard
    /// the first time it comes up.
    private func dismissKeyboardTip(_ messages: XCUIApplication) {
        let tip = messages.buttons["Continue"]
        if tip.waitForExistence(timeout: 2) { tip.tap(); hold(0.8) }
    }

    // MARK: Takes (the three films)

    /// Unrecorded setup: a fresh wallet on the local mint holding 5,000 sat.
    func testFund() throws {
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        onboardLocal(hold: 1)
        hold(2)
        let receive = app.buttons["wallet-action-receive"]
        waitEnabled(receive, timeout: 60)
        tap(receive, "receive")
        hold(1.0)
        tap(app.buttons["wallet-flow-receiveLightning"], "bitcoin")
        hold(1.3)
        keypad("5000", pace: 0.2)
        tap(app.buttons["receive-lightning-create-request"], "create-invoice")
        XCTAssertTrue(app.staticTexts["Payment Received!"].waitForExistence(timeout: 60), "invoice never paid")
        hold(1.5)
        tap(app.buttons["Done"], "done")
        hold(2)
    }

    /// The send film: a funded wallet makes 2,500 sat of ecash, copies it,
    /// and posts it in Messages. The Simulator can only send in its two demo
    /// threads (a new or group thread is SMS, which it can't send), so this
    /// is John Appleseed's; the chat film uses Kate Bell's.
    func testSendChat() throws {
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        let send = app.buttons["wallet-action-send"]
        waitEnabled(send, timeout: 60)
        mark("home")
        hold(2.5)
        tap(send, "send")
        hold(1.0)
        tap(app.buttons["Ecash. Create ecash"], "ecash")
        hold(1.3)
        keypad("2500")
        hold(0.8)
        tap(app.buttons["cashu.send.ecash.submit"], "send-submit")
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        mark("token")
        hold(2.5)
        try dryStill("token")
        tap(app.buttons["Copy"].firstMatch, "copy-token")
        hold(1.2)
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        mark("to-messages")
        messages.activate()
        hold(2.0)
        try dryStill("messages")
        let thread = messages.cells.containing(NSPredicate(format: "label CONTAINS '888'")).firstMatch
        if thread.waitForExistence(timeout: 5) && thread.isHittable { mark("tap:thread"); thread.tap(); hold(1.8) }
        try dryStill("thread")
        dismissKeyboardTip(messages)
        let composer = messages.descendants(matching: .any)
            .matching(NSPredicate(format: "placeholderValue == 'Message' OR identifier == 'messageBodyField'")).firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 5), "no composer")
        mark("press-composer")
        composer.press(forDuration: 0.9)
        hold(0.7)
        try dryStill("composer-menu")
        let paste = messages.menuItems["Paste"].exists ? messages.menuItems["Paste"] : messages.buttons["Paste"].firstMatch
        mark("tap:paste")
        paste.tap()
        hold(1.5)
        try dryStill("pasted")
        let sendButton = messages.buttons.matching(NSPredicate(format: "identifier == 'sendButton' OR label == 'Send'")).firstMatch
        mark("tap:send-message")
        sendButton.tap()
        hold(3)
        try dryStill("sent")
    }

    /// The restore film, on testFund's wallet: copy the recovery phrase from
    /// Settings, delete the wallet, restore it from the phrase and the mint,
    /// and the balance comes back.
    func testRestoreFlow() throws {
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        let settings = app.buttons["wallet-settings-button"]
        waitEnabled(settings, timeout: 60)
        mark("home")
        hold(2.5)
        tap(settings, "settings")
        hold(1.2)
        tap(app.buttons["Backup & Restore"].firstMatch, "backup")
        hold(1.2)
        // The row's label is its title and subtitle together.
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Backup seed phrase'")).firstMatch, "backup-seed")
        hold(1.2)
        try dryStill("backup-sheet")
        tap(app.buttons["Reveal Recovery Phrase"], "reveal")
        requestFaceMatch()
        hold(3.0)
        try dryStill("revealed")
        tap(app.buttons["Copy Recovery Phrase"], "copy-seed")
        requestFaceMatch()
        hold(2.5)
        try dryStill("copied")
        // Down with the sheet, back to Settings, down to Delete Wallet.
        mark("dismiss-sheet")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98)))
        hold(1.0)
        tap(app.navigationBars.buttons.element(boundBy: 0), "back")
        hold(1.0)
        app.swipeUp(); app.swipeUp()
        hold(0.8)
        tap(app.buttons["Delete Wallet"].firstMatch, "delete")
        hold(1.2)
        try dryStill("delete-confirm")
        tap(app.buttons["Delete"].firstMatch, "delete-confirm")
        // The welcome again.
        XCTAssertTrue(app.buttons["Restore Wallet"].waitForExistence(timeout: 30), "never reached the welcome")
        mark("welcome")
        hold(2.5)
        tap(app.buttons["Restore Wallet"], "restore")
        hold(1.8)
        try dryStill("restore-method")
        tap(app.buttons["Use Seed Phrase"], "use-seed")
        hold(1.5)
        try dryStill("seed-entry")
        tap(app.buttons["onboarding-seed-paste"], "paste-seed")
        hold(2.0)
        try dryStill("seed-pasted")
        let cont = app.buttons["onboarding-restore-continue"]
        waitEnabled(cont)
        tap(cont, "seed-continue")
        hold(2.0)
        // The app's own way through: its encrypted mint-list backup on the
        // wallet's relays. Typing the URL is the fallback.
        let restore = app.buttons["onboarding-restore-mints"]
        tap(app.buttons["Find my mints"].firstMatch, "find-mints")
        waitEnabled(restore, timeout: 20)
        if !restore.isEnabled {
            let mintField = app.textFields["mint.example.com"]
            tap(mintField, "mint-field")
            hold(0.6)
            mintField.typeText(ProcessInfo.processInfo.environment["MINT"] ?? "http://127.0.0.1:3340")
            hold(0.6)
            tap(app.buttons["Add"].firstMatch, "add-mint")
            waitEnabled(restore)
        }
        mark("mint-staged")
        hold(1.5)
        try dryStill("mint-staged")
        tap(restore, "restore-funds")
        hold(1.0)
        try dryStill("restoring")
        let done = app.buttons["Continue"].firstMatch
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline && !(done.exists && done.isEnabled && done.isHittable) { hold(0.2) }
        mark("restored")
        hold(2.0)
        try dryStill("restored")
        tap(done, "finish")
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 60), "never reached the wallet")
        mark("home-restored")
        hold(4)
        try dryStill("home-restored")
    }

    /// Phone A of the chat film (on testFund's wallet): create
    /// 1,000 sat of ecash, copy it, share it into Messages to the simulator's
    /// demo contact, send.
    func testShare() throws {
        // The live field + XCTest's accessibility = stalls (references/capture.md);
        // these takes never use the welcome, so freeze it.
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        let send = app.buttons["wallet-action-send"]
        waitEnabled(send, timeout: 60)
        hold(2)
        tap(send, "send")
        hold(1.0)
        tap(app.buttons["Ecash. Create ecash"], "ecash")
        hold(1.3)
        keypad("1000")
        hold(0.8)
        tap(app.buttons["cashu.send.ecash.submit"], "send-submit")
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        mark("token")
        hold(2.5)
        try dryStill("token")
        tap(app.buttons["Copy"].firstMatch, "copy-token")
        hold(1.2)
        // Copy → Messages → paste → send: the message is the money.
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        mark("to-messages")
        messages.activate()
        hold(2.0)
        try dryStill("messages")
        // Kate Bell's demo thread (John Appleseed's is the send film's). Both
        // numbers contain 555, so match hers, not a list position.
        let thread = messages.cells.containing(NSPredicate(format: "label CONTAINS 'Kate' OR label CONTAINS '564-8583'")).firstMatch
        if thread.waitForExistence(timeout: 5) { mark("tap:thread"); thread.tap() }
        hold(1.8)
        try dryStill("thread")
        dismissKeyboardTip(messages)
        // "Paste from Cashu" in the keyboard's suggestion bar is a secure
        // paste control: it ignores synthesized taps. The edit menu's Paste
        // accepts them, so long-press the composer itself (found by its
        // placeholder; it sits just above the software keyboard).
        let byPlaceholder = messages.descendants(matching: .any)
            .matching(NSPredicate(format: "placeholderValue == 'Message' OR identifier == 'messageBodyField'")).firstMatch
        mark("press-composer")
        if byPlaceholder.waitForExistence(timeout: 3) {
            byPlaceholder.press(forDuration: 0.9)
        } else {
            let keyboard = messages.keyboards.element
            let y = keyboard.exists && keyboard.frame.minY > 300 ? keyboard.frame.minY - 60 : 830
            messages.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 180, dy: y)).press(forDuration: 0.9)
        }
        hold(0.7)
        try dryStill("composer-menu")
        let paste = messages.menuItems["Paste"].exists ? messages.menuItems["Paste"] : messages.buttons["Paste"].firstMatch
        mark("tap:paste")
        paste.tap()
        hold(1.5)
        try dryStill("pasted")
        let sendButton = messages.buttons.matching(NSPredicate(format: "identifier == 'sendButton' OR label == 'Send'")).firstMatch
        mark("tap:send-message")
        sendButton.tap()
        hold(3)
        try dryStill("sent")
    }

    /// Phone B, and the main film's claim: copy phone A's bubble in Messages,
    /// then a fresh wallet taps the Receive sheet's own Paste button and
    /// receives the token. Run with ALLOW_PASTE=1 (Cashu's "Paste from Other
    /// Apps: Allow"), or the button's clipboard read asks "Allow Paste?".
    func testClaim() throws {
        try copyTokenFromMessages()
        // The live field + XCTest's accessibility = stalls (references/capture.md);
        // these takes never use the welcome, so freeze it.
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        onboardLocal()
        hold(3)
        let receive = app.buttons["wallet-action-receive"]
        waitEnabled(receive, timeout: 60)
        tap(receive, "receive")
        hold(2.0)
        try dryStill("receive-sheet")
        tap(app.buttons["Paste from clipboard"], "paste")
        hold(2.5)
        try dryStill("claim")
        let confirm = app.buttons["receive-token-confirm"]
        waitEnabled(confirm, timeout: 20)
        tap(confirm, "claim")
        hold(4)
        try dryStill("claimed")
    }

    /// Probe, on any existing wallet: does the Paste button prompt? Stills
    /// before and after the tap (allow-paste.sh vs allow-paste.sh revoke).
    /// COPY_FROM_MESSAGES=1 copies Kate Bell's newest bubble first.
    func testPasteProbe() throws {
        if ProcessInfo.processInfo.environment["COPY_FROM_MESSAGES"] == "1" { try copyTokenFromMessages() }
        launch(["ASCII_FIELD_STATIC_TIME": "2.5"])
        let receive = app.buttons["wallet-action-receive"]
        waitEnabled(receive, timeout: 60)
        tap(receive, "receive")
        hold(1.5)
        try still("probe-sheet")
        tap(app.buttons["Paste from clipboard"], "paste")
        hold(1.5)
        try still("probe-after")
    }
}
