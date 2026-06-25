import XCTest

/// UI tests for the Settings tab navigation and basic interactions.
final class SettingsUITests: XCTestCase {

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

    private func createWalletAndNavigateToSettings() {
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

        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.tabBars.buttons["Settings"].isSelected)
    }

    // MARK: - Tests

    func testSettingsViewLoads() throws {
        createWalletAndNavigateToSettings()

        // Settings view should show content — look for any known section label
        let settingsContent = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Display' OR label CONTAINS 'Balance' OR label CONTAINS 'Wallet'")).firstMatch
        XCTAssertTrue(settingsContent.waitForExistence(timeout: 10), "Settings content should be visible")
    }

    func testSettingsTabIsAccessible() throws {
        createWalletAndNavigateToSettings()

        // Tab bar should remain accessible after navigating to settings
        XCTAssertTrue(app.tabBars.firstMatch.exists)
        XCTAssertTrue(app.tabBars.buttons["Settings"].isSelected)
    }

    func testCanReturnToWalletFromSettings() throws {
        createWalletAndNavigateToSettings()

        app.tabBars.buttons["Wallet"].tap()
        XCTAssertTrue(app.tabBars.buttons["Wallet"].isSelected)
    }

    func testMintsTabShowsEmptyStateWithoutMint() throws {
        createWalletAndNavigateToSettings()

        app.tabBars.buttons["Mints"].tap()
        XCTAssertTrue(app.tabBars.buttons["Mints"].isSelected)

        // With no mints added (skipped mint setup), mints list should be accessible
        // and either show empty state or an "Add mint" button
        let addMintButton = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Add' OR label CONTAINS 'mint'")).firstMatch
        let navigationTitle = app.navigationBars.staticTexts.firstMatch
        // Either an add button or nav title should be visible
        let contentExists = addMintButton.waitForExistence(timeout: 5) || navigationTitle.waitForExistence(timeout: 5)
        XCTAssertTrue(contentExists, "Mints tab should show some content")
    }
}
