import XCTest

/// UI tests for the Receive flow options sheet.
final class ReceiveUITests: UITestBase {
    override var launchMode: LaunchMode { .seededWalletWithMint }

    // MARK: - Helpers

    private var receiveButton: XCUIElement {
        app.buttons["wallet-action-receive"]
    }

    private var receiveEcashOption: XCUIElement {
        app.buttons["wallet-flow-receiveEcash"]
    }

    private var receiveBitcoinOption: XCUIElement {
        app.buttons["wallet-flow-receiveLightning"]
    }

    private func openReceiveChooser() {
        XCTAssertTrue(
            receiveButton.waitForExistence(timeout: 10),
            "Receive button should be visible on wallet tab"
        )
        receiveButton.tap()

        XCTAssertTrue(
            receiveEcashOption.waitForExistence(timeout: 10),
            "Receive chooser should show the Ecash option"
        )
    }

    // MARK: - Tests

    func testReceiveOptionsAppear() throws {
        waitForMainTab()

        openReceiveChooser()

        XCTAssertTrue(
            receiveBitcoinOption.waitForExistence(timeout: 5),
            "Receive chooser should show the Bitcoin option"
        )
    }

    func testReceiveSheetCanBeDismissed() throws {
        waitForMainTab()

        openReceiveChooser()

        let closeButton = app.buttons["wallet-chooser-close"]
        XCTAssertTrue(closeButton.waitForExistence(timeout: 5), "Receive chooser should show a close button")
        closeButton.tap()

        XCTAssertTrue(tabButton("Wallet", timeout: 5).exists)
    }

    func testBitcoinOptionOpensLightningFlow() throws {
        waitForMainTab()

        openReceiveChooser()

        XCTAssertTrue(
            receiveBitcoinOption.waitForExistence(timeout: 10),
            "Receive chooser should show the Bitcoin option"
        )
        receiveBitcoinOption.tap()

        let createRequestButton = app.buttons["receive-lightning-create-request"]
        XCTAssertTrue(createRequestButton.waitForExistence(timeout: 10), "Lightning receive view should open")
    }
}
