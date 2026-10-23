import Foundation

/// Which two held mints a transfer runs between, and the rules for choosing
/// and changing them (Android `MintTransferRoute.kt` parity) — extracted for
/// unit testing.
struct MintTransferRoute: Equatable {
    var sourceMintURL: String
    var destinationMintURL: String

    /// The pair the screen opens on, or nil when the wallet holds fewer than
    /// two mints.
    ///
    /// Opened from the Mints list it moves the largest balance toward the
    /// default mint — the likeliest intent, and one tap on the arrow away from
    /// the other. Opened from a mint, that mint is the source when it has
    /// something to send and the destination when it is empty.
    static func initial(
        mints: [MintInfo],
        activeMintURL: String?,
        openedFromMintURL: String? = nil
    ) -> MintTransferRoute? {
        guard mints.count >= 2 else { return nil }

        func source(excluding excluded: MintInfo?) -> MintInfo? {
            let candidates = mints.filter { !same($0.url, excluded?.url) }
            let funded = candidates.filter { $0.balance > 0 && MintTransferEligibility.canSend($0) }
            // `max` keeps the first of equals, so list order breaks a tie.
            return funded.max { $0.balance < $1.balance }
                ?? candidates.first(where: MintTransferEligibility.canSend)
                ?? candidates.first
        }

        func destination(excluding excluded: MintInfo) -> MintInfo? {
            let candidates = mints.filter { !same($0.url, excluded.url) }
            let receiving = candidates.filter(MintTransferEligibility.canReceive)
            return receiving.first { same($0.url, activeMintURL) }
                ?? receiving.first
                ?? candidates.first
        }

        if let opened = mints.first(where: { same($0.url, openedFromMintURL) }) {
            if opened.balance > 0, MintTransferEligibility.canSend(opened) {
                guard let destination = destination(excluding: opened) else { return nil }
                return MintTransferRoute(sourceMintURL: opened.url, destinationMintURL: destination.url)
            }
            guard let source = source(excluding: opened) else { return nil }
            return MintTransferRoute(sourceMintURL: source.url, destinationMintURL: opened.url)
        }

        guard let source = source(excluding: nil),
              let destination = destination(excluding: source) else { return nil }
        return MintTransferRoute(sourceMintURL: source.url, destinationMintURL: destination.url)
    }

    var swapped: MintTransferRoute {
        MintTransferRoute(sourceMintURL: destinationMintURL, destinationMintURL: sourceMintURL)
    }

    /// Put `mintURL` in one slot. Choosing the mint that sits in the other slot
    /// swaps the two, so a pick never dead-ends on "already in use".
    func choosing(_ mintURL: String, as direction: MintSelectorDirection) -> MintTransferRoute {
        switch direction {
        case .source:
            if Self.same(mintURL, destinationMintURL) { return swapped }
            return MintTransferRoute(sourceMintURL: mintURL, destinationMintURL: destinationMintURL)
        case .destination:
            if Self.same(mintURL, sourceMintURL) { return swapped }
            return MintTransferRoute(sourceMintURL: sourceMintURL, destinationMintURL: mintURL)
        }
    }

    private static func same(_ url: String, _ other: String?) -> Bool {
        guard let other else { return false }
        return MintURLIdentity.normalized(url) == MintURLIdentity.normalized(other)
    }
}

/// What stands between the entered amount and the Continue button.
enum MintTransferEntry: Equatable {
    case empty
    case blocked(MintTransferEligibility.Blocker)
    /// More than the source holds. Fees are settled on the review step.
    case overBalance
    case ready

    static func validation(amount: UInt64, source: MintInfo, destination: MintInfo) -> MintTransferEntry {
        if let blocker = MintTransferEligibility.blocker(source: source, destination: destination) {
            return .blocked(blocker)
        }
        if amount == 0 { return .empty }
        return amount > source.balance ? .overBalance : .ready
    }
}
