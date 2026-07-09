import XCTest
@testable import CashuWallet

@MainActor
final class MintServiceTests: XCTestCase {
    private var service: MintService!

    override func setUp() {
        super.setUp()
        service = MintService(
            walletRepository: { nil },
            walletStore: WalletStore(storage: InMemoryStorage(), secureStorage: InMemorySecureStorage())
        )
    }

    // MARK: - validateMintUrl

    func testValidHttpsUrlAccepted() {
        XCTAssertNil(service.validateMintUrl("https://mint.example.com"))
    }

    func testValidHttpLocalhostAccepted() {
        XCTAssertNil(service.validateMintUrl("http://localhost:3338"))
    }

    func testTrailingSlashNormalizationBeforeValidation() {
        XCTAssertNil(service.validateMintUrl("https://mint.example.com/"))
    }

    func testMissingHostReturnsError() {
        XCTAssertNotNil(service.validateMintUrl("not-a-url-at-all"))
    }

    func testFtpSchemeReturnsError() {
        XCTAssertNotNil(service.validateMintUrl("ftp://mint.example.com"))
    }

    func testCustomSchemeReturnsError() {
        // A syntactically valid but non-http(s) scheme must be rejected.
        XCTAssertNotNil(service.validateMintUrl("cashu://mint.example.com"))
    }

    // MARK: - validateMintUrl — host validation (isValidMintHost)

    func testSingleLabelHostRejected() {
        // No dot, not localhost, not an IP — not a usable mint host.
        XCTAssertNotNil(service.validateMintUrl("https://localmint"))
    }

    func testLocalhostWithoutPortAccepted() {
        XCTAssertNil(service.validateMintUrl("http://localhost"))
    }

    func testIPv4HostAccepted() {
        XCTAssertNil(service.validateMintUrl("http://192.168.1.50"))
    }

    func testIPv4HostWithPortAccepted() {
        XCTAssertNil(service.validateMintUrl("http://127.0.0.1:3338"))
    }

    func testDottedHostWithPortAccepted() {
        XCTAssertNil(service.validateMintUrl("https://mint.example.com:443"))
    }

    // MARK: - isMintTracked

    func testIsMintTrackedFalseWhenEmpty() {
        XCTAssertFalse(service.isMintTracked(url: "https://mint.example.com"))
    }

    func testIsMintTrackedTrueAfterLoad() {
        let storage = InMemoryStorage()
        let ws = WalletStore(storage: storage, secureStorage: InMemorySecureStorage())
        ws.saveMints([mint("https://mint.example.com", name: "Test")])

        let s = MintService(walletRepository: { nil }, walletStore: ws)
        s.loadCachedMints()
        XCTAssertTrue(s.isMintTracked(url: "https://mint.example.com"))
    }

    func testIsMintTrackedNormalizesTrailingSlash() {
        let storage = InMemoryStorage()
        let ws = WalletStore(storage: storage, secureStorage: InMemorySecureStorage())
        ws.saveMints([mint("https://mint.example.com", name: "Test")])

        let s = MintService(walletRepository: { nil }, walletStore: ws)
        s.loadCachedMints()
        XCTAssertTrue(s.isMintTracked(url: "https://mint.example.com/"))
    }

    // MARK: - loadCachedMints / activeMint

    func testLoadCachedMintsSetsFirstAsActive() {
        let storage = InMemoryStorage()
        let ws = WalletStore(storage: storage, secureStorage: InMemorySecureStorage())
        let m = mint("https://mint.example.com", name: "First")
        ws.saveMints([m])
        ws.activeMintURL = m.url

        let s = MintService(walletRepository: { nil }, walletStore: ws)
        s.loadCachedMints()
        XCTAssertEqual(s.activeMint?.url, "https://mint.example.com")
    }

    func testLoadCachedMintsFallsBackToFirstWhenNoActiveSaved() {
        let storage = InMemoryStorage()
        let ws = WalletStore(storage: storage, secureStorage: InMemorySecureStorage())
        ws.saveMints([
            mint("https://mint1.example.com", name: "Mint 1"),
            mint("https://mint2.example.com", name: "Mint 2"),
        ])

        let s = MintService(walletRepository: { nil }, walletStore: ws)
        s.loadCachedMints()
        XCTAssertEqual(s.activeMint?.url, "https://mint1.example.com")
    }

    // MARK: - updateMintBalances

    func testUpdateMintBalanceUpdatesMatchingURL() {
        service.mints = [mint("https://mint.example.com", name: "X")]
        service.updateMintBalance(url: "https://mint.example.com", balance: 100)
        XCTAssertEqual(service.mints[0].balance, 100)
    }

    func testUpdateMintBalanceIgnoresUnknownURL() {
        service.mints = [mint("https://mint.example.com", name: "X")]
        service.updateMintBalance(url: "https://other.example.com", balance: 999)
        XCTAssertEqual(service.mints[0].balance, 0)
    }

    func testUpdateMintBalanceNormalizesTrailingSlash() {
        service.mints = [mint("https://mint.example.com", name: "X")]
        service.updateMintBalance(url: "https://mint.example.com/", balance: 42)
        XCTAssertEqual(service.mints[0].balance, 42)
    }

    func testUpdateMintBalancesUpdatesActiveMintBalance() {
        let m = mint("https://mint.example.com", name: "Active")
        service.mints = [m]
        service.activeMint = m
        service.updateMintBalance(url: "https://mint.example.com", balance: 77)
        XCTAssertEqual(service.activeMint?.balance, 77)
    }

    func testUpdateMintBalancesNoOpWhenUnchanged() {
        var m = mint("https://mint.example.com", name: "X")
        m.balance = 50
        service.mints = [m]
        let before = service.mints[0].balance
        service.updateMintBalance(url: "https://mint.example.com", balance: 50)
        XCTAssertEqual(service.mints[0].balance, before)
    }

    func testUpdateMultipleBalancesInOneCall() {
        service.mints = [
            mint("https://mint1.example.com", name: "A"),
            mint("https://mint2.example.com", name: "B"),
        ]
        service.updateMintBalances([
            "https://mint1.example.com": 10,
            "https://mint2.example.com": 20,
        ])
        XCTAssertEqual(service.mints[0].balance, 10)
        XCTAssertEqual(service.mints[1].balance, 20)
    }

    // MARK: - saveMints / persistence

    func testSaveMintsPersistsToStore() {
        let storage = InMemoryStorage()
        let ws = WalletStore(storage: storage, secureStorage: InMemorySecureStorage())
        let s = MintService(walletRepository: { nil }, walletStore: ws)
        s.mints = [mint("https://mint.example.com", name: "Saved")]
        s.saveMints()

        let s2 = MintService(walletRepository: { nil }, walletStore: ws)
        s2.loadCachedMints()
        XCTAssertEqual(s2.mints.count, 1)
        XCTAssertEqual(s2.mints[0].name, "Saved")
    }

    // MARK: - Helpers

    private func mint(_ url: String, name: String) -> MintInfo {
        MintInfo(url: url, name: name, description: nil, isActive: true, balance: 0)
    }
}

/// Multi-unit support: mint unit discovery/selection, unit string ↔ CurrencyUnit
/// mapping, and unit-native amount entry.
final class MultiUnitSupportTests: XCTestCase {
    private func mint(units: [String], mintUnits: [String] = ["sat"]) -> MintInfo {
        MintInfo(url: "https://mint.example", name: "Mint", description: nil,
                 isActive: true, balance: 0, iconUrl: nil, units: units, mintUnits: mintUnits)
    }

    // MARK: - MintInfo unit helpers

    func testSingleUnitMintHidesSelector() {
        XCTAssertFalse(mint(units: ["sat"]).supportsMultipleUnits)
    }

    func testMultiUnitMintShowsSelector() {
        XCTAssertTrue(mint(units: ["sat", "eur"]).supportsMultipleUnits)
    }

    func testDefaultUnitPrefersSat() {
        XCTAssertEqual(mint(units: ["eur", "sat", "usd"]).defaultUnit, "sat")
    }

    func testDefaultUnitFallsBackToFirstSorted() {
        XCTAssertEqual(mint(units: ["usd", "eur"]).defaultUnit, "eur")
    }

    func testDefaultUnitEmptyIsSat() {
        XCTAssertEqual(mint(units: []).defaultUnit, "sat")
    }

    func testResolvedUnitKeepsSupported() {
        XCTAssertEqual(mint(units: ["sat", "eur"]).resolvedUnit("eur"), "eur")
    }

    func testResolvedUnitResetsUnsupported() {
        // usd isn't supported → falls back to the mint's default (sat).
        XCTAssertEqual(mint(units: ["sat", "eur"]).resolvedUnit("usd"), "sat")
    }

    // MARK: - MintInfo mintable-unit helpers (Receive/mint selector)

    func testSingleMintUnitHidesReceiveSelector() {
        // A mint that MELTS eur but only MINTS sat must not offer eur for minting.
        XCTAssertFalse(mint(units: ["sat", "eur"], mintUnits: ["sat"]).supportsMultipleMintUnits)
    }

    func testMultiMintUnitShowsReceiveSelector() {
        XCTAssertTrue(mint(units: ["sat", "eur"], mintUnits: ["sat", "eur"]).supportsMultipleMintUnits)
    }

    func testDefaultMintUnitPrefersSat() {
        XCTAssertEqual(mint(units: ["sat", "eur"], mintUnits: ["eur", "sat"]).defaultMintUnit, "sat")
    }

    func testDefaultMintUnitFallsBackToFirstSorted() {
        XCTAssertEqual(mint(units: ["usd", "eur"], mintUnits: ["usd", "eur"]).defaultMintUnit, "eur")
    }

    func testResolvedMintUnitKeepsMintable() {
        XCTAssertEqual(mint(units: ["sat", "eur"], mintUnits: ["sat", "eur"]).resolvedMintUnit("eur"), "eur")
    }

    func testResolvedMintUnitResetsNonMintable() {
        // eur is meltable but not mintable → falls back to the default mint unit.
        XCTAssertEqual(mint(units: ["sat", "eur"], mintUnits: ["sat"]).resolvedMintUnit("eur"), "sat")
    }

    // MARK: - Home balance pager ordering

    func testHomeBalanceSatOnly() {
        XCTAssertEqual(HomeBalance.homeBalanceUnits(["sat": 1000]), ["sat"])
    }

    func testHomeBalanceEmptyIsSat() {
        XCTAssertEqual(HomeBalance.homeBalanceUnits([:]), ["sat"])
    }

    func testHomeBalanceIncludesHeldNonSatSorted() {
        XCTAssertEqual(
            HomeBalance.homeBalanceUnits(["sat": 1000, "usd": 500, "eur": 200]),
            ["sat", "eur", "usd"]
        )
    }

    func testHomeBalanceExcludesZeroNonSat() {
        // A unit the mint lists but the user doesn't hold gets no page.
        XCTAssertEqual(
            HomeBalance.homeBalanceUnits(["sat": 1000, "eur": 0, "usd": 300]),
            ["sat", "usd"]
        )
    }

    func testHomeBalanceAllZeroNonSatIsSat() {
        XCTAssertEqual(HomeBalance.homeBalanceUnits(["sat": 0, "eur": 0]), ["sat"])
    }

    func testResolvedHomeUnitKeepsAvailable() {
        XCTAssertEqual(HomeBalance.resolvedUnit("eur", in: ["sat", "eur"]), "eur")
    }

    func testResolvedHomeUnitFallsBackToSat() {
        // Stored unit dropped to zero balance and left the pager → back to sat.
        XCTAssertEqual(HomeBalance.resolvedUnit("eur", in: ["sat"]), "sat")
    }

    // MARK: - Pager gate (active/default mint)

    func testShowsPagerWhenMultiUnitDefaultAndNonSatHeld() {
        XCTAssertTrue(HomeBalance.showsUnitPager(
            activeMintSupportsMultipleUnits: true,
            balancesByUnit: ["sat": 100, "eur": 5]
        ))
    }

    func testNoPagerWhenDefaultMintIsSingleUnit() {
        // Non-sat balance held elsewhere, but the default mint is single-unit.
        XCTAssertFalse(HomeBalance.showsUnitPager(
            activeMintSupportsMultipleUnits: false,
            balancesByUnit: ["sat": 100, "eur": 5]
        ))
    }

    func testNoPagerWhenNoNonSatBalance() {
        XCTAssertFalse(HomeBalance.showsUnitPager(
            activeMintSupportsMultipleUnits: true,
            balancesByUnit: ["sat": 100]
        ))
    }

    func testNoPagerWhenNonSatBalanceIsZero() {
        XCTAssertFalse(HomeBalance.showsUnitPager(
            activeMintSupportsMultipleUnits: true,
            balancesByUnit: ["sat": 100, "eur": 0]
        ))
    }

    func testResolvedUnitNilUsesDefault() {
        XCTAssertEqual(mint(units: ["eur", "usd"]).resolvedUnit(nil), "eur")
    }

    // MARK: - Unit string ↔ CurrencyUnit round-trip

    func testCurrencyUnitRoundTripsKnownUnits() {
        for unit in ["sat", "msat", "usd", "eur", "auth"] {
            let roundTripped = PaymentRequestDecoder.unitDescription(
                PaymentRequestDecoder.currencyUnit(from: unit)
            )
            XCTAssertEqual(roundTripped, unit)
        }
    }

    func testCurrencyUnitPreservesCustomUnit() {
        let roundTripped = PaymentRequestDecoder.unitDescription(
            PaymentRequestDecoder.currencyUnit(from: "hour")
        )
        XCTAssertEqual(roundTripped, "hour")
    }

    // MARK: - Currency lookup is never nil (arbitrary units supported)

    func testCurrencyForKnownUnits() {
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "sat").decimals, 0)
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "eur").decimals, 2)
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "usd").decimals, 2)
    }

    func testCurrencyForCustomUnitFallsBack() {
        let currency = CurrencyRegistry.currency(forMintUnit: "hour")
        XCTAssertEqual(currency.decimals, 0)
        XCTAssertEqual(currency.code, "HOUR")
    }

    // MARK: - Unit-native amount entry

    func testEntryBaseUnitsTwoDecimals() {
        XCTAssertEqual(AmountFormatter.entryBaseUnits(raw: "5.00", decimals: 2), 500)
        XCTAssertEqual(AmountFormatter.entryBaseUnits(raw: "14.54", decimals: 2), 1454)
    }

    func testEntryBaseUnitsInteger() {
        XCTAssertEqual(AmountFormatter.entryBaseUnits(raw: "500", decimals: 0), 500)
    }

    func testCentsAccumulatorBuildsUpFromRight() {
        // Digits shift in from the right: 5 → 0.05 → 0.50 → 5.00 (= 500 cents).
        var raw = ""
        for key in ["5", "0", "0"] {
            raw = AmountFormatter.entryAppendUnit(key, to: raw, decimals: 2)
        }
        XCTAssertEqual(AmountFormatter.entryBaseUnits(raw: raw, decimals: 2), 500)
    }

    func testIntegerAppendCollapsesLeadingZero() {
        XCTAssertEqual(AmountFormatter.entryAppendUnit("5", to: "0", decimals: 0), "5")
    }

    func testBackspaceUnitShiftsCentsRight() {
        var raw = ""
        for key in ["5", "0", "0"] {   // 5.00
            raw = AmountFormatter.entryAppendUnit(key, to: raw, decimals: 2)
        }
        raw = AmountFormatter.entryBackspaceUnit(raw, decimals: 2)   // → 0.50
        XCTAssertEqual(AmountFormatter.entryBaseUnits(raw: raw, decimals: 2), 50)
    }

    func testEntryStringRoundTrips() {
        XCTAssertEqual(
            AmountFormatter.entryBaseUnits(
                raw: AmountFormatter.entryString(baseUnits: 500, decimals: 2),
                decimals: 2
            ),
            500
        )
        XCTAssertEqual(AmountFormatter.entryString(baseUnits: 500, decimals: 0), "500")
        XCTAssertEqual(AmountFormatter.entryString(baseUnits: 0, decimals: 2), "")
    }
}
