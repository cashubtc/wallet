import XCTest

/// UI tests verifying tab-bar navigation after wallet creation.
final class MainTabUITests: UITestBase {
    override var launchMode: LaunchMode { .seededWallet }

    // MARK: - Tests

    func testPrimaryNavigationAndEmptyMintsState() throws {
        waitForMainTab()

        let tabBar = mainTabBar()
        XCTAssertEqual(tabBar.buttons.count, 3)
        XCTAssertTrue(tabButton("History").exists)
        XCTAssertTrue(tabButton("Mints").exists)
        waitForSelectedTab("Wallet")

        tapTab("History")
        XCTAssertTrue(
            screen("history-screen").waitForExistence(timeout: 10),
            "History view should appear"
        )

        tapTab("Mints")
        XCTAssertTrue(
            screen("mints-screen").waitForExistence(timeout: 10),
            "Mints view should appear"
        )
        XCTAssertTrue(
            app.buttons["mints-add-button"].waitForExistence(timeout: 5),
            "Mints tab should show the Add Mint button when no mint is configured"
        )

        tapTab("Wallet")
    }
}
