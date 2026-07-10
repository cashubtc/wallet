import XCTest

/// Shared base for all CashuWallet UI tests.
///
/// Provides a pre-launched XCUIApplication and the common wallet-creation
/// helpers so individual test files don't duplicate setUp/tearDown or
/// the multi-step onboarding walk-through.
class UITestBase: XCTestCase {
    enum LaunchMode {
        case emptyWallet
        case seededWallet
        case seededWalletWithMint
    }

    var app: XCUIApplication!
    var mintURL: String {
        ProcessInfo.processInfo.environment["NUTSHELL_MINT_URL"] ?? "http://localhost:3338"
    }
    var cdkMintURL: String {
        ProcessInfo.processInfo.environment["CDK_MINT_URL"] ?? "http://localhost:3339"
    }
    var launchMode: LaunchMode { .emptyWallet }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment = launchEnvironment(for: launchMode)
        app.launch()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = nil
    }

    private func launchEnvironment(for mode: LaunchMode) -> [String: String] {
        var environment = [
            "CI_INTEGRATION_TEST": "1",
            "RESET_WALLET": "1",
            "NUTSHELL_MINT_URL": mintURL,
            "CDK_MINT_URL": cdkMintURL,
        ]

        switch mode {
        case .emptyWallet:
            break
        case .seededWallet:
            environment["UITEST_SEED_WALLET"] = "1"
        case .seededWalletWithMint:
            environment["UITEST_SEED_WALLET"] = "1"
            environment["UITEST_SEED_MINT"] = "1"
            environment["UITEST_SEED_MINT_URL"] = mintURL
        }

        return environment
    }

    // MARK: - Onboarding helpers

    /// Walk through: welcome → create wallet → acknowledge seed → saved seed.
    /// Leaves the app on the "Pick your first mint" screen.
    func createWalletThroughSeed() {
        let create = app.buttons["onboarding-create-wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 30))
        create.tap()

        let ack = app.buttons["onboarding-ack-seed"]
        XCTAssertTrue(ack.waitForExistence(timeout: 15))
        ack.tap()

        let saved = app.buttons["onboarding-saved-seed"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()
    }

    /// Full onboarding: create wallet, skip mint setup, wait for main tab bar.
    func createWalletAndSkipMint() {
        createWalletThroughSeed()

        let skip = app.buttons["onboarding-skip-mint"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10))
        skip.tap()

        waitForMainTab()
    }

    /// Full onboarding: create wallet, add live mint, wait for main tab bar.
    func createWalletWithMint(at mintURL: String? = nil) {
        let mintURL = mintURL ?? self.mintURL
        createWalletThroughSeed()

        let addCustom = app.buttons["onboarding-add-custom-mint"]
        XCTAssertTrue(addCustom.waitForExistence(timeout: 10))
        addCustom.tap()

        let field = app.textFields["onboarding-custom-mint-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(mintURL)
        let done = app.keyboards.buttons["Done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5), "URL keyboard should expose a Done button")
        done.tap()

        let cont = app.buttons["onboarding-continue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 5))

        XCTAssertTrue(
            cont.waitUntilEnabledAndHittable(timeout: 10),
            "Continue should become tappable after adding a custom mint"
        )
        cont.tap()

        waitForMainTab(timeout: 60)
    }

    func waitForMainTab(timeout: TimeInterval = 20) {
        XCTAssertTrue(
            tabButton("Wallet", timeout: timeout).exists,
            "Main wallet tab bar should appear"
        )
    }

    @discardableResult
    func mainTabBar(
        timeout: TimeInterval = 20,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement {
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(
            tabBar.waitForExistence(timeout: timeout),
            "Main tab bar should appear",
            file: file,
            line: line
        )
        return tabBar
    }

    @discardableResult
    func tabButton(
        _ title: String,
        timeout: TimeInterval = 20,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement {
        let button = mainTabBar(timeout: timeout, file: file, line: line).buttons[title].firstMatch
        XCTAssertTrue(
            button.waitForExistence(timeout: timeout),
            "\(title) tab should appear",
            file: file,
            line: line
        )
        return button
    }

    func waitForSelectedTab(
        _ title: String,
        timeout: TimeInterval = 5,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let button = tabButton(title, timeout: timeout, file: file, line: line)
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: button
        )
        let result = XCTWaiter.wait(for: [selected], timeout: timeout)
        XCTAssertEqual(
            result,
            .completed,
            "\(title) tab should become selected",
            file: file,
            line: line
        )
    }

    func tapTab(
        _ title: String,
        timeout: TimeInterval = 5,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let button = tabButton(title, timeout: timeout, file: file, line: line)
        button.tap()
        waitForSelectedTab(title, timeout: timeout, file: file, line: line)
    }
}

private extension XCUIElement {
    func waitUntilEnabledAndHittable(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)

        while Date() < deadline {
            if exists && isEnabled && isHittable {
                return true
            }

            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
        }

        return exists && isEnabled && isHittable
    }
}
