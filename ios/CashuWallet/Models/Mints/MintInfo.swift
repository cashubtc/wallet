import Foundation

struct MintInfo: Identifiable, Equatable, Codable {
    var id: String { url }
    let url: String
    var name: String
    var description: String?
    var isActive: Bool
    var balance: UInt64
    
    /// Icon URL (if available from mint info)
    var iconUrl: String?
    
    /// Supported units — the union of the mint's mintable and meltable units.
    var units: [String] = ["sat"]

    /// Units the mint can MINT (NUT-04), a subset of `units`. Drives the Receive
    /// unit selector so we never offer a melt-only unit for minting.
    var mintUnits: [String] = ["sat"]

    /// Supported NUT-04 payment methods for receiving
    var supportedMintMethods: [PaymentMethodKind] = [.bolt11]

    /// Supported NUT-05 payment methods for sending
    var supportedMeltMethods: [PaymentMethodKind] = [.bolt11]

    /// NUT-04 bolt12 `MintMethodSettings.description`. Default false so records
    /// persisted before this landed, and mints that omit the field, fail closed
    /// (the Description row stays hidden until a live fetch advertises true).
    var supportsBolt12MintDescription: Bool = false

    /// NUT-05 bolt11 `amountless` option for the sat unit: the mint can pay an
    /// invoice that leaves the amount to the payer. Nil until a live fetch has
    /// read it — records persisted before this landed stay eligible and let the
    /// mint decide, since mint info only refreshes from the Mints list.
    var supportsAmountlessBolt11Melt: Bool? = nil

    /// Required on-chain confirmations for minting, if advertised by the mint
    var onchainMintConfirmations: Int? = nil

    /// What the mint advertises for BOLT11 in sat. Nil until a live NUT-06
    /// fetch reports it, so records persisted before this landed stay unknown
    /// rather than reading as "unsupported".
    var bolt11Sat: Bolt11SatCapability? = nil

    /// Present when this entry is a CDK federation: several member mints that
    /// act as one mint with one balance. `url` is the federation's local mint
    /// URL, the one the CDK wallet is keyed by. Nil for a standalone mint.
    var federation: FederationMintMetadata? = nil

    /// Last updated timestamp
    var lastUpdated: Date = Date()
}

/// Public, non-secret description of a federation entry. The CDK wallet
/// repository stores the federation config itself and reactivates it on
/// launch; the app keeps only what it needs to display the federation and to
/// recognise any member URL as this entry.
struct FederationMintMetadata: Equatable, Codable {
    let federationId: String
    let memberMintUrls: [String]
    let memberCount: Int
    let signatureThreshold: Int

    init(federationId: String, memberMintUrls: [String], memberCount: Int, signatureThreshold: Int) {
        self.federationId = federationId
        self.memberMintUrls = memberMintUrls.map(MintURLIdentity.normalized)
        self.memberCount = memberCount
        self.signatureThreshold = signatureThreshold
    }

    /// "3 of 5": how many members must sign out of how many there are.
    var thresholdSummary: String { "\(signatureThreshold) of \(memberCount)" }

    /// The badge shown in the mint list and mint detail. Same copy on Android.
    var badgeText: String { "Federation · \(thresholdSummary)" }

    func hasMember(_ url: String) -> Bool {
        let identity = MintURLIdentity.normalized(url)
        return memberMintUrls.contains { MintURLIdentity.normalized($0) == identity }
    }
}

/// The (bolt11, sat) pair as advertised under NUT-04 and NUT-05 — the rail a
/// transfer between two held mints runs on. The method lists on `MintInfo`
/// cannot answer this: mint methods are not unit-filtered and neither list
/// carries the NUT's `disabled` flag or its amount limits.
struct Bolt11SatCapability: Equatable, Codable {
    /// NUT-04 lists (bolt11, sat) and minting is not disabled.
    var canMint: Bool
    /// NUT-05 lists (bolt11, sat) and melting is not disabled.
    var canMelt: Bool
    var mintMin: UInt64?
    var mintMax: UInt64?
    var meltMin: UInt64?
    var meltMax: UInt64?

    typealias Advertised = (method: PaymentMethodKind?, isSat: Bool, min: UInt64?, max: UInt64?)

    /// A disabled NUT turns the direction off even when the method is listed.
    /// Limits are kept either way so the capability records what was reported.
    static func reported(
        mint: [Advertised],
        mintingDisabled: Bool,
        melt: [Advertised],
        meltingDisabled: Bool
    ) -> Bolt11SatCapability {
        let mintMethod = mint.first { $0.method == .bolt11 && $0.isSat }
        let meltMethod = melt.first { $0.method == .bolt11 && $0.isSat }
        return Bolt11SatCapability(
            canMint: mintMethod != nil && !mintingDisabled,
            canMelt: meltMethod != nil && !meltingDisabled,
            mintMin: mintMethod?.min,
            mintMax: mintMethod?.max,
            meltMin: meltMethod?.min,
            meltMax: meltMethod?.max
        )
    }
}

/// Lightweight identity + capability preview fetched for discovery / staging
/// without adding the mint to the saved wallet list.
struct MintPreviewInfo {
    let name: String?
    let iconUrl: String?
    let methods: [PaymentMethodKind]
}

extension MintInfo {
    var isFederation: Bool { federation != nil }

    /// True when `mintURL` names this entry: its own URL or, for a federation,
    /// any member's URL. Tokens, payment requests and quotes can carry any
    /// member's URL, while CDK keys the wallet by the local URL only.
    func matches(mintURL: String) -> Bool {
        MintURLIdentity.normalized(url) == MintURLIdentity.normalized(mintURL)
            || federation?.hasMember(mintURL) == true
    }

    /// The tracked entry `url` names, by its own URL first so a standalone
    /// mint never loses to a federation that happens to list it.
    static func tracked(_ url: String, in mints: [MintInfo]) -> MintInfo? {
        let identity = MintURLIdentity.normalized(url)
        return mints.first { MintURLIdentity.normalized($0.url) == identity }
            ?? mints.first { $0.matches(mintURL: url) }
    }

    /// The URL the wallet keys `url` by: a federation member's URL maps to the
    /// federation entry's URL; anything else is returned normalised.
    static func canonicalURL(for url: String, in mints: [MintInfo]) -> String {
        tracked(url, in: mints)?.url ?? MintURLIdentity.normalized(url)
    }

    static func displayName(for url: String, in mints: [MintInfo]) -> String {
        let name = tracked(url, in: mints)?.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let name, !name.isEmpty { return name }
        return URL(string: url)?.host ?? MintURLIdentity.normalized(url)
    }

    /// True when the mint advertises more than one unit, so a unit chooser is
    /// worth surfacing. Single-unit mints hide the selector entirely.
    var supportsMultipleUnits: Bool { units.count > 1 }

    /// Preferred unit for this mint: "sat" when supported, otherwise the first
    /// unit alphabetically, falling back to "sat" for a malformed empty list.
    var defaultUnit: String {
        units.contains("sat") ? "sat" : (units.sorted().first ?? "sat")
    }

    /// Returns `unit` when this mint supports it, otherwise `defaultUnit`. Used
    /// to reset a stale selection when the active mint changes.
    func resolvedUnit(_ unit: String?) -> String {
        guard let unit, units.contains(unit) else { return defaultUnit }
        return unit
    }

    // MARK: - Mintable units (NUT-04)

    /// True when the mint can mint more than one unit — gates the Receive selector.
    var supportsMultipleMintUnits: Bool { mintUnits.count > 1 }

    /// Preferred mintable unit: "sat" when mintable, else the first sorted unit.
    var defaultMintUnit: String {
        mintUnits.contains("sat") ? "sat" : (mintUnits.sorted().first ?? "sat")
    }

    /// Returns `unit` when the mint can mint it, otherwise `defaultMintUnit`.
    func resolvedMintUnit(_ unit: String?) -> String {
        guard let unit, mintUnits.contains(unit) else { return defaultMintUnit }
        return unit
    }

    // MARK: - Melt capability (NUT-05)

    /// Whether this mint can pay a request of `method`. A BOLT11 invoice that
    /// leaves the amount to the payer also needs the mint's NUT-05 `amountless`
    /// option; BOLT12 offers are amountless by design and need nothing extra.
    func canMelt(_ method: PaymentMethodKind, amountless: Bool = false) -> Bool {
        guard supportedMeltMethods.contains(method) else { return false }
        return !(amountless && method == .bolt11) || supportsAmountlessBolt11Melt != false
    }

    /// True when a sat-unit NUT-05 bolt11 method advertises `amountless: true`.
    /// Null or false on every such method (or none at all) fails closed.
    static func reportsAmountlessBolt11Melt(
        methods: [(method: PaymentMethodKind?, isSatUnit: Bool, amountless: Bool?)]
    ) -> Bool {
        methods.contains { $0.method == .bolt11 && $0.isSatUnit && $0.amountless == true }
    }
}

extension MintInfo {
    private enum CodingKeys: String, CodingKey {
        case url
        case name
        case description
        case isActive
        case balance
        case iconUrl
        case units
        case mintUnits
        case supportedMintMethods
        case supportedMeltMethods
        case supportsBolt12MintDescription
        case supportsAmountlessBolt11Melt
        case onchainMintConfirmations
        case bolt11Sat
        case federation
        case lastUpdated
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        url = try container.decode(String.self, forKey: .url)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? "Unknown Mint"
        description = try container.decodeIfPresent(String.self, forKey: .description)
        isActive = try container.decodeIfPresent(Bool.self, forKey: .isActive) ?? true
        balance = try container.decodeIfPresent(UInt64.self, forKey: .balance) ?? 0
        iconUrl = try container.decodeIfPresent(String.self, forKey: .iconUrl)
        units = try container.decodeIfPresent([String].self, forKey: .units) ?? ["sat"]
        // Older records predate mintUnits — fall back to the full unit set so a
        // multi-unit mint keeps offering units until its next refresh repopulates.
        mintUnits = try container.decodeIfPresent([String].self, forKey: .mintUnits) ?? units
        supportedMintMethods = try container.decodeIfPresent([PaymentMethodKind].self, forKey: .supportedMintMethods) ?? [.bolt11]
        supportedMeltMethods = try container.decodeIfPresent([PaymentMethodKind].self, forKey: .supportedMeltMethods) ?? [.bolt11]
        supportsBolt12MintDescription = try container.decodeIfPresent(Bool.self, forKey: .supportsBolt12MintDescription) ?? false
        supportsAmountlessBolt11Melt = try container.decodeIfPresent(Bool.self, forKey: .supportsAmountlessBolt11Melt)
        onchainMintConfirmations = try container.decodeIfPresent(Int.self, forKey: .onchainMintConfirmations)
        bolt11Sat = try container.decodeIfPresent(Bolt11SatCapability.self, forKey: .bolt11Sat)
        federation = try container.decodeIfPresent(FederationMintMetadata.self, forKey: .federation)
        lastUpdated = try container.decodeIfPresent(Date.self, forKey: .lastUpdated) ?? Date()
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(url, forKey: .url)
        try container.encode(name, forKey: .name)
        try container.encodeIfPresent(description, forKey: .description)
        try container.encode(isActive, forKey: .isActive)
        try container.encode(balance, forKey: .balance)
        try container.encodeIfPresent(iconUrl, forKey: .iconUrl)
        try container.encode(units, forKey: .units)
        try container.encode(mintUnits, forKey: .mintUnits)
        try container.encode(supportedMintMethods, forKey: .supportedMintMethods)
        try container.encode(supportedMeltMethods, forKey: .supportedMeltMethods)
        try container.encode(supportsBolt12MintDescription, forKey: .supportsBolt12MintDescription)
        try container.encodeIfPresent(supportsAmountlessBolt11Melt, forKey: .supportsAmountlessBolt11Melt)
        try container.encodeIfPresent(onchainMintConfirmations, forKey: .onchainMintConfirmations)
        try container.encodeIfPresent(bolt11Sat, forKey: .bolt11Sat)
        try container.encodeIfPresent(federation, forKey: .federation)
        try container.encode(lastUpdated, forKey: .lastUpdated)
    }
}


/// Shared mint identity filtering and payment selection. Callers choose whether
/// an explicit preference is binding; NFC may fall back, while send confirms it.
enum MintSelection {
    static func matching(_ mints: [MintInfo], requestedURLs: [String]) -> [MintInfo] {
        guard !requestedURLs.isEmpty else { return mints }
        return mints.filter { mint in requestedURLs.contains { mint.matches(mintURL: $0) } }
    }

    static func select(
        from mints: [MintInfo], requestedURLs: [String], amount: UInt64,
        preferredURL: String?, activeURL: String? = nil,
        requiresPreferredBalance: Bool = false
    ) throws -> MintInfo {
        let candidates = matching(mints, requestedURLs: requestedURLs)
        guard !candidates.isEmpty else { throw NFCPaymentError.noMatchingMint(requestedMints: requestedURLs) }
        if let preferredURL,
           let mint = candidates.first(where: { $0.matches(mintURL: preferredURL) }) {
            if mint.balance >= amount { return mint }
            if requiresPreferredBalance { throw NFCPaymentError.insufficientBalance(required: amount, available: mint.balance) }
        }
        if let activeURL,
           let mint = candidates.first(where: { $0.matches(mintURL: activeURL) }),
           mint.balance >= amount { return mint }
        guard let mint = candidates.first(where: { $0.balance >= amount }) else {
            throw NFCPaymentError.insufficientBalance(required: amount, available: candidates.map(\.balance).max() ?? 0)
        }
        return mint
    }

    /// Selected first, then mints `isAvailable` accepts, then those that cover
    /// `minimumAmount`, then by balance and name.
    static func sorted(
        _ mints: [MintInfo], selectedID: String? = nil,
        isAvailable: ((MintInfo) -> Bool)? = nil, minimumAmount: UInt64? = nil
    ) -> [MintInfo] {
        mints.sorted { lhs, rhs in
            let lhsSelected = lhs.id == selectedID
            let rhsSelected = rhs.id == selectedID
            if lhsSelected != rhsSelected { return lhsSelected }
            if let isAvailable {
                let lhsAvailable = isAvailable(lhs)
                let rhsAvailable = isAvailable(rhs)
                if lhsAvailable != rhsAvailable { return lhsAvailable }
            }
            if let minimumAmount {
                let lhsCanPay = lhs.balance >= minimumAmount
                let rhsCanPay = rhs.balance >= minimumAmount
                if lhsCanPay != rhsCanPay { return lhsCanPay }
            }
            if lhs.balance == rhs.balance { return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending }
            return lhs.balance > rhs.balance
        }
    }
}
