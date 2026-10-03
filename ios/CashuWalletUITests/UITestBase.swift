import XCTest

/// Shared base for all CashuWallet UI tests.
///
/// Provides a pre-launched XCUIApplication and the common wallet-creation
/// helpers so individual test files don't duplicate setUp/tearDown or
/// the multi-step onboarding walk-through.
class UITestBase: XCTestCase {
    enum LaunchMode {
        case emptyWallet
        case seededWallet
        case seededWalletWithMint
    }

    var app: XCUIApplication!
    var mintURL: String {
        ProcessInfo.processInfo.environment["NUTSHELL_MINT_URL"] ?? "http://localhost:3338"
    }
    var cdkMintURL: String {
        ProcessInfo.processInfo.environment["CDK_MINT_URL"] ?? "http://localhost:3339"
    }
    var launchMode: LaunchMode { .emptyWallet }
    // Tests that configure their first launch can opt out of an unused launch.
    var launchesAppAutomatically: Bool { true }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment = launchEnvironment(for: launchMode)
        app.launchArguments = [
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        if launchesAppAutomatically {
            app.launch()
        } else {
            // Do not reuse a process left behind by an interrupted prior test.
            app.terminate()
        }
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = nil
    }

    func launchEnvironment(for mode: LaunchMode) -> [String: String] {
        var environment = [
            "CI_INTEGRATION_TEST": "1",
            "RESET_WALLET": "1",
            "UITEST_DISABLE_ANIMATIONS": "1",
            "NUTSHELL_MINT_URL": mintURL,
            "CDK_MINT_URL": cdkMintURL,
        ]

        switch mode {
        case .emptyWallet:
            break
        case .seededWallet:
            environment["UITEST_SEED_WALLET"] = "1"
        case .seededWalletWithMint:
            environment["UITEST_SEED_WALLET"] = "1"
            environment["UITEST_SEED_MINT"] = "1"
            environment["UITEST_SEED_MINT_URL"] = mintURL
        }

        return environment
    }

    // MARK: - Onboarding helpers

    /// Relaunch the existing wallet. Setup flags must never erase or reseed
    /// state when a test is checking persistence or interrupted operations.
    func relaunchPreservingWallet() {
        app.terminate()
        for key in ["RESET_WALLET", "UITEST_SEED_WALLET", "UITEST_SEED_MINT"] {
            app.launchEnvironment.removeValue(forKey: key)
        }
        app.launch()
        waitForMainTab()
    }

    /// Walk through: welcome → create wallet → acknowledge seed → saved seed.
    /// Leaves the app on the "Pick your first mint" screen.
    func createWalletThroughSeed() {
        let create = app.buttons["onboarding-create-wallet"]
        tapWhenReady(create, timeout: 30)

        let ack = app.buttons["onboarding-ack-seed"]
        tapWhenReady(ack, timeout: 15)

        let saved = app.buttons["onboarding-saved-seed"]
        tapWhenReady(saved)
    }

    /// Full onboarding: create wallet, skip mint setup, wait for main tab bar.
    func createWalletAndSkipMint() {
        createWalletThroughSeed()

        let skip = app.buttons["onboarding-skip-mint"]
        tapWhenReady(skip, timeout: 10)

        waitForMainTab()
    }

    /// Full onboarding: create wallet, add live mint, wait for main tab bar.
    func createWalletWithMint(at mintURL: String? = nil) {
        let mintURL = mintURL ?? self.mintURL
        createWalletThroughSeed()

        let addCustom = app.buttons["onboarding-add-custom-mint"]
        tapWhenReady(addCustom, timeout: 10)

        let field = app.textFields["onboarding-custom-mint-field"]
        enterMintURL(mintURL, into: field)
        // Mint setup is not a keyboard-return-key test. Submit through the
        // app's stable control after verifying the complete URL was entered.
        tapWhenReady(app.buttons["onboarding-commit-custom-mint"])
        XCTAssertTrue(field.waitForNonExistence(timeout: 5), "Adding the mint should close URL entry")

        let cont = app.buttons["onboarding-continue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 5))

        XCTAssertTrue(
            cont.waitUntilEnabledAndHittable(timeout: 10),
            "Continue should become tappable after adding a custom mint"
        )
        tapWhenReady(cont)

        waitForMainTab(timeout: 60)
    }

    /// CI has returned from typeText with only a URL prefix and no keyboard,
    /// without XCTest reporting an input error. Verify the value, and allow
    /// one refocus to complete a verified prefix; never submit partial input
    /// or append to unexpected text. This does not retry the test or payment.
    func enterMintURL(
        _ url: String,
        into field: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        guard field.waitForExistence(timeout: 10) else {
            XCTFail("Mint URL field should appear", file: file, line: line)
            return
        }
        for attempt in 1...2 {
            let value = field.value as? String ?? ""
            let prefix = value == field.placeholderValue ? "" : value
            if prefix == url { return }
            guard url.hasPrefix(prefix) else {
                XCTFail("Mint URL field contains unexpected text", file: file, line: line)
                return
            }

            XCTContext.runActivity(named: "Enter complete mint URL (attempt \(attempt))") { _ in
                focusTextField(field, file: file, line: line)
                field.typeText(String(url.dropFirst(prefix.count)))
            }
            let complete = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "value == %@", url), object: field
            )
            if XCTWaiter.wait(for: [complete], timeout: 3) == .completed { return }
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
        XCTAssertEqual(field.value as? String, url,
                       "The complete mint URL must be entered before submission", file: file, line: line)
    }

    func waitForMainTab(timeout: TimeInterval = 20) {
        XCTAssertTrue(
            tabButton("Wallet", timeout: timeout).exists,
            "Main wallet tab bar should appear"
        )
    }

    @discardableResult
    func mainTabBar(
        timeout: TimeInterval = 20,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement {
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(
            tabBar.waitForExistence(timeout: timeout),
            "Main tab bar should appear",
            file: file,
            line: line
        )
        return tabBar
    }

    @discardableResult
    func tabButton(
        _ title: String,
        timeout: TimeInterval = 20,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement {
        let button = app.tabBars.firstMatch.buttons[title].firstMatch
        XCTAssertTrue(
            button.waitForExistence(timeout: timeout),
            "\(title) tab should appear",
            file: file,
            line: line
        )
        return button
    }

    func waitForSelectedTab(
        _ title: String,
        timeout: TimeInterval = 5,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let button = tabButton(title, timeout: timeout, file: file, line: line)
        waitForSelectedTab(button, title: title, timeout: timeout, file: file, line: line)
    }

    private func waitForSelectedTab(
        _ button: XCUIElement,
        title: String,
        timeout: TimeInterval,
        file: StaticString,
        line: UInt
    ) {
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: button
        )
        let result = XCTWaiter.wait(for: [selected], timeout: timeout)
        XCTAssertEqual(
            result,
            .completed,
            "\(title) tab should become selected",
            file: file,
            line: line
        )
    }

    func tapTab(
        _ title: String,
        timeout: TimeInterval = 5,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let button = tabButton(title, timeout: timeout, file: file, line: line)
        tapWhenReady(button, timeout: timeout, file: file, line: line)
        waitForSelectedTab(button, title: title, timeout: timeout, file: file, line: line)
    }

    func screen(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    func scrollToButton(
        _ button: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let scrollView = app.scrollViews.firstMatch
        XCTAssertTrue(scrollView.waitForExistence(timeout: 5), file: file, line: line)
        for attempt in 0...10 {
            // XCTest can report a SwiftUI button as hittable below the viewport.
            // Require its whole frame to be visible before trusting a tap.
            let viewport = scrollView.frame.intersection(app.frame)
            if button.exists && viewport.contains(button.frame) && button.isHittable {
                return
            }
            if attempt < 10 { scrollView.swipeUp() }
        }
        XCTFail("Button must be visible inside the scroll view: \(button.label)", file: file, line: line)
    }

    /// A field can be hittable before an insertion transition finishes assigning focus.
    func focusTextField(_ field: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        tapWhenReady(field, file: file, line: line)
        if !app.keyboards.element.waitForExistence(timeout: 2) {
            tapWhenReady(field, file: file, line: line)
        }
        XCTAssertTrue(
            app.keyboards.element.waitForExistence(timeout: 10),
            "Tapping the text field should raise the keyboard", file: file, line: line
        )
    }

    func tapWhenReady(
        _ element: XCUIElement,
        timeout: TimeInterval = 5,
        message: String = "Element should be enabled and tappable",
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let ready = element.waitUntilEnabledAndHittable(timeout: timeout)
        if !ready {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.lifetime = .keepAlways
            add(screenshot)
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
        }
        XCTAssertTrue(
            ready,
            message,
            file: file,
            line: line
        )
        element.tap()
    }
}

extension XCUIElement {
    func waitUntilEnabledAndHittable(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)

        while Date() < deadline {
            if exists && isEnabled && isHittable {
                return true
            }

            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
        }

        return exists && isEnabled && isHittable
    }

    /// Polls `isEnabled` until it matches, for controls whose state follows
    /// async work (a settling balance) rather than the tap just before it.
    func waitUntilEnabled(_ enabled: Bool, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)

        while Date() < deadline {
            if exists && isEnabled == enabled {
                return true
            }

            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
        }

        return exists && isEnabled == enabled
    }
}
