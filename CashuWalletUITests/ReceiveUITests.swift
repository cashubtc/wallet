import XCTest

/// UI tests for the Receive flow options sheet.
final class ReceiveUITests: XCTestCase {

    private var app: XCUIApplication!
    private let mintURL = ProcessInfo.processInfo.environment["NUTSHELL_MINT_URL"] ?? "http://localhost:3338"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment = [
            "CI_INTEGRATION_TEST": "1",
            "RESET_WALLET": "1"
        ]
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    // MARK: - Helpers

    private func createWalletAndSkipMint() {
        let create = app.buttons["onboarding-create-wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 30))
        create.tap()

        let ack = app.buttons["onboarding-ack-seed"]
        XCTAssertTrue(ack.waitForExistence(timeout: 15))
        ack.tap()

        let saved = app.buttons["onboarding-saved-seed"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()

        let skip = app.buttons["onboarding-skip-mint"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10))
        skip.tap()

        let walletTab = app.tabBars.buttons["Wallet"]
        XCTAssertTrue(walletTab.waitForExistence(timeout: 20))
    }

    private func createWalletWithMint() {
        let create = app.buttons["onboarding-create-wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 30))
        create.tap()

        let ack = app.buttons["onboarding-ack-seed"]
        XCTAssertTrue(ack.waitForExistence(timeout: 15))
        ack.tap()

        let saved = app.buttons["onboarding-saved-seed"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()

        let addCustom = app.buttons["onboarding-add-custom-mint"]
        XCTAssertTrue(addCustom.waitForExistence(timeout: 10))
        addCustom.tap()

        let field = app.textFields["onboarding-custom-mint-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(mintURL)

        app.buttons["onboarding-commit-custom-mint"].tap()

        let cont = app.buttons["onboarding-continue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 5))
        cont.tap()

        let walletTab = app.tabBars.buttons["Wallet"]
        XCTAssertTrue(walletTab.waitForExistence(timeout: 30))
    }

    // MARK: - Tests

    func testReceiveOptionsAppear() throws {
        createWalletAndSkipMint()

        // Open receive sheet via the Receive button on the wallet tab
        let receiveButton = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Receive'")).firstMatch
        XCTAssertTrue(receiveButton.waitForExistence(timeout: 10), "Receive button should be visible on wallet tab")
        receiveButton.tap()

        // All three options should appear
        let pasteOption = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Paste Ecash Token'")).firstMatch
        let scanOption = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Scan QR Code'")).firstMatch
        let paymentRequestOption = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Payment Request'")).firstMatch

        XCTAssertTrue(pasteOption.waitForExistence(timeout: 10), "Paste Ecash Token option should appear")
        XCTAssertTrue(scanOption.exists, "Scan QR Code option should appear")
        XCTAssertTrue(paymentRequestOption.exists, "Payment Request option should appear")
    }

    func testReceiveSheetCanBeDismissed() throws {
        createWalletAndSkipMint()

        let receiveButton = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Receive'")).firstMatch
        XCTAssertTrue(receiveButton.waitForExistence(timeout: 10))
        receiveButton.tap()

        // Wait for receive options to appear, then swipe down to dismiss
        let pasteOption = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Paste Ecash Token'")).firstMatch
        XCTAssertTrue(pasteOption.waitForExistence(timeout: 10))

        // Dismiss by swiping down on the sheet
        app.swipeDown()

        // Wallet tab should still be visible
        XCTAssertTrue(app.tabBars.buttons["Wallet"].waitForExistence(timeout: 5))
    }

    func testPaymentRequestOptionOpensLightningFlow() throws {
        createWalletWithMint()

        let receiveButton = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Receive'")).firstMatch
        XCTAssertTrue(receiveButton.waitForExistence(timeout: 10))
        receiveButton.tap()

        let paymentRequestOption = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Payment Request'")).firstMatch
        XCTAssertTrue(paymentRequestOption.waitForExistence(timeout: 10))
        paymentRequestOption.tap()

        // Lightning receive view should appear with a method selector or QR
        let lightningContent = app.otherElements.matching(NSPredicate(format: "label CONTAINS 'Receive method'")).firstMatch
        XCTAssertTrue(lightningContent.waitForExistence(timeout: 10), "Lightning receive view should open")
    }
}
