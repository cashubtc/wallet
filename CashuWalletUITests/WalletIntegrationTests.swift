import XCTest

/// UI integration tests driving the real onboarding flow end-to-end.
///
/// Each test launches the app with `RESET_WALLET=1`, which makes `WalletManager`
/// wipe any persisted wallet on startup so onboarding always begins from a
/// known-empty state (see `IntegrationTestConfig` / `WalletManager.initialize`).
///
/// The mint-add tests connect to the live Nutshell and CDK mints started by CI.
final class WalletIntegrationTests: UITestBase {

    // MARK: - Tests

    /// Create a wallet and skip mint setup — should land on the main tab bar.
    func testOnboardingCreateWalletAndSkipMint() throws {
        createWalletThroughSeed()

        let skip = app.buttons["onboarding-skip-mint"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10), "First-mint step should appear")
        skip.tap()

        waitForMainTab()
    }

    /// Create a wallet and connect the live Nutshell mint via a custom URL.
    func testOnboardingAddNutshellMint() throws {
        assertCanAddMint(at: mintURL)
    }

    /// Create a wallet and connect the live CDK mint via a custom URL.
    func testOnboardingAddCDKMint() throws {
        assertCanAddMint(at: cdkMintURL)
    }

    private func assertCanAddMint(at url: String) {
        createWalletWithMint(at: url)

        // The added mint should be listed on the Mints tab.
        tapTab("Mints")
        let mintRow = app.staticTexts[url]
        XCTAssertTrue(mintRow.waitForExistence(timeout: 10), "Added mint should appear in the Mints list")
    }
}
