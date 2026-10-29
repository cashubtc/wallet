import SwiftUI

/// What the home screen shows of the wallet: the Recent rows, by id, and the
/// balances. A row's content always renders live; only its arrival or departure
/// waits for the beat.
struct HomeSnapshot: Equatable {
    struct Row: Equatable {
        let id: String
        let isIncoming: Bool
    }

    var rows: [Row]
    var balancesByUnit: [String: UInt64]
    var satBalance: UInt64

    /// The balance held in a lowercased unit.
    func balance(of unit: String) -> UInt64 {
        balancesByUnit.first { $0.key.lowercased() == unit }?.value
            ?? (unit == "sat" ? satBalance : 0)
    }

    /// True when `self` holds an incoming row that `earlier` did not.
    func hasNewIncomingRow(since earlier: HomeSnapshot) -> Bool {
        let known = Set(earlier.rows.map(\.id))
        return rows.contains { $0.isIncoming && !known.contains($0.id) }
    }
}

/// Where the home screen stands relative to the user.
enum HomePresence: Equatable {
    /// Nothing covers it: changes play as they settle.
    case onScreen
    /// A sheet, cover, pushed page, another tab, the lock screen or the
    /// background hides it: changes wait and replay on return.
    case covered
    /// The History tab is showing and plays arrivals itself, so home takes
    /// changes silently and replays nothing.
    case seenElsewhere
}

/// A confirmed incoming payment, as home hears about it.
struct HomeReceipt: Equatable {
    let amount: UInt64
    let unit: String
    /// No other surface confirms this payment (npub.cash, a background
    /// Lightning payment, an unwatched Cashu request), so home owns its haptic
    /// and spoken announcement, and replays its "+N" after a cover.
    let homeOwned: Bool

    /// Lowercased mint unit ("sat", "eur", …).
    var unitKey: String { unit.lowercased() }

    /// Spoken receipt confirmation, respecting the current balance visibility.
    static func announcement(for receipts: [HomeReceipt], hideBalances: Bool) -> String {
        if hideBalances { return "Payment received" }
        var totals: [(unit: String, amount: UInt64)] = []
        for receipt in receipts {
            let unit = receipt.unitKey
            if let index = totals.firstIndex(where: { $0.unit == unit }) {
                totals[index].amount += receipt.amount
            } else {
                totals.append((unit, receipt.amount))
            }
        }
        let amounts = totals.map { total in
            total.unit == "sat"
                ? AmountFormatter.satsParts(total.amount, useBitcoinSymbol: false).spoken
                : AmountParts.parse(CurrencyAmount(
                    value: total.amount,
                    currency: CurrencyRegistry.currency(forMintUnit: total.unit)
                ).formatted()).spoken
        }
        return "Received " + amounts.joined(separator: " and ")
    }
}

/// Everything home shows changing at once: rows, balances, "+N", haptic and
/// announcement start on the same frame.
struct HomeBeat: Equatable {
    let snapshot: HomeSnapshot
    /// "+N" per lowercased unit; empty for none.
    let deltas: [String: UInt64]
    /// Home-owned receipts whose haptic and announcement play with this beat.
    let confirmations: [HomeReceipt]
}

/// The home arrival rules, kept free of timers and views so they test directly.
///
/// The wallet publishes a receipt's pieces separately (balance, then rows, and
/// the receipt notification on either side), so home holds what it shows and
/// moves it in one beat once the pieces have settled.
struct HomeArrivalLedger {
    /// What home shows. nil until the first transaction load: until then home
    /// mirrors the wallet with no motion, so a launch never plays an arrival.
    private(set) var shown: HomeSnapshot?
    private(set) var presence: HomePresence
    private var live: HomeSnapshot?
    /// Receipts that landed on screen, per unit, waiting for their beat.
    private var liveDeltas: [String: UInt64] = [:]
    private var liveConfirmations: [HomeReceipt] = []
    /// Home-owned receipts that landed under a cover, replayed as "+N".
    private var heldDeltas: [String: UInt64] = [:]
    /// A receipt landed on screen before its row did.
    private var awaitingIncomingRow = false

    init(presence: HomePresence = .covered) {
        self.presence = presence
    }

    /// True while a beat is waiting to play.
    var hasPendingBeat: Bool {
        guard presence == .onScreen, let shown, let live else { return false }
        return live != shown || !liveDeltas.isEmpty || !heldDeltas.isEmpty || !liveConfirmations.isEmpty
    }

    /// Feed the wallet's current state. `loaded` is false until the wallet's
    /// first transaction load, and again after a wallet reset.
    mutating func update(live snapshot: HomeSnapshot, loaded: Bool) {
        live = snapshot
        guard loaded else {
            shown = nil
            clearPending()
            return
        }
        if shown == nil || presence == .seenElsewhere {
            shown = snapshot
        }
    }

    /// Record a receipt. Returns the receipts whose haptic and announcement
    /// play now: home-owned ones that won't get an on-screen beat.
    mutating func receive(_ receipt: HomeReceipt) -> [HomeReceipt] {
        let confirmNow = receipt.homeOwned ? [receipt] : []
        guard shown != nil else { return confirmNow }
        switch presence {
        case .onScreen:
            liveDeltas[receipt.unitKey, default: 0] += receipt.amount
            if receipt.homeOwned { liveConfirmations.append(receipt) }
            awaitingIncomingRow = true
            return []
        case .covered:
            if receipt.homeOwned { heldDeltas[receipt.unitKey, default: 0] += receipt.amount }
            return confirmNow
        case .seenElsewhere:
            return confirmNow
        }
    }

    /// Returns the receipts whose haptic and announcement must play now,
    /// because home was covered before their beat could play.
    mutating func setPresence(_ newPresence: HomePresence) -> [HomeReceipt] {
        let old = presence
        guard newPresence != old else { return [] }
        presence = newPresence
        var flushed: [HomeReceipt] = []
        if old == .onScreen {
            // These landed while home was showing, so their "+N" replays.
            heldDeltas.merge(liveDeltas, uniquingKeysWith: +)
            liveDeltas = [:]
            flushed = liveConfirmations
            liveConfirmations = []
            awaitingIncomingRow = false
        }
        if newPresence == .seenElsewhere {
            if shown != nil { shown = live }
            heldDeltas = [:]
        }
        return flushed
    }

    /// A receipt landed on screen and its row hasn't loaded yet.
    var isAwaitingRow: Bool {
        guard awaitingIncomingRow, let shown, let live else { return false }
        return !live.hasNewIncomingRow(since: shown)
    }

    /// Plays the beat when it's ready. A receipt waits for its row, so its
    /// "+N" never shows under a balance that hasn't moved.
    mutating func takeBeat() -> HomeBeat? {
        guard hasPendingBeat, !isAwaitingRow, let shown, let live else { return nil }
        // A "+N" beside a balance that didn't rise (a send settled in the same
        // replay) would contradict the roll, so it shows only where it rose.
        let deltas = liveDeltas.merging(heldDeltas, uniquingKeysWith: +).filter { unit, _ in
            live.balance(of: unit) > shown.balance(of: unit)
        }
        let beat = HomeBeat(snapshot: live, deltas: deltas, confirmations: liveConfirmations)
        self.shown = live
        clearPending()
        return beat
    }

    /// The row is late: hands over the receipts waiting on it so their haptic
    /// and announcement play now. Their "+N" still waits to land with the row.
    mutating func takeOverdueConfirmations() -> [HomeReceipt] {
        guard isAwaitingRow else { return [] }
        defer { liveConfirmations = [] }
        return liveConfirmations
    }

    /// The row never came: stop waiting and drop the "+N" it would have
    /// carried, so later changes play again.
    mutating func stopAwaitingRow() {
        guard isAwaitingRow else { return }
        awaitingIncomingRow = false
        liveDeltas = [:]
    }

    private mutating func clearPending() {
        liveDeltas = [:]
        liveConfirmations = []
        heldDeltas = [:]
        awaitingIncomingRow = false
    }
}

/// Runs `HomeArrivalLedger` on the clock: waits for the wallet's writes to go
/// quiet, gives a receipt's row a little longer to load, then hands the beat to
/// the home screen.
@MainActor
@Observable
final class HomeArrivalCoordinator {
    /// What home renders; nil means mirror the wallet directly.
    private(set) var shown: HomeSnapshot?

    /// Plays a beat. Called on the main actor; the handler moves `shown`
    /// with `present(_:)` inside its animation.
    @ObservationIgnored var onBeat: ((HomeBeat) -> Void)?
    /// Plays haptic + announcement for receipts that won't get a beat.
    @ObservationIgnored var onConfirm: (([HomeReceipt]) -> Void)?

    @ObservationIgnored private var ledger = HomeArrivalLedger()
    @ObservationIgnored private var rowCache: [String: WalletTransaction] = [:]
    @ObservationIgnored private var liveRowIDs: Set<String> = []
    @ObservationIgnored private var settleTask: Task<Void, Never>?
    /// When a receipt waiting on its row confirms anyway, and when it stops
    /// waiting altogether.
    @ObservationIgnored private var rowDeadline: ContinuousClock.Instant?
    @ObservationIgnored private var rowGiveUp: ContinuousClock.Instant?
    @ObservationIgnored private let clock = ContinuousClock()
    private let quiet: Duration
    private let returnPause: Duration
    private let rowWait: Duration
    private let rowGiveUpWait: Duration

    /// `quiet` is how long the wallet's writes must pause before a beat;
    /// `returnPause` is the shorter pause before a replay once home is
    /// uncovered, when the writes have long settled. A mint-quote sync
    /// announces each receipt as it mints but publishes balance and rows once,
    /// after the whole pass, so a slow mint can hold the row back for seconds:
    /// after `rowWait` the receipt's haptic and announcement play without it,
    /// and after `rowGiveUpWait` home stops waiting for it.
    init(
        quiet: Duration = .milliseconds(250),
        returnPause: Duration = .milliseconds(100),
        rowWait: Duration = .seconds(5),
        rowGiveUpWait: Duration = .seconds(30)
    ) {
        self.quiet = quiet
        self.returnPause = returnPause
        self.rowWait = rowWait
        self.rowGiveUpWait = rowGiveUpWait
    }

    func update(live: HomeSnapshot, rows: [WalletTransaction], loaded: Bool) {
        for row in rows { rowCache[row.id] = row }
        liveRowIDs = Set(rows.map(\.id))
        ledger.update(live: live, loaded: loaded)
        syncShown()
        scheduleBeat()
    }

    func receive(_ receipt: HomeReceipt) {
        confirm(ledger.receive(receipt))
        scheduleBeat()
    }

    func setPresence(_ presence: HomePresence) {
        confirm(ledger.setPresence(presence))
        syncShown()
        scheduleBeat(after: presence == .onScreen ? returnPause : quiet)
    }

    /// The last known copy of a row the wallet no longer lists, so a row that
    /// is leaving can still render until its beat.
    func cachedRow(id: String) -> WalletTransaction? {
        rowCache[id]
    }

    /// Moves what home shows to the beat's snapshot. Call from `onBeat`,
    /// inside the beat's animation.
    func present(_ beat: HomeBeat) {
        shown = beat.snapshot
    }

    private func syncShown() {
        guard shown != ledger.shown else { return }
        shown = ledger.shown
        pruneRowCache()
    }

    private func pruneRowCache() {
        let kept = liveRowIDs.union(shown?.rows.map(\.id) ?? [])
        rowCache = rowCache.filter { kept.contains($0.key) }
    }

    private func scheduleBeat(after pause: Duration? = nil) {
        settleTask?.cancel()
        settleTask = nil
        guard ledger.hasPendingBeat else {
            clearRowDeadlines()
            return
        }
        if ledger.isAwaitingRow, rowDeadline == nil {
            rowDeadline = clock.now.advanced(by: rowWait)
            rowGiveUp = clock.now.advanced(by: rowGiveUpWait)
        }
        settle(after: pause ?? quiet)
    }

    private func settle(after pause: Duration) {
        settleTask = Task { [weak self] in
            try? await Task.sleep(for: pause)
            guard !Task.isCancelled else { return }
            self?.settle()
        }
    }

    private func settle() {
        settleTask = nil
        let now = clock.now
        if ledger.isAwaitingRow, let rowGiveUp, now >= rowGiveUp {
            ledger.stopAwaitingRow()
        }
        if let beat = ledger.takeBeat() {
            clearRowDeadlines()
            if let onBeat {
                onBeat(beat)
            } else {
                present(beat)
            }
            pruneRowCache()
            return
        }
        guard ledger.isAwaitingRow, let rowDeadline, let rowGiveUp else { return }
        if now >= rowDeadline { confirm(ledger.takeOverdueConfirmations()) }
        // The wallet's next write reschedules; otherwise wake at the next deadline.
        settle(after: (now < rowDeadline ? rowDeadline : rowGiveUp) - now)
    }

    private func clearRowDeadlines() {
        rowDeadline = nil
        rowGiveUp = nil
    }

    private func confirm(_ receipts: [HomeReceipt]) {
        guard !receipts.isEmpty else { return }
        onConfirm?(receipts)
    }
}
