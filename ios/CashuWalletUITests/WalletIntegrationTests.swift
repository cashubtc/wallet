import XCTest

/// UI integration tests driving the real onboarding flow end-to-end.
///
/// Each test launches the app with `RESET_WALLET=1`, which makes `WalletManager`
/// wipe any persisted wallet on startup so onboarding always begins from a
/// known-empty state (see `IntegrationTestConfig` / `WalletManager.initialize`).
///
/// The mint-add smoke test connects to the live Nutshell mint started by CI.
/// Nutshell/CDK backend parity is covered by the faster mint integration suite.
final class WalletIntegrationTests: UITestBase {

    // MARK: - Tests

    /// Create a wallet and skip mint setup — should land on the main tab bar.
    func testOnboardingCreateWalletAndSkipMint() throws {
        createWalletThroughSeed()

        let skip = app.buttons["onboarding-skip-mint"]
        tapWhenReady(skip, timeout: 10, message: "First-mint step should appear")

        waitForMainTab()
    }

    /// Create a wallet and connect the live Nutshell mint via a custom URL.
    func testOnboardingAddNutshellMint() throws {
        assertCanAddMint(at: mintURL)
    }

    private func assertCanAddMint(at url: String) {
        createWalletWithMint(at: url)

        // The added mint should be listed on the Mints tab.
        tapTab("Mints")
        let mintRow = app.staticTexts[url]
        XCTAssertTrue(mintRow.waitForExistence(timeout: 10), "Added mint should appear in the Mints list")
    }
}

/// Real UI receive/send flow. Fixture endpoints generate invoices, never balances.
class LivePaymentUITestBase: UITestBase {
    var fixtureURL: String!
    var fixtureSession: String!
    var backend: String { "cdk" }
    override var mintURL: String { fixtureURL + "/sessions/" + fixtureSession + "/mint/" + backend }

    override func setUpWithError() throws {
        // These journeys include onboarding, real receive/send requests and
        // relaunch. Each transport can consume its own bounded deadline.
        executionTimeAllowance = 360
        fixtureURL = ProcessInfo.processInfo.environment["PAYMENT_FIXTURE_URL"]
        try XCTSkipIf(fixtureURL == nil, "Local payment fixtures are required")
        fixtureSession = try fixtureCall("/sessions", method: "POST")["id"] as? String
        try super.setUpWithError()
    }

    override func tearDownWithError() throws {
        if fixtureSession != nil, testRun?.hasSucceeded == false {
            // Preserve transport outcomes before deleting the isolated session.
            // The fixture records methods/statuses, never invoices or proofs.
            if let state = try? fixtureCall("/sessions/" + fixtureSession),
               let requests = state["requests"],
               let data = try? JSONSerialization.data(withJSONObject: requests, options: [.prettyPrinted]),
               let text = String(data: data, encoding: .utf8) {
                let attachment = XCTAttachment(string: text)
                attachment.name = "Payment fixture request outcomes"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        try super.tearDownWithError()
        if fixtureSession != nil { _ = try fixtureCall("/sessions/" + fixtureSession, method: "DELETE") }
    }

    override func launchEnvironment(for mode: LaunchMode) -> [String: String] {
        var environment = super.launchEnvironment(for: mode)
        environment["UITEST_LIVE_PAYMENTS"] = "1"
        environment["UITEST_PAYMENT_RELAY"] = fixtureURL.replacingOccurrences(of: "http://", with: "ws://") + "/sessions/" + fixtureSession + "/relay"
        return environment
    }

    func fixtureCall(_ path: String, method: String = "GET", body: [String: Any]? = nil) throws -> [String: Any] {
        var request = URLRequest(url: URL(string: fixtureURL + path)!)
        request.httpMethod = method
        request.timeoutInterval = 15
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = expectation(description: "Payment fixture response")
        var result: Result<[String: Any], Error>!
        URLSession.shared.dataTask(with: request) { data, response, error in
            defer { done.fulfill() }
            result = Result {
                if let error { throw error }
                guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode), let data else {
                    throw NSError(domain: "PaymentFixture", code: 1)
                }
                return try JSONSerialization.jsonObject(with: data) as! [String: Any]
            }
        }.resume()
        wait(for: [done], timeout: 20)
        return try XCTUnwrap(result, "Fixture returned no response").get()
    }

    func receiveThroughUI() {
        tapWhenReady(app.buttons["wallet-action-receive"])
        tapWhenReady(app.buttons["wallet-flow-receiveLightning"])
        XCTAssertTrue(screen("receive-lightning-screen").waitForExistence(timeout: 15))
        for digit in ["1", "0", "0"] { tapWhenReady(app.buttons[digit]) }
        tapWhenReady(app.buttons["receive-lightning-create-request"])
        // The fixture permits 30 seconds per upstream request. Quote creation,
        // status polling and issuance are separate operations, so a 30-second
        // end-to-end deadline can expire while a valid status check is running.
        XCTAssertTrue(app.staticTexts["Payment Received!"].waitForExistence(timeout: 90),
                      "The live invoice must settle and issue ecash before continuing")
        tapWhenReady(app.buttons["Done"])
        waitForMainTab()
    }

    func receiveThenPayAndReopenHistory() throws {
        createWalletWithMint()
        receiveThroughUI()
        let invoice = try fixtureCall("/sessions/" + fixtureSession + "/invoice", method: "POST", body: ["amount": 21])["invoice"] as! String
        tapWhenReady(app.buttons["wallet-action-send"])
        let field = app.descendants(matching: .any).matching(identifier: "Address, invoice, or Cashu Request").firstMatch
        tapWhenReady(field)
        field.typeText(invoice)
        let pay = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Pay 21")).firstMatch
        if backend == "nutshell" {
            // Nutshell 0.20.1 returns PENDING before its background melt task
            // persists that state. Match the native integration fixture's
            // pacing so the first status read cannot race that backend write.
            _ = try fixtureCall("/sessions/" + fixtureSession + "/faults", method: "POST", body: [
                "method": "GET", "path": "/v1/melt/quote/bolt11/",
                "action": "delay", "seconds": 1, "remaining": 1,
            ])
        }
        tapWhenReady(pay, timeout: 20)
        XCTAssertTrue(app.staticTexts["Payment Sent!"].waitForExistence(timeout: 30))
        tapWhenReady(app.buttons["Done"])
        tapTab("History")
        XCTAssertTrue(app.staticTexts["Lightning paid"].firstMatch.waitForExistence(timeout: 10))
        app.terminate()
        app.launchEnvironment["RESET_WALLET"] = "0"
        app.launch()
        waitForMainTab(timeout: 30)
        tapTab("History")
        XCTAssertTrue(app.staticTexts["Lightning paid"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Lightning received"].firstMatch.exists)
    }
}

final class LiveCdkPaymentUITests: LivePaymentUITestBase {
    /// cashubtc/wallet#381: the hint Paste leaves must survive the field's own
    /// change event. This CDK FakeWallet mint doesn't advertise NUT-05
    /// `amountless`, so an amountless invoice lands on the caution instead of
    /// the amount keypad. Android twin: `AmountlessInvoiceJourneyTest`.
    func testPastedHintsStayVisible() throws {
        createWalletWithMint()
        receiveThroughUI()
        tapWhenReady(app.buttons["wallet-action-send"])

        paste(Self.amountlessInvoice)
        let caution = app.staticTexts["None of your mints can pay invoices without an amount. Ask for one with the amount set."]
        XCTAssertTrue(caution.waitForExistence(timeout: 10), "An amountless invoice no mint can pay must explain why")
        assertStillShownAfterDebounce(caution)
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "amountless-invoice-caution"
        capture.lifetime = .keepAlways
        add(capture)

        tapWhenReady(app.buttons["Clear"])
        paste("hello world")
        let unrecognized = app.staticTexts["Unrecognized — try a Lightning address, invoice, Bitcoin address, or Cashu Request"]
        XCTAssertTrue(unrecognized.waitForExistence(timeout: 10))
        assertStillShownAfterDebounce(unrecognized)
    }

    /// Paste through the field's own button, as a user would. Reading another
    /// app's pasteboard content raises the system paste prompt.
    private func paste(_ text: String) {
        UIPasteboard.general.string = text
        tapWhenReady(app.buttons["Paste from clipboard"])
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Paste"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
    }

    /// Typing detection re-runs 400 ms after the field changes; a paste's hint
    /// must still be there afterwards.
    private func assertStillShownAfterDebounce(_ hint: XCUIElement) {
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertTrue(hint.exists, "The hint was cleared by the field's change event")
    }

    // BOLT #11 example: donation invoice with no amount in the HRP.
    private static let amountlessInvoice = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"

    func testQuoteAndPaymentUseSameProcessingLayout() throws {
        createWalletWithMint()
        receiveThroughUI()
        let invoice = try fixtureCall("/sessions/" + fixtureSession + "/invoice", method: "POST", body: ["amount": 21])["invoice"] as! String
        for path in ["/v1/melt/quote/bolt11", "/v1/melt/bolt11"] {
            _ = try fixtureCall("/sessions/" + fixtureSession + "/faults", method: "POST", body: [
                "method": "POST", "path": path, "action": "delay", "seconds": 15, "remaining": 1,
            ])
        }
        tapWhenReady(app.buttons["wallet-action-send"])
        let field = app.descendants(matching: .any).matching(identifier: "Address, invoice, or Cashu Request").firstMatch
        tapWhenReady(field)
        field.typeText(invoice)
        let processing = app.staticTexts["Processing…"]
        XCTAssertTrue(processing.waitForExistence(timeout: 10))
        let quoteTitleTop = processing.frame.minY
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'From '")).firstMatch.exists)
        let quoteCapture = XCTAttachment(screenshot: app.screenshot())
        quoteCapture.name = "quote-processing"
        quoteCapture.lifetime = .keepAlways
        add(quoteCapture)

        let pay = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Pay 21")).firstMatch
        // 15 seconds of intentional delay plus the fixture's 30-second
        // upstream deadline, with room for the UI to publish the response.
        tapWhenReady(pay, timeout: 60)
        XCTAssertTrue(processing.waitForExistence(timeout: 10))
        XCTAssertEqual(processing.frame.minY, quoteTitleTop, accuracy: 1)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'From '")).firstMatch.exists)
        let paymentCapture = XCTAttachment(screenshot: app.screenshot())
        paymentCapture.name = "payment-processing"
        paymentCapture.lifetime = .keepAlways
        add(paymentCapture)
        XCTAssertTrue(app.staticTexts["Payment Sent!"].waitForExistence(timeout: 60))
    }

    func testReceivePayAndRelaunch() throws { try receiveThenPayAndReopenHistory() }
}

final class LiveNutshellPaymentUITests: LivePaymentUITestBase {
    override var backend: String { "nutshell" }
    func testReceivePayAndRelaunch() throws { try receiveThenPayAndReopenHistory() }
}
