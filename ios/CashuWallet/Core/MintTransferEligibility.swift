import Foundation

/// Pure rules for whether ecash can move from one held mint to another
/// (Android `MintTransferEligibility.kt` parity) — extracted for unit testing.
///
/// A transfer melts at the source to pay a BOLT11 invoice minted at the
/// destination, in sat. Balance is deliberately not a rule here: an empty
/// source is a valid selection, it just has nothing to send.
enum MintTransferEligibility {
    enum Blocker: Equatable {
        case sameMint
        case sourceCannotSend
        case destinationCannotReceive
    }

    static func blocker(source: MintInfo, destination: MintInfo) -> Blocker? {
        if MintURLIdentity.normalized(source.url) == MintURLIdentity.normalized(destination.url) {
            return .sameMint
        }
        if !canSend(source) { return .sourceCannotSend }
        if !canReceive(destination) { return .destinationCannotReceive }
        return nil
    }

    static func eligible(source: MintInfo, destination: MintInfo) -> Bool {
        blocker(source: source, destination: destination) == nil
    }

    /// A mint whose capability was never fetched falls back to its method
    /// lists, which default to BOLT11 — the same benefit of the doubt every
    /// other flow gives an unfetched mint.
    static func canSend(_ mint: MintInfo) -> Bool {
        mint.bolt11Sat?.canMelt ?? mint.supportedMeltMethods.contains(.bolt11)
    }

    static func canReceive(_ mint: MintInfo) -> Bool {
        mint.bolt11Sat?.canMint
            ?? (mint.supportedMintMethods.contains(.bolt11) && mint.mintUnits.contains("sat"))
    }

    /// Amounts both mints advertise they accept: the destination's mint limits
    /// intersected with the source's melt limits. Nil when they do not overlap.
    /// Advisory only — limits may have changed since the last fetch, and the
    /// mint is the final judge.
    static func amountRange(source: MintInfo, destination: MintInfo) -> ClosedRange<UInt64>? {
        let lower = max(destination.bolt11Sat?.mintMin ?? 1, source.bolt11Sat?.meltMin ?? 1, 1)
        let upper = min(destination.bolt11Sat?.mintMax ?? .max, source.bolt11Sat?.meltMax ?? .max)
        return lower <= upper ? lower...upper : nil
    }
}
