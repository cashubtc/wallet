import XCTest

/// UI tests for the Settings tab navigation and basic interactions.
final class SettingsUITests: UITestBase {

    // MARK: - Helpers

    private func navigateToSettings() {
        createWalletAndSkipMint()
        tapTab("Settings")
    }

    // MARK: - Tests

    func testSettingsViewLoads() throws {
        navigateToSettings()

        // Assert the real Settings screen rendered: its nav bar plus a known row.
        XCTAssertTrue(
            app.navigationBars["Settings"].waitForExistence(timeout: 10),
            "Settings navigation bar should appear"
        )
        XCTAssertTrue(
            app.buttons["Delete Wallet"].waitForExistence(timeout: 5),
            "Settings content should render (Delete Wallet row visible)"
        )
    }

    func testSettingsTabIsAccessible() throws {
        navigateToSettings()

        XCTAssertTrue(mainTabBar(timeout: 5).exists)
        XCTAssertTrue(tabButton("Settings").isSelected)
    }

    func testCanReturnToWalletFromSettings() throws {
        navigateToSettings()

        tapTab("Wallet")
        XCTAssertTrue(tabButton("Wallet").isSelected)
    }
}
