import XCTest

/// UI tests for wallet-header Settings navigation and basic interactions.
final class SettingsUITests: UITestBase {
    override var launchMode: LaunchMode { .seededWallet }

    // MARK: - Helpers

    private func navigateToSettings() {
        waitForMainTab()
        let settings = app.buttons["wallet-settings-button"]
        tapWhenReady(settings)
    }

    // MARK: - Tests

    func testSettingsRoundTrip() throws {
        navigateToSettings()

        XCTAssertTrue(
            screen("settings-screen").waitForExistence(timeout: 10),
            "Settings screen should appear"
        )
        XCTAssertTrue(
            app.buttons["Delete Wallet"].waitForExistence(timeout: 5),
            "Settings content should render (Delete Wallet row visible)"
        )
        let tabBar = mainTabBar(timeout: 5)
        XCTAssertEqual(tabBar.buttons.count, 3)
        XCTAssertFalse(tabBar.buttons["Settings"].exists)

        let back = app.buttons["settings-back-button"]
        tapWhenReady(back)
        XCTAssertTrue(app.buttons["wallet-settings-button"].waitForExistence(timeout: 5))
        waitForSelectedTab("Wallet")
    }
}
