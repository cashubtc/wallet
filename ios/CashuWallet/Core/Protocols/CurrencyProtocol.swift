import Foundation

// MARK: - Currency Protocol

/// Protocol defining the interface for different currencies.
/// Implementations include SAT (satoshis), USD, EUR, and potentially other fiat currencies.
///
/// This abstraction allows the wallet to:
/// - Display amounts in different currency denominations
/// - Support mints with different unit types
/// - Convert between currencies when needed
protocol Currency {
    /// ISO-style currency code (e.g., "SAT", "USD", "EUR")
    var code: String { get }
    
    /// Currency symbol for display (e.g., "₿", "$", "€")
    var symbol: String { get }
    
    /// Number of decimal places for this currency
    /// SAT = 0, USD/EUR = 2
    var decimals: Int { get }
    
    /// Human-readable name (e.g., "Satoshis", "US Dollar", "Euro")
    var displayName: String { get }
    
    /// Whether symbol appears before or after the amount
    var symbolPosition: CurrencySymbolPosition { get }
}

/// Position of currency symbol relative to amount
enum CurrencySymbolPosition {
    case before  // $100
    case after   // 100 SAT
}

// MARK: - Built-in Currencies

/// Satoshis - the base unit for Bitcoin/Lightning
struct SatoshiCurrency: Currency {
    let code = "SAT"
    let symbol = "₿"
    let decimals = 0
    let displayName = "Satoshis"
    let symbolPosition = CurrencySymbolPosition.before
}

/// US Dollar
struct USDCurrency: Currency {
    let code = "USD"
    let symbol = "$"
    let decimals = 2
    let displayName = "US Dollar"
    let symbolPosition = CurrencySymbolPosition.before
}

/// Euro
struct EURCurrency: Currency {
    let code = "EUR"
    let symbol = "€"
    let decimals = 2
    let displayName = "Euro"
    let symbolPosition = CurrencySymbolPosition.before
}

/// Fallback for an arbitrary mint unit the registry doesn't know about (any
/// custom unit string a mint might advertise). Treated as an integer base unit
/// (no decimals) and displayed with the raw code as a trailing label.
struct GenericCurrency: Currency {
    let code: String
    let symbol = ""
    let decimals = 0
    let displayName: String
    let symbolPosition = CurrencySymbolPosition.after

    init(unit: String) {
        let normalized = unit.uppercased()
        self.code = normalized
        self.displayName = normalized
    }
}

// MARK: - Currency Amount

/// A value with an associated currency
struct CurrencyAmount: Equatable {
    /// The raw value in the smallest unit (sats for BTC, cents for fiat)
    let value: UInt64
    
    /// The currency this amount is denominated in
    let currency: any Currency
    
    /// Create amount from smallest unit value
    init(value: UInt64, currency: any Currency) {
        self.value = value
        self.currency = currency
    }
    
    /// Create satoshi amount (convenience)
    static func sats(_ value: UInt64) -> CurrencyAmount {
        CurrencyAmount(value: value, currency: SatoshiCurrency())
    }
    
    /// Create USD amount from cents
    static func usdCents(_ cents: UInt64) -> CurrencyAmount {
        CurrencyAmount(value: cents, currency: USDCurrency())
    }
    
    /// Create EUR amount from cents
    static func eurCents(_ cents: UInt64) -> CurrencyAmount {
        CurrencyAmount(value: cents, currency: EURCurrency())
    }
    
    /// The display value (e.g., 1000 cents = 10.00)
    var displayValue: Double {
        if currency.decimals == 0 {
            return Double(value)
        }
        return Double(value) / pow(10.0, Double(currency.decimals))
    }
    
    /// Formatted string for display
    func formatted(showSymbol: Bool = true) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = currency.decimals
        formatter.maximumFractionDigits = currency.decimals
        formatter.usesGroupingSeparator = true
        formatter.groupingSeparator = ","
        formatter.decimalSeparator = "."
        
        let formattedValue = formatter.string(from: NSNumber(value: displayValue)) ?? "\(displayValue)"
        
        guard showSymbol else { return formattedValue }
        
        switch currency.symbolPosition {
        case .before:
            return "\(currency.symbol)\(formattedValue)"
        case .after:
            return "\(formattedValue) \(currency.code)"
        }
    }
    
    // MARK: - Equatable
    
    static func == (lhs: CurrencyAmount, rhs: CurrencyAmount) -> Bool {
        lhs.value == rhs.value && lhs.currency.code == rhs.currency.code
    }
}

// MARK: - Home Balance Ordering

/// Ordering for the multi-unit home balance pager. Sat is always first (the
/// wallet's base unit and identity); every other unit the user actually holds a
/// positive balance in follows, sorted. A wallet with only sat — or no non-sat
/// balance — yields just `["sat"]`, so the home shows its single hero unchanged.
enum HomeBalance {
    static func homeBalanceUnits(_ balancesByUnit: [String: UInt64]) -> [String] {
        let nonSat = balancesByUnit
            .filter { $0.key.lowercased() != "sat" && $0.value > 0 }
            .keys
            .sorted()
        return ["sat"] + nonSat
    }

    /// Resolves a stored/last-viewed unit against the currently available units,
    /// falling back to "sat" when that unit no longer carries a balance.
    static func resolvedUnit(_ unit: String, in units: [String]) -> String {
        units.contains(unit) ? unit : "sat"
    }

    /// Held funds stay visible when the active mint's payment options change.
    static func showsUnitPager(balancesByUnit: [String: UInt64]) -> Bool {
        homeBalanceUnits(balancesByUnit).count > 1
    }
}

// MARK: - Home Balance Cycle

/// What the home balance shows. Tapping it steps the sat page through
/// bitcoin → fiat → hidden (skipping fiat when there's no fiat line) and every
/// other unit page through unit → hidden. Hidden is one switch for all pages.
enum HomeBalanceStep: Equatable {
    /// Bitcoin on the sat page, the page's own unit elsewhere.
    case unit
    case fiat
    case hidden
}

enum HomeBalanceCycle {
    static let hiddenLabel = "Balance hidden"

    /// The step on screen. Fiat comes from the effective primary, not the
    /// stored preference, so a price that drops out reads as the unit step and
    /// the next tap hides rather than landing on a fiat line that isn't there.
    static func current(
        isHidden: Bool,
        effectivePrimary: AmountDisplayPrimary,
        isSatPage: Bool
    ) -> HomeBalanceStep {
        if isHidden { return .hidden }
        return isSatPage && effectivePrimary == .fiat ? .fiat : .unit
    }

    static func next(after step: HomeBalanceStep, hasFiat: Bool, isSatPage: Bool) -> HomeBalanceStep {
        switch step {
        case .unit: isSatPage && hasFiat ? .fiat : .hidden
        case .fiat: .hidden
        case .hidden: .unit
        }
    }

    /// The settings a step writes. Hiding keeps the stored primary; showing
    /// again always lands on bitcoin, from any page.
    static func settings(
        for step: HomeBalanceStep,
        keeping primary: AmountDisplayPrimary
    ) -> (primary: AmountDisplayPrimary, hidden: Bool) {
        switch step {
        case .unit: (.sats, false)
        case .fiat: (.fiat, false)
        case .hidden: (primary, true)
        }
    }

    /// What the next tap does, phrased for "Double tap to …" and the named
    /// accessibility action. `unitName` is "sats" or the page's currency code.
    static func actionPhrase(for next: HomeBalanceStep, fiatText: String?, unitName: String) -> String {
        switch next {
        case .hidden: "hide balance"
        case .fiat: "make \(fiatText ?? "fiat") primary"
        case .unit: "show balance in \(unitName)"
        }
    }
}

// MARK: - Currency Registry

/// Registry for looking up currencies by code
enum CurrencyRegistry {
    private static let satoshiUnitNames: Set<String> = [
        "sat", "sats", "satoshi", "satoshis"
    ]

    /// All supported currencies
    static let supportedCurrencies: [any Currency] = [
        SatoshiCurrency(),
        USDCurrency(),
        EURCurrency()
    ]
    
    /// Get currency by code
    static func currency(forCode code: String) -> (any Currency)? {
        supportedCurrencies.first { $0.code.uppercased() == code.uppercased() }
    }

    /// Whether a mint unit represents Bitcoin's satoshi account. History can
    /// receive legacy aliases from persisted requests and tokens, so display
    /// conversion must use the same classification as the currency registry.
    static func isSatoshiUnit(_ unit: String) -> Bool {
        satoshiUnitNames.contains(normalizedMintUnit(unit))
    }
    
    /// Map a mint unit string to a currency for entry precision + display.
    /// Known units resolve to their built-in currency; any other (custom) unit
    /// falls back to a `GenericCurrency` so the result is never nil and
    /// arbitrary mint units are supported.
    static func currency(forMintUnit unit: String) -> any Currency {
        let normalized = normalizedMintUnit(unit)
        if satoshiUnitNames.contains(normalized) {
            return SatoshiCurrency()
        }

        switch normalized {
        case "usd", "dollar", "dollars":
            return USDCurrency()
        case "eur", "euro", "euros":
            return EURCurrency()
        default:
            return GenericCurrency(unit: unit.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    private static func normalizedMintUnit(_ unit: String) -> String {
        unit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
