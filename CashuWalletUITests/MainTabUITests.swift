import XCTest

/// UI tests verifying tab-bar navigation after wallet creation.
final class MainTabUITests: UITestBase {

    // MARK: - Tests

    func testAllTabsExist() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        XCTAssertTrue(tabButton("Wallet").exists)
        XCTAssertTrue(tabButton("History").exists)
        XCTAssertTrue(tabButton("Mints").exists)
        XCTAssertTrue(tabButton("Settings").exists)
    }

    func testNavigateToHistoryTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("History")

        XCTAssertTrue(
            app.navigationBars["History"].waitForExistence(timeout: 10),
            "History view should appear"
        )
    }

    func testNavigateToMintsTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Mints")
    }

    /// With no mint configured, the Mints tab shows its add-mint form.
    func testMintsTabShowsAddMintWithoutMint() throws {
        createWalletAndSkipMint()
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

    func testNavigateToSettingsTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Settings")
    }

    func testWalletTabIsDefaultSelected() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        waitForSelectedTab("Wallet")
    }

    func testNavigateBetweenMultipleTabs() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Mints")

        tapTab("Settings")

        tapTab("Wallet")
    }
}
