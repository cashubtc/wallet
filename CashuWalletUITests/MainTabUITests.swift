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

        // History view should appear — at minimum the tab should be selected
        let historyTab = tabButton("History")
        XCTAssertTrue(historyTab.isSelected, "History tab should become selected")
    }

    func testNavigateToMintsTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Mints")

        let mintsTab = tabButton("Mints")
        XCTAssertTrue(mintsTab.isSelected)
    }

    /// With no mint configured, the Mints tab shows its add-mint form.
    func testMintsTabShowsAddMintWithoutMint() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Mints")
        XCTAssertTrue(tabButton("Mints").isSelected)

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

        let settingsTab = tabButton("Settings")
        XCTAssertTrue(settingsTab.isSelected)
    }

    func testWalletTabIsDefaultSelected() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        let walletTab = tabButton("Wallet")
        XCTAssertTrue(walletTab.isSelected, "Wallet should be selected by default")
    }

    func testNavigateBetweenMultipleTabs() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        tapTab("Mints")
        XCTAssertTrue(tabButton("Mints").isSelected)

        tapTab("Settings")
        XCTAssertTrue(tabButton("Settings").isSelected)

        tapTab("Wallet")
        XCTAssertTrue(tabButton("Wallet").isSelected)
    }
}
