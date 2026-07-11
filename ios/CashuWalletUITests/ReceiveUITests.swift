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
        tapWhenReady(
            receiveButton,
            timeout: 10,
            message: "Receive button should be visible on wallet tab"
        )

        XCTAssertTrue(
            receiveEcashOption.waitForExistence(timeout: 10),
            "Receive chooser should show the Ecash option"
        )
    }

    // MARK: - Tests

    func testReceiveChooserCanBeDismissed() throws {
        waitForMainTab()

        openReceiveChooser()
        XCTAssertTrue(
            receiveBitcoinOption.waitForExistence(timeout: 5),
            "Receive chooser should show the Bitcoin option"
        )

        let closeButton = app.buttons["wallet-chooser-close"]
        tapWhenReady(closeButton, message: "Receive chooser should show a close button")

        XCTAssertTrue(tabButton("Wallet", timeout: 5).exists)
    }

    func testBitcoinOptionOpensLightningFlow() throws {
        waitForMainTab()

        openReceiveChooser()

        XCTAssertTrue(
            receiveBitcoinOption.waitForExistence(timeout: 10),
            "Receive chooser should show the Bitcoin option"
        )
        tapWhenReady(receiveBitcoinOption)

        XCTAssertTrue(
            screen("receive-lightning-screen").waitForExistence(timeout: 10),
            "Lightning receive view should open"
        )
    }
}
