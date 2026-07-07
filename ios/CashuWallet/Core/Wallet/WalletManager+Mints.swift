import Foundation
import Cdk

extension WalletManager {
    // MARK: - Mint Operations (Delegate to MintService)

    func addMint(url: String) async throws {
        try await mintService.addMint(url: url)
        await refreshBalance()
        performICloudBackup()
        Task { await NostrMintBackupService.shared.backupCurrentMintsIfEnabled() }
        SentryService.breadcrumb("Mint added", category: "wallet.mint")
    }

    func removeMint(at offsets: IndexSet) async {
        await mintService.removeMint(at: offsets)
        await refreshBalance()
        performICloudBackup()
        Task { await NostrMintBackupService.shared.backupCurrentMintsIfEnabled() }
        SentryService.breadcrumb("Mint removed", category: "wallet.mint")
    }

    func setActiveMint(_ mint: MintInfo) async throws {
        try await mintService.setActiveMint(mint)
        await refreshBalance()
    }

    /// Whether the given mint URL is already tracked by the wallet.
    func isMintKnown(url: String) -> Bool {
        mintService.isMintTracked(url: url)
    }


    func refreshMintInfo() async {
        await mintService.refreshMintInfo()
    }

    /// Fetch full mint info from the mint's API via CashuDevKit
    func fetchFullMintInfo(mintUrl: String) async throws -> Cdk.MintInfo? {
        guard let walletRepository = walletRepository else {
            throw WalletError.notInitialized
        }
        let mintUrlObj = MintUrl(url: mintUrl)
        let wallet = try await walletRepository.getWallet(mintUrl: mintUrlObj, unit: .sat)
        return try await wallet.fetchMintInfo()
    }

    /// Best-effort preview of a mint's identity (name + icon), fetched through
    /// CashuDevKit. CDK requires a wallet entry before `fetchMintInfo()`, so this
    /// may prepare the mint in the CDK repository, but it does not add the mint
    /// to the app's saved mint list.
    func fetchMintPreviewInfo(url: String) async -> (name: String?, iconUrl: String?)? {
        guard let walletRepository else {
            return nil
        }

        let normalized = normalizePreviewMintUrl(url)
        let mintUrl = MintUrl(url: normalized)
        do {
            if await !walletRepository.hasMint(mintUrl: mintUrl) {
                try await walletRepository.createWallet(mintUrl: mintUrl, unit: .sat, targetProofCount: nil)
            }
            let wallet = try await walletRepository.getWallet(mintUrl: mintUrl, unit: .sat)
            let info = try await wallet.fetchMintInfo()
            return (name: info?.name, iconUrl: info?.iconUrl)
        } catch {
            AppLogger.wallet.error("Failed to fetch CDK mint preview for \(normalized): \(error)")
            return nil
        }
    }

    private func normalizePreviewMintUrl(_ url: String) -> String {
        var normalized = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if !normalized.contains("://") {
            normalized = "https://" + normalized
        }
        return normalized.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    // MARK: - Balance Operations
    func refreshBalance() async {
        guard let walletRepository = walletRepository else { return }
        let mintUrls = trackedMintUrlsForWalletAccess()
        
        guard !mintUrls.isEmpty else {
            balance = 0
            balancesByUnit = [:]
            return
        }

        var total: UInt64 = 0
        var balancesByMintURL: [String: UInt64] = [:]
        // Per-unit totals across all mints (sat plus any held eur/usd/custom).
        var unitTotals: [String: UInt64] = [:]

        for mintUrlString in mintUrls {
            let mintUrl = MintUrl(url: mintUrlString)
            do {
                let wallet = try await walletRepository.getWallet(mintUrl: mintUrl, unit: .sat)
                let walletBalance = try await wallet.totalBalance()

                total += walletBalance.value
                balancesByMintURL[mintUrlString] = walletBalance.value
                unitTotals["sat", default: 0] += walletBalance.value
            } catch {
                balancesByMintURL[mintUrlString] = 0
                AppLogger.wallet.error("Failed to refresh balance for mint \(mintUrlString): \(error)")
            }

            // Add this mint's non-sat unit balances. Only the units it advertises
            // are queried; a never-used unit wallet throws getWallet → skipped as
            // zero (no createWallet — the sat wallet above is reused for sat).
            let nonSatUnits = mintService.mints
                .first(where: { $0.url == mintUrlString })?
                .units.filter { $0.lowercased() != "sat" } ?? []
            for unit in nonSatUnits {
                let currencyUnit = PaymentRequestDecoder.currencyUnit(from: unit)
                if let unitWallet = try? await walletRepository.getWallet(mintUrl: mintUrl, unit: currencyUnit),
                   let unitBalance = try? await unitWallet.totalBalance() {
                    unitTotals[unit, default: 0] += unitBalance.value
                }
            }
        }

        mintService.updateMintBalances(balancesByMintURL)
        balance = total
        balancesByUnit = unitTotals
    }
}
