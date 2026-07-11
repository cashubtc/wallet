import XCTest

/// UI tests verifying tab-bar navigation after wallet creation.
final class MainTabUITests: UITestBase {
    override var launchMode: LaunchMode { .seededWallet }

    // MARK: - Tests

    func testAllTabsExist() throws {
        waitForMainTab()

        XCTAssertTrue(tabButton("Wallet").exists)
        XCTAssertTrue(tabButton("History").exists)
        XCTAssertTrue(tabButton("Mints").exists)
        XCTAssertEqual(mainTabBar().buttons.count, 3)
    }

    func testNavigateToHistoryTab() throws {
        waitForMainTab()

        tapTab("History")

        XCTAssertTrue(
            app.navigationBars["History"].waitForExistence(timeout: 10),
            "History view should appear"
        )
    }

    func testNavigateToMintsTab() throws {
        waitForMainTab()

        tapTab("Mints")
    }

    /// With no mint configured, the Mints tab shows its add-mint form.
    func testMintsTabShowsAddMintWithoutMint() throws {
        waitForMainTab()

        tapTab("Mints")

        XCTAssertTrue(
            app.navigationBars["Mints"].waitForExistence(timeout: 10),
            "Mints navigation bar should appear"
        )
        XCTAssertTrue(
            app.buttons["mints-add-button"].waitForExistence(timeout: 5),
            "Mints tab should show the Add Mint button when no mint is configured"
        )
    }

    func testOpenSettingsFromWallet() throws {
        waitForMainTab()

        let settings = app.buttons["wallet-settings-button"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
    }

    func testWalletTabIsDefaultSelected() throws {
        waitForMainTab()

        waitForSelectedTab("Wallet")
    }

    func testNavigateBetweenMultipleTabs() throws {
        waitForMainTab()

        tapTab("Mints")

        tapTab("Wallet")

        tapTab("History")
    }
}
