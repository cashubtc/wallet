import XCTest

/// UI integration tests driving the real onboarding flow end-to-end.
///
/// Each test launches the app with `RESET_WALLET=1`, which makes `WalletManager`
/// wipe any persisted wallet on startup so onboarding always begins from a
/// known-empty state (see `IntegrationTestConfig` / `WalletManager.initialize`).
///
/// The mint-add smoke test connects to the live Nutshell mint started by CI.
/// Nutshell/CDK backend parity is covered by the faster mint integration suite.
final class WalletIntegrationTests: UITestBase {

    // MARK: - Tests

    /// Create a wallet and skip mint setup — should land on the main tab bar.
    func testOnboardingCreateWalletAndSkipMint() throws {
        createWalletThroughSeed()

        let skip = app.buttons["onboarding-skip-mint"]
        tapWhenReady(skip, timeout: 10, message: "First-mint step should appear")

        waitForMainTab()
    }

    /// Create a wallet and connect the live Nutshell mint via a custom URL.
    func testOnboardingAddNutshellMint() throws {
        assertCanAddMint(at: mintURL)
    }

    private func assertCanAddMint(at url: String) {
        createWalletWithMint(at: url)

        // The added mint should be listed on the Mints tab.
        tapTab("Mints")
        let mintRow = app.staticTexts[url]
        XCTAssertTrue(mintRow.waitForExistence(timeout: 10), "Added mint should appear in the Mints list")
    }
}
