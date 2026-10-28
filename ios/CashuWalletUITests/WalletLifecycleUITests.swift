import XCTest

/// Full app journeys against the local FakeWallet mint started by CI.
/// Setup itself follows onboarding; all money-moving operations use real CDK.
final class WalletLifecycleUITests: UITestBase {
    override func setUpWithError() throws {
        // These journeys include onboarding, live mint settlement, and wallet
        // relaunches. Keep a bound without killing a successful round trip as
        // it reaches its final balance assertion on slower hosted runners.
        // A slow runner has stretched the two-relaunch mint journey (~90 s
        // normally) past 3 minutes, so allow 5, under CI's 6-minute maximum.
        // Individual waits stay short and bounded.
        executionTimeAllowance = 300
        try super.setUpWithError()
    }

    func testAddDuplicateSwitchAndRemoveMultiUnitMintPersists() {
        createWalletWithMint()
        tapTab("Mints")
        addMint(url: mintURL + "/")
        // Duplicate detection queues behind wallet startup/mint operations.
        // Wait for the submitted operation to settle before inspecting its
        // result; the error's visibility is not a ten-second network deadline.
        let duplicateFinished = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: app.buttons["mints-add-submit-button"]
        )
        XCTAssertEqual(XCTWaiter.wait(for: [duplicateFinished], timeout: 30), .completed,
                       "Duplicate mint validation should finish before checking its error")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "already")).firstMatch
            .waitForExistence(timeout: 10))
        tapWhenReady(app.buttons["mints-add-clear-button"])
        app.textFields["mints-add-url-field"].typeText(cdkMintURL)
        tapWhenReady(app.buttons["mints-add-submit-button"])
        let second = mintRow(url: cdkMintURL)
        tapWhenReady(second, timeout: 30)
        scrollTo("Set as Default")
        let setDefault = app.buttons["Set as Default"]
        tapWhenReady(setDefault)
        // The update queues behind mint loading. A successful tap can leave
        // this button showing a spinner; wait for the active mint to change
        // before leaving the detail screen or relaunching the wallet.
        XCTAssertTrue(setDefault.waitForNonExistence(timeout: 30),
                      "Setting the default mint should finish before navigating back")
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))
        assertDefaultMint(second)
        relaunchPreservingWallet()
        tapTab("Mints")
        assertDefaultMint(second)
        tapWhenReady(second)
        scrollTo("Remove mint")
        tapWhenReady(app.buttons["Remove mint"])
        tapWhenReady(app.buttons["Cancel"])
        XCTAssertTrue(app.buttons["Remove mint"].exists)
        tapWhenReady(app.buttons["Remove mint"])
        tapWhenReady(app.buttons["Remove"])
        // The CDK mint registers more than one unit; removal must still finish
        // and dismiss the detail screen before the process is killed.
        XCTAssertTrue(app.buttons["Remove mint"].waitForNonExistence(timeout: 30))
        XCTAssertTrue(second.waitForNonExistence(timeout: 15))
        assertDefaultMint(mintRow(url: mintURL))
        relaunchPreservingWallet()
        tapTab("Mints")
        XCTAssertTrue(mintRow(url: mintURL).waitForExistence(timeout: 10))
        XCTAssertFalse(second.exists)
        assertDefaultMint(mintRow(url: mintURL))
    }

    private func assertDefaultMint(
        _ row: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let updated = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true AND value == %@", "Default mint"),
            object: row
        )
        XCTAssertEqual(XCTWaiter.wait(for: [updated], timeout: 10), .completed,
                       "The mint row should identify the persisted default mint", file: file, line: line)
    }

    func testRemovingLastMintReturnsToEmptyWalletAfterRelaunch() {
        createWalletWithMint()
        tapTab("Mints")
        tapWhenReady(mintRow(url: mintURL))
        scrollTo("Remove mint")
        tapWhenReady(app.buttons["Remove mint"])
        XCTAssertTrue(app.staticTexts["Remove mint?"].waitForExistence(timeout: 5))
        tapWhenReady(app.buttons["Remove"])
        // The list row is already absent while its detail screen is open.
        // Wait for successful removal to dismiss that screen before killing
        // the process; otherwise the pending operation can be interrupted.
        XCTAssertTrue(app.buttons["Remove mint"].waitForNonExistence(timeout: 30))
        XCTAssertTrue(mintRow(url: mintURL).waitForNonExistence(timeout: 15))
        relaunchPreservingWallet()
        XCTAssertTrue(app.staticTexts["Add a mint to get started"].waitForExistence(timeout: 10))
        tapWhenReady(app.buttons["wallet-action-send"])
        XCTAssertTrue(app.staticTexts["Add a mint first"].waitForExistence(timeout: 5))
    }

    /// The full balance cycle with fiat on: bitcoin → fiat → hidden → bitcoin.
    /// Hidden masks History's row amount; opening the payment still shows it.
    func testBalanceCyclesThroughFiatAndPrivate() {
        createWalletWithMint()
        receive(sats: "100")
        assertBalance("100")

        tapWhenReady(app.buttons["wallet-settings-button"])
        let currency = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Currency")).firstMatch
        tapWhenReady(currency)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "USD, ")).firstMatch)
        XCTAssertTrue(screen("settings-screen").waitForExistence(timeout: 5))
        // UI tests don't auto-refresh the price; fetch the deterministic one.
        tapWhenReady(currency)
        tapWhenReady(app.buttons["Refresh price"])
        XCTAssertTrue(
            app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Updated")).firstMatch
                .waitForExistence(timeout: 10)
        )
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "USD, ")).firstMatch)
        XCTAssertTrue(screen("settings-screen").waitForExistence(timeout: 5))
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))

        let balance = app.buttons["wallet-balance-sat"]
        XCTAssertTrue(balance.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["$0.10"].waitForExistence(timeout: 10), "The fiat line should appear")
        tapWhenReady(balance)
        waitForLabel(of: balance, matching: "label == %@", "Balance: $0.10")
        tapWhenReady(balance)
        waitForLabel(of: balance, matching: "label == %@", "Balance hidden")

        tapTab("History")
        let row = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Lightning received, amount hidden")
        ).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "History rows must not read the amount while hidden")
        tapWhenReady(row)
        XCTAssertTrue(
            app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", "100"))
                .firstMatch
                .waitForExistence(timeout: 10),
            "The transaction detail keeps the real amount"
        )
        // The receipt has no close button; drag its system grabber away.
        let grabber = app.buttons["Sheet Grabber"]
        XCTAssertTrue(grabber.waitForExistence(timeout: 5))
        grabber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.1,
                   thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98)),
                   withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(grabber.waitForNonExistence(timeout: 5), "The receipt should dismiss")

        tapTab("Wallet")
        tapWhenReady(balance)
        waitForLabel(of: balance, matching: "label BEGINSWITH %@", "Balance: 100")
    }

    func testReceiveLightningPersistsBalanceAndHistory() {
        createWalletWithMint()
        receive(sats: "100")
        assertBalance("100")
        relaunchPreservingWallet()
        assertBalance("100")
        tapTab("History")
        let payment = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Lightning received")).firstMatch
        tapWhenReady(payment)
        XCTAssertTrue(app.staticTexts["Lightning received"].waitForExistence(timeout: 10))
    }

    func testSendEcashUpdatesBalanceAndPendingReceipt() {
        createWalletWithMint()
        receive(sats: "100")
        openSendEcash()
        tapWhenReady(app.buttons["2"])
        tapWhenReady(app.buttons["5"])
        tapWhenReady(app.buttons["cashu.send.ecash.submit"])
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["Copy"].exists)
        tapWhenReady(app.buttons["Close"])
        assertBalance("75")
        relaunchPreservingWallet()
        assertBalance("75")
        tapTab("History")
        let outgoing = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Ecash sent")).firstMatch
        let incoming = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Lightning received")).firstMatch
        XCTAssertTrue(outgoing.waitForExistence(timeout: 10))
        tapWhenReady(app.buttons["Filter transactions"])
        tapWhenReady(app.buttons["Pending only"])
        XCTAssertTrue(outgoing.exists)
        XCTAssertFalse(incoming.exists)
        tapWhenReady(app.buttons["Filter transactions"])
        tapWhenReady(app.buttons["Completed only"])
        XCTAssertTrue(incoming.exists)
        XCTAssertFalse(outgoing.exists)
        tapWhenReady(app.buttons["Search history"])
        app.searchFields.firstMatch.typeText("no matching payment")
        XCTAssertTrue(app.staticTexts["No Results"].waitForExistence(timeout: 5))
    }

    func testAmountClearAndInsufficientBalanceCannotSpend() {
        createWalletWithMint()
        receive(sats: "100")
        // Send Ecash checks the amount against the mint's cached balance, so
        // let the received sats settle before typing against them.
        assertBalance("100")
        openSendEcash()
        let submit = app.buttons["cashu.send.ecash.submit"]
        XCTAssertFalse(submit.isEnabled)
        tapWhenReady(app.buttons["2"])
        tapWhenReady(app.buttons["5"])
        tapWhenReady(app.buttons["Delete"])
        XCTAssertTrue(submit.waitUntilEnabled(true, timeout: 5), "2 sat fits the balance")
        app.buttons["Delete"].press(forDuration: 0.6)
        XCTAssertTrue(submit.waitUntilEnabled(false, timeout: 5), "A cleared amount can't be sent")
        for _ in 0..<3 { tapWhenReady(app.buttons["9"]) }
        XCTAssertTrue(app.staticTexts["Insufficient balance"].waitForExistence(timeout: 5))
        XCTAssertFalse(submit.isEnabled)
        tapWhenReady(app.buttons["Close"])
        assertBalance("100")
    }

    func testEcashRoundTripThroughClipboardRestoresBalance() {
        createWalletWithMint()
        receive(sats: "100")
        tapWhenReady(app.buttons["wallet-settings-button"])
        let privacy = app.buttons["Privacy"]
        for _ in 0..<8 {
            if privacy.isHittable { break }
            app.scrollViews.firstMatch.swipeUp()
        }
        tapWhenReady(privacy)
        let polling = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Check sent ecash")).firstMatch
        if (polling.value as? String) != "0" {
            polling.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        }
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))
        openSendEcash()
        tapWhenReady(app.buttons["2"])
        tapWhenReady(app.buttons["5"])
        tapWhenReady(app.buttons["cashu.send.ecash.submit"])
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        tapWhenReady(app.buttons["cashu.send.ecash.check-status"])
        XCTAssertTrue(app.staticTexts["This token has not been claimed yet."].waitForExistence(timeout: 15))
        tapWhenReady(app.buttons["Copy"])
        tapWhenReady(app.buttons["Close"])
        assertBalance("75")
        // The balance reads through the closing sheet; Receive is tappable only
        // once the sheet has gone, which can take longer than 5 s on CI.
        tapWhenReady(app.buttons["wallet-action-receive"], timeout: 15)
        tapWhenReady(app.buttons["Paste from clipboard"])
        let receive = app.buttons["receive-token-confirm"]
        tapWhenReady(receive)
        XCTAssertTrue(app.staticTexts["Payment Received!"].waitForExistence(timeout: 30))
        tapWhenReady(app.buttons["Done"])
        assertBalance("100")
    }

    func testQuickLockCanBeSelectedAndRemovedWithoutLosingAmount() {
        createWalletWithMint()
        receive(sats: "100")
        tapWhenReady(app.buttons["wallet-settings-button"])
        let row = app.buttons["Locked Ecash"]
        for _ in 0..<8 {
            if row.isHittable { break }
            app.scrollViews.firstMatch.swipeUp()
        }
        tapWhenReady(row)
        let quickLock = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", "Quick lock to my key")).firstMatch
        if (quickLock.value as? String) != "1" {
            quickLock.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        }
        relaunchPreservingWallet()
        openSendEcash()
        tapWhenReady(app.buttons["2"])
        tapWhenReady(app.buttons["5"])
        tapWhenReady(app.buttons["Lock ecash"])
        tapWhenReady(app.buttons["Lock to my key"])
        XCTAssertTrue(app.buttons["Locked to public key"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["cashu.send.ecash.submit"].isEnabled)
        tapWhenReady(app.buttons["Remove lock"])
        XCTAssertFalse(app.buttons["Locked to public key"].exists)
        tapWhenReady(app.buttons["cashu.send.ecash.submit"])
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        tapWhenReady(app.buttons["Close"])
        assertBalance("75")
    }

    func testRestoreFromRevealedTestSeedRecoversFunds() {
        app.terminate()
        app.launchEnvironment["UITEST_AUTHENTICATION"] = "allow"
        app.launch()
        createWalletWithMint()
        receive(sats: "100")
        tapWhenReady(app.buttons["wallet-settings-button"])
        tapWhenReady(app.buttons["Backup & Restore"])
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Backup seed phrase")).firstMatch)
        tapWhenReady(app.buttons["Reveal Recovery Phrase"])
        XCTAssertTrue(app.buttons["Copy Recovery Phrase"].waitForExistence(timeout: 5))
        // Read only this newly created synthetic wallet's words through the UI.
        // Do not log or attach the phrase to assertion messages.
        let words = (1...12).map { index in
            app.descendants(matching: .any).matching(
                NSPredicate(format: "label BEGINSWITH %@", "Word \(index), ")
            ).firstMatch.label.components(separatedBy: ", ").last!
        }
        app.staticTexts["Backup Wallet"].swipeDown()
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Restore,")).firstMatch)
        let field = app.textFields.firstMatch
        tapWhenReady(field)
        for (index, word) in words.enumerated() {
            field.typeText(word)
            tapWhenReady(app.buttons[word].firstMatch)
            if index < 11 {
                XCTAssertTrue(app.textFields["word \(index + 2)"].waitForExistence(timeout: 5))
            }
        }
        tapWhenReady(app.buttons["Next"])
        let mintField = app.textFields["mint.example.com"]
        tapWhenReady(mintField)
        mintField.typeText(mintURL)
        tapWhenReady(app.buttons["Add"])
        tapWhenReady(app.buttons["Restore from 1 mint"])
        XCTAssertTrue(app.staticTexts["Restore Complete"].waitForExistence(timeout: 60))
        XCTAssertTrue(app.staticTexts["Recovered: 100 sats"].exists)
        tapWhenReady(app.buttons["Continue"])
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))
        tapWhenReady(app.navigationBars.buttons.element(boundBy: 0))
        assertBalance("100")
        relaunchPreservingWallet()
        assertBalance("100")
    }

    func testUsdAmountEntryAndSendUseCents() {
        createWalletWithMint(at: cdkMintURL)
        tapWhenReady(app.buttons["wallet-action-receive"])
        tapWhenReady(app.buttons["wallet-flow-receiveLightning"])
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Unit:")).firstMatch)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "USD")).firstMatch)
        tapWhenReady(app.buttons["1"])
        tapWhenReady(app.buttons["0"])
        tapWhenReady(app.buttons["receive-lightning-create-request"])
        XCTAssertTrue(app.staticTexts["Payment Received!"].waitForExistence(timeout: 45))
        tapWhenReady(app.buttons["Done"])
        openSendEcash()
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Unit:")).firstMatch)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "USD")).firstMatch)
        tapWhenReady(app.buttons["2"])
        tapWhenReady(app.buttons["Decimal point"])
        tapWhenReady(app.buttons["5"])
        tapWhenReady(app.buttons["0"])
        tapWhenReady(app.buttons["9"])
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Unit:")).firstMatch)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "USD")).firstMatch)
        tapWhenReady(app.buttons["cashu.send.ecash.submit"])
        XCTAssertTrue(app.staticTexts["Pending Ecash"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["$2.50"].exists)
        XCTAssertTrue(app.staticTexts["USD"].exists)
        tapWhenReady(app.buttons["Close"])
        relaunchPreservingWallet()
        tapTab("History")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Ecash sent", "$2.50")).firstMatch
            .waitForExistence(timeout: 10))
    }

    private func receive(sats: String) {
        tapWhenReady(app.buttons["wallet-action-receive"])
        tapWhenReady(app.buttons["wallet-flow-receiveLightning"])
        for digit in sats { tapWhenReady(app.buttons[String(digit)]) }
        tapWhenReady(app.buttons["receive-lightning-create-request"])
        XCTAssertTrue(app.staticTexts["Payment Received!"].waitForExistence(timeout: 45))
        tapWhenReady(app.buttons["Done"])
        waitForMainTab()
    }

    private func openSendEcash() {
        tapWhenReady(app.buttons["wallet-action-send"])
        tapWhenReady(app.buttons["Ecash. Create ecash"])
        XCTAssertTrue(app.buttons["cashu.send.ecash.submit"].waitForExistence(timeout: 10))
    }

    private func waitForLabel(of element: XCUIElement, matching format: String, _ value: String) {
        let match = XCTNSPredicateExpectation(predicate: NSPredicate(format: format, value), object: element)
        XCTAssertEqual(
            XCTWaiter.wait(for: [match], timeout: 10), .completed,
            "Expected a label matching \(format) \(value), got \"\(element.label)\""
        )
    }

    private func assertBalance(_ amount: String) {
        let balance = app.descendants(matching: .any).matching(
            NSPredicate(format: "label MATCHES %@", "Balance: .*\\b" + amount + "\\b.*")
        ).firstMatch
        XCTAssertTrue(balance.waitForExistence(timeout: 15), "Wallet must display the settled balance")
    }

    private func mintRow(url: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", url)).firstMatch
    }

    private func addMint(url: String) {
        tapWhenReady(app.buttons["mints-add-button"])
        let field = app.textFields["mints-add-url-field"]
        tapWhenReady(field)
        field.typeText(url)
        tapWhenReady(app.buttons["mints-add-submit-button"])
    }

    private func scrollTo(_ title: String) {
        scrollToButton(app.buttons[title])
    }
}
