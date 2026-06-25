import Foundation

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

    /// Wrap a numeric string with the selected currency's symbol in the locale's
    /// position, by extracting the prefix/suffix from a narrow-currency template.
    @MainActor
    private static func wrapWithCurrencySymbol(_ number: String) -> String {
        let code = PriceService.shared.currencyCode
        let template = Decimal(0).formatted(
            .currency(code: code).presentation(.narrow).precision(.fractionLength(0))
        )
        guard let zero = template.range(of: "0") else { return number }
        let prefix = String(template[..<zero.lowerBound])
        let suffix = String(template[zero.upperBound...])
        return prefix + number + suffix
    }
}
