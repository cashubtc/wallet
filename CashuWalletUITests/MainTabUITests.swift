import XCTest

/// UI tests verifying tab-bar navigation after wallet creation.
final class MainTabUITests: XCTestCase {

    private var app: XCUIApplication!

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
    }

    private func waitForMainTab(timeout: TimeInterval = 20) {
        let walletTab = app.tabBars.buttons["Wallet"]
        XCTAssertTrue(walletTab.waitForExistence(timeout: timeout))
    }

    // MARK: - Tests

    func testAllTabsExist() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.buttons["Wallet"].exists)
        XCTAssertTrue(tabBar.buttons["History"].exists)
        XCTAssertTrue(tabBar.buttons["Mints"].exists)
        XCTAssertTrue(tabBar.buttons["Settings"].exists)
    }

    func testNavigateToHistoryTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        app.tabBars.buttons["History"].tap()

        // History view should appear — at minimum the tab should be selected
        let historyTab = app.tabBars.buttons["History"]
        XCTAssertTrue(historyTab.isSelected, "History tab should become selected")
    }

    func testNavigateToMintsTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        app.tabBars.buttons["Mints"].tap()

        let mintsTab = app.tabBars.buttons["Mints"]
        XCTAssertTrue(mintsTab.isSelected)
    }

    func testNavigateToSettingsTab() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        app.tabBars.buttons["Settings"].tap()

        let settingsTab = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settingsTab.isSelected)
    }

    func testWalletTabIsDefaultSelected() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        let walletTab = app.tabBars.buttons["Wallet"]
        XCTAssertTrue(walletTab.isSelected, "Wallet should be selected by default")
    }

    func testNavigateBetweenMultipleTabs() throws {
        createWalletAndSkipMint()
        waitForMainTab()

        app.tabBars.buttons["Mints"].tap()
        XCTAssertTrue(app.tabBars.buttons["Mints"].isSelected)

        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.tabBars.buttons["Settings"].isSelected)

        app.tabBars.buttons["Wallet"].tap()
        XCTAssertTrue(app.tabBars.buttons["Wallet"].isSelected)
    }
}
