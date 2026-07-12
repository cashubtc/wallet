import Foundation

struct AmountDisplayText {
    let primary: String
    let secondary: String?
    let effectivePrimary: AmountDisplayPrimary
}

enum AmountFormatter {
    private static let decimalFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        return formatter
    }()

    static func sats(_ sats: UInt64, useBitcoinSymbol: Bool, includeUnit: Bool = true) -> String {
        let formatted = decimalFormatter.string(from: NSNumber(value: sats)) ?? "\(sats)"
        if useBitcoinSymbol {
            return "₿\(formatted)"
        }
        return includeUnit ? "\(formatted) sat" : formatted
    }

    /// Canonical fiat presentation for wallet amounts. A fixed POSIX/US
    /// presentation keeps the ISO currency's symbol and placement stable across
    /// device locales (USD is always "$60.00", never "US$60.00" or "60.00 $").
    static func fiat(_ amount: Double, currencyCode: String) -> String {
        let formatter = currencyFormatter(currencyCode: currencyCode)
        return formatter.string(from: NSNumber(value: amount)) ?? String(format: "%.2f", amount)
    }

    /// Converts sats at the supplied BTC price and formats the selected fiat
    /// currency. A missing/non-positive price produces no conversion.
    static func fiat(sats: UInt64, btcPrice: Double?, currencyCode: String) -> String? {
        guard let btcPrice, btcPrice > 0 else { return nil }
        let value = Double(sats) / 100_000_000.0 * btcPrice
        return fiat(value, currencyCode: currencyCode)
    }

    /// The single source of truth for primary/secondary wallet amount ordering.
    /// Fiat preference falls back to sats until a live/cached price is available.
    static func displayText(
        amountSats: UInt64,
        preferredPrimary: AmountDisplayPrimary,
        showFiat: Bool,
        btcPrice: Double?,
        currencyCode: String,
        useBitcoinSymbol: Bool
    ) -> AmountDisplayText {
        let fiatText = showFiat
            ? fiat(sats: amountSats, btcPrice: btcPrice, currencyCode: currencyCode)
            : nil
        let satsText = sats(amountSats, useBitcoinSymbol: useBitcoinSymbol)
        let effectivePrimary: AmountDisplayPrimary =
            preferredPrimary == .fiat && fiatText == nil ? .sats : preferredPrimary

        switch effectivePrimary {
        case .fiat:
            return AmountDisplayText(primary: fiatText ?? satsText, secondary: satsText, effectivePrimary: effectivePrimary)
        case .sats:
            return AmountDisplayText(primary: satsText, secondary: fiatText, effectivePrimary: effectivePrimary)
        }
    }

    // MARK: - Live amount entry (sats or fiat)
    //
    // The keypad writes a single `amountString`; what it *means* depends on the
    // active entry unit. In sats mode it's an integer; in fiat mode it's a
    // locale-formatted decimal (cents) that converts to sats at the live price.
    // These helpers are the single source of truth for that pipeline so every
    // entry screen stays thin.

    /// The locale's decimal separator ("," or "."), used as the keypad's
    /// fiat-only decimal key and when parsing/formatting typed fiat.
    static var decimalSeparator: String {
        Locale.current.decimalSeparator ?? "."
    }

    /// Locale-aware grouping for the integer part of a typed fiat amount.
    private static let fiatGroupingFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        return formatter
    }()

    /// Satoshis represented by a typed string in the given entry unit.
    @MainActor
    static func entrySats(raw: String, unit: AmountDisplayPrimary) -> UInt64 {
        switch unit {
        case .sats:
            return UInt64(raw) ?? 0
        case .fiat:
            return PriceService.shared.fiatToSats(parseFiat(raw))
        }
    }

    /// Append a keypad digit under entry rules. Sats mode is an integer append
    /// that collapses a lone leading zero. Fiat mode is a cents accumulator:
    /// the digit shifts in at the right (cents), so the value is always a
    /// complete two-decimal amount and a pre-filled/converted figure stays
    /// editable (there is no decimal key in fiat mode). Returns the new raw
    /// string (unchanged if the key is rejected, so the caller can skip the
    /// haptic).
    static func entryAppend(_ key: String, to raw: String, unit: AmountDisplayPrimary) -> String {
        guard key.count == 1, let ch = key.first, ch.isNumber else { return raw }

        switch unit {
        case .sats:
            // Integer part — collapse a lone leading zero ("0" + "5" -> "5").
            return raw == "0" ? key : raw + key
        case .fiat:
            let cents = parseFiatCents(raw)
            guard cents < maxFiatCents else { return raw }
            let updated = cents * 10 + UInt64(ch.wholeNumberValue ?? 0)
            return updated == 0 ? "" : centsToFiatString(updated)
        }
    }

    /// Remove the last keypad input. Sats drops the trailing digit; fiat shifts
    /// the cents accumulator right by one (14.54 -> 1.45 -> 0.14 -> ""). Returns
    /// the new raw string (unchanged if empty, so the caller can skip the haptic).
    static func entryBackspace(_ raw: String, unit: AmountDisplayPrimary) -> String {
        guard !raw.isEmpty else { return raw }
        switch unit {
        case .sats:
            return String(raw.dropLast())
        case .fiat:
            let cents = parseFiatCents(raw) / 10
            return cents == 0 ? "" : centsToFiatString(cents)
        }
    }

    // MARK: - Live amount entry in an explicit mint unit
    //
    // A mint *account* unit (sat, eur, usd, or a custom string) is entered
    // directly in that unit — no BTC-price conversion. `decimals` comes from the
    // unit's `Currency` (0 for sat/custom, 2 for usd/eur). `decimals == 0` is an
    // integer append; `decimals > 0` is a minor-unit accumulator (digits shift
    // in from the right), mirroring the fiat cents keypad but yielding the
    // unit's own base amount instead of converting to sats.

    /// The integer base-unit value of a typed string for a unit with `decimals`
    /// fraction digits ("5.00", 2 → 500; "500", 0 → 500).
    static func entryBaseUnits(raw: String, decimals: Int) -> UInt64 {
        guard decimals > 0 else { return UInt64(raw) ?? 0 }
        return UInt64(raw.filter { $0.isNumber }) ?? 0
    }

    /// Append a keypad digit for direct unit entry. Integer units collapse a
    /// lone leading zero; fractional units accumulate minor units from the right.
    /// Returns the unchanged string when the key is rejected (so the caller can
    /// skip the haptic).
    static func entryAppendUnit(_ key: String, to raw: String, decimals: Int) -> String {
        guard key.count == 1, let ch = key.first, ch.isNumber else { return raw }
        guard decimals > 0 else {
            return raw == "0" ? key : raw + key
        }
        let minor = entryBaseUnits(raw: raw, decimals: decimals)
        guard minor < maxMinorUnits else { return raw }
        let updated = minor * 10 + UInt64(ch.wholeNumberValue ?? 0)
        return updated == 0 ? "" : minorUnitString(updated, decimals: decimals)
    }

    /// Remove the last keypad input for direct unit entry (mirrors `entryBackspace`).
    static func entryBackspaceUnit(_ raw: String, decimals: Int) -> String {
        guard !raw.isEmpty else { return raw }
        guard decimals > 0 else { return String(raw.dropLast()) }
        let minor = entryBaseUnits(raw: raw, decimals: decimals) / 10
        return minor == 0 ? "" : minorUnitString(minor, decimals: decimals)
    }

    /// The typed-entry string for a base-unit amount in a unit with `decimals`
    /// fraction digits — the inverse of `entryBaseUnits` (500, 2 → "5.00").
    /// Empty for a zero amount so the keypad shows its placeholder.
    static func entryString(baseUnits: UInt64, decimals: Int) -> String {
        guard baseUnits > 0 else { return "" }
        return minorUnitString(baseUnits, decimals: decimals)
    }

    /// Ceiling on the minor-unit accumulator, matching the fiat entry cap so a
    /// held key can't run past sane bounds.
    private static let maxMinorUnits: UInt64 = 99_999_999_999

    /// A locale-separated string with `decimals` fraction digits for a minor-unit
    /// integer (1454, 2 → "14.54"); no grouping/symbol — those are added at
    /// display time via the unit's `Currency`.
    private static func minorUnitString(_ minor: UInt64, decimals: Int) -> String {
        guard decimals > 0 else { return String(minor) }
        var divisor: UInt64 = 1
        for _ in 0..<decimals { divisor *= 10 }
        let intPart = minor / divisor
        let fracPart = Int(minor % divisor)
        let frac = String(format: "%0\(decimals)d", fracPart)
        return "\(intPart)\(decimalSeparator)\(frac)"
    }

    /// Re-express a typed string when the entry unit flips, preserving the
    /// amount through sats so the displayed value stays economically equal.
    @MainActor
    static func entryConverted(raw: String, from: AmountDisplayPrimary, to: AmountDisplayPrimary) -> String {
        guard from != to, !raw.isEmpty else { return raw }
        let sats = entrySats(raw: raw, unit: from)
        guard sats > 0 else { return "" }
        switch to {
        case .sats:
            return String(sats)
        case .fiat:
            return fiatEntryString(PriceService.shared.satsToFiat(sats))
        }
    }

    /// The big primary line for a typed string, formatted live in the entry
    /// unit and partial-aware (a trailing separator and trailing zeros render
    /// exactly as typed). Fiat reuses the locale's symbol position + separators.
    @MainActor
    static func entryPrimary(raw: String, unit: AmountDisplayPrimary, useBitcoinSymbol: Bool) -> String {
        switch unit {
        case .sats:
            return sats(UInt64(raw) ?? 0, useBitcoinSymbol: useBitcoinSymbol)
        case .fiat:
            let sep = decimalSeparator
            let parts = raw.split(separator: Character(sep), omittingEmptySubsequences: false)
            let intRaw = parts.first.map(String.init) ?? ""
            let intValue = UInt64(intRaw) ?? 0
            let groupedInt = fiatGroupingFormatter.string(from: NSNumber(value: intValue)) ?? "\(intValue)"

            var number = groupedInt
            if raw.contains(sep) {
                let fracRaw = parts.count > 1 ? String(parts[1]) : ""
                number += sep + fracRaw
            }
            return wrapWithCurrencySymbol(number)
        }
    }

    // MARK: - Fiat parsing / formatting helpers

    /// Ceiling on the integer part of a typed fiat amount (~$1B), so the cents
    /// accumulator can't run away past sane bounds.
    private static let maxFiatCents: UInt64 = 99_999_999_999

    /// Parse a typed fiat string (locale separator) into a `Double`.
    private static func parseFiat(_ raw: String) -> Double {
        guard !raw.isEmpty else { return 0 }
        var normalized = raw.replacingOccurrences(of: decimalSeparator, with: ".")
        if normalized.hasSuffix(".") { normalized.removeLast() }
        return Double(normalized) ?? 0
    }

    /// Integer cents in a typed fiat string ("14.54" -> 1454).
    private static func parseFiatCents(_ raw: String) -> UInt64 {
        UInt64(raw.filter { $0.isNumber }) ?? 0
    }

    /// A locale-separated two-decimal fiat string (1454 -> "14.54"); no
    /// grouping or symbol — those are added at display time.
    private static func centsToFiatString(_ cents: UInt64) -> String {
        "\(cents / 100)\(decimalSeparator)\(String(format: "%02d", cents % 100))"
    }

    /// A raw entry string (locale separator, two decimals, no grouping/symbol)
    /// for a fiat value — used when converting sats -> fiat on a flip.
    private static func fiatEntryString(_ fiat: Double) -> String {
        let cents = (fiat * 100).rounded()
        guard cents.isFinite, cents > 0, cents < Double(UInt64.max) else { return "" }
        return centsToFiatString(UInt64(cents))
    }

    /// Wrap a partial numeric entry with the canonical currency prefix/suffix.
    @MainActor
    private static func wrapWithCurrencySymbol(_ number: String) -> String {
        let formatter = currencyFormatter(currencyCode: PriceService.shared.currencyCode)
        return (formatter.positivePrefix ?? "") + number + (formatter.positiveSuffix ?? "")
    }

    private static func currencyFormatter(currencyCode: String) -> NumberFormatter {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .currency
        formatter.currencyCode = currencyCode.uppercased()
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        // NumberFormatter can inject a regular or non-breaking space between a
        // currency symbol and the number. Wallet-native currency amounts use a
        // compact affix ("$12.23"), so converted BTC amounts must match it.
        formatter.positivePrefix = formatter.positivePrefix?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        formatter.positiveSuffix = formatter.positiveSuffix?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        formatter.negativePrefix = formatter.negativePrefix?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        formatter.negativeSuffix = formatter.negativeSuffix?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return formatter
    }
}
