import XCTest
@testable import CashuWallet

final class HomeArrivalLedgerTests: XCTestCase {
    private let base = snapshot(rows: [("old", true)], sats: 1_000)
    private let landed = snapshot(rows: [("new", true), ("old", true)], sats: 3_500)
    private let paid = HomeReceipt(amount: 2_500, unit: "sat", homeOwned: true)

    func testHomeMirrorsTheWalletUntilTheFirstLoad() {
        var ledger = HomeArrivalLedger(presence: .onScreen)
        ledger.update(live: base, loaded: false)
        XCTAssertNil(ledger.shown)

        ledger.update(live: landed, loaded: true)
        XCTAssertEqual(ledger.shown, landed, "the first load is shown as is")
        XCTAssertFalse(ledger.hasPendingBeat)
        XCTAssertNil(ledger.takeBeat())
    }

    func testWalletResetReturnsHomeToMirroring() {
        var ledger = loaded(.onScreen)
        ledger.update(live: snapshot(rows: [], sats: 0), loaded: false)
        XCTAssertNil(ledger.shown)
        XCTAssertFalse(ledger.hasPendingBeat)
    }

    func testOnScreenReceiptWaitsForItsRowThenPlaysOneBeat() {
        var ledger = loaded(.onScreen)
        XCTAssertEqual(ledger.receive(paid), [], "on screen, the haptic waits for the beat")

        // The notification can land before the balance and the row.
        XCTAssertNil(ledger.takeBeat())
        ledger.update(live: snapshot(rows: [("old", true)], sats: 3_500), loaded: true)
        XCTAssertNil(ledger.takeBeat(), "the balance alone doesn't play")

        ledger.update(live: landed, loaded: true)
        let beat = ledger.takeBeat()
        XCTAssertEqual(beat, HomeBeat(snapshot: landed, deltas: ["sat": 2_500], confirmations: [paid]))
        XCTAssertEqual(ledger.shown, landed)
        XCTAssertFalse(ledger.hasPendingBeat)
    }

    func testLateRowConfirmsFirstAndCarriesPlusNWhenItLands() {
        var ledger = loaded(.onScreen)
        _ = ledger.receive(paid)
        XCTAssertTrue(ledger.isAwaitingRow)
        XCTAssertEqual(ledger.takeOverdueConfirmations(), [paid])
        XCTAssertNil(ledger.takeBeat(), "+N never shows under a balance that hasn't moved")

        ledger.update(live: landed, loaded: true)
        XCTAssertEqual(ledger.takeBeat(), HomeBeat(snapshot: landed, deltas: ["sat": 2_500], confirmations: []))
    }

    func testRowThatNeverComesStopsBlockingLaterChanges() {
        var ledger = loaded(.onScreen)
        _ = ledger.receive(paid)
        _ = ledger.takeOverdueConfirmations()
        ledger.stopAwaitingRow()
        XCTAssertFalse(ledger.isAwaitingRow)
        XCTAssertNil(ledger.takeBeat(), "nothing moved, and the +N was dropped")

        let rolled = snapshot(rows: [("old", true)], sats: 3_500)
        ledger.update(live: rolled, loaded: true)
        XCTAssertEqual(ledger.takeBeat(), HomeBeat(snapshot: rolled, deltas: [:], confirmations: []))
    }

    func testBackToBackReceiptsSumIntoOneBeat() {
        var ledger = loaded(.onScreen)
        _ = ledger.receive(HomeReceipt(amount: 1_000, unit: "sat", homeOwned: true))
        _ = ledger.receive(HomeReceipt(amount: 1_500, unit: "sat", homeOwned: true))
        ledger.update(live: landed, loaded: true)
        let beat = ledger.takeBeat()
        XCTAssertEqual(beat?.deltas, ["sat": 2_500])
        XCTAssertEqual(beat?.confirmations.count, 2)
    }

    func testCoveredChangesHoldAndReplayWhenHomeReturns() {
        var ledger = loaded(.covered)
        ledger.update(live: landed, loaded: true)
        XCTAssertEqual(ledger.shown, base, "nothing moves under a cover")
        XCTAssertFalse(ledger.hasPendingBeat)

        XCTAssertEqual(ledger.receive(paid), [paid], "the haptic plays when the money lands")
        let inFlow = HomeReceipt(amount: 700, unit: "sat", homeOwned: false)
        XCTAssertEqual(ledger.receive(inFlow), [], "the sheet confirmed it")

        XCTAssertEqual(ledger.setPresence(.onScreen), [])
        let beat = ledger.takeBeat()
        XCTAssertEqual(
            beat,
            HomeBeat(snapshot: landed, deltas: ["sat": 2_500], confirmations: []),
            "the replay shows +N only for what no sheet confirmed, and doesn't buzz twice"
        )
    }

    func testSendReplaysWithoutPlusN() {
        var ledger = loaded(.covered)
        let sent = snapshot(rows: [("send", false), ("old", true)], sats: 400)
        ledger.update(live: sent, loaded: true)
        _ = ledger.setPresence(.onScreen)
        XCTAssertEqual(ledger.takeBeat(), HomeBeat(snapshot: sent, deltas: [:], confirmations: []))
    }

    func testReplayDropsPlusNWhereTheBalanceFell() {
        var ledger = loaded(.covered)
        _ = ledger.receive(HomeReceipt(amount: 13, unit: "sat", homeOwned: true))
        // The receipt landed during a send of 14, so the balance fell by 1.
        let sentAndPaid = snapshot(rows: [("send", false), ("paid", true), ("old", true)], sats: 999)
        ledger.update(live: sentAndPaid, loaded: true)
        _ = ledger.setPresence(.onScreen)
        XCTAssertEqual(ledger.takeBeat(), HomeBeat(snapshot: sentAndPaid, deltas: [:], confirmations: []))
    }

    func testCoveringHomeBeforeItsBeatConfirmsNowAndReplaysLater() {
        var ledger = loaded(.onScreen)
        _ = ledger.receive(paid)
        XCTAssertEqual(ledger.setPresence(.covered), [paid])

        ledger.update(live: landed, loaded: true)
        _ = ledger.setPresence(.onScreen)
        XCTAssertEqual(ledger.takeBeat(), HomeBeat(snapshot: landed, deltas: ["sat": 2_500], confirmations: []))
    }

    func testHistoryTabTakesChangesSilently() {
        var ledger = loaded(.covered)
        _ = ledger.receive(paid)
        _ = ledger.setPresence(.seenElsewhere)
        ledger.update(live: landed, loaded: true)
        XCTAssertEqual(ledger.shown, landed)
        XCTAssertEqual(ledger.receive(paid), [paid])

        _ = ledger.setPresence(.covered)
        _ = ledger.setPresence(.onScreen)
        XCTAssertFalse(ledger.hasPendingBeat, "History already showed it arrive")
        XCTAssertNil(ledger.takeBeat())
    }

    func testPlusNIsKeptPerUnit() {
        var ledger = loaded(.onScreen)
        let euros = HomeReceipt(amount: 500, unit: "EUR", homeOwned: true)
        _ = ledger.receive(euros)
        _ = ledger.receive(paid)
        var withEuros = landed
        withEuros.balancesByUnit["eur"] = 500
        ledger.update(live: withEuros, loaded: true)
        let beat = ledger.takeBeat()
        XCTAssertEqual(beat?.deltas, ["eur": 500, "sat": 2_500])
        XCTAssertEqual(beat?.confirmations, [euros, paid])
    }

    func testAnnouncementSumsEachUnit() {
        XCTAssertEqual(HomeReceipt.announcement(for: [paid], hideBalances: false), "Received 2,500 sat")
        XCTAssertEqual(
            HomeReceipt.announcement(for: [
                HomeReceipt(amount: 1_000, unit: "sat", homeOwned: true),
                HomeReceipt(amount: 1_500, unit: "SAT", homeOwned: true),
            ], hideBalances: false),
            "Received 2,500 sat"
        )
    }

    func testHiddenAnnouncementConfirmsPaymentWithoutAmount() {
        XCTAssertEqual(HomeReceipt.announcement(for: [paid], hideBalances: true), "Payment received")
    }

    func testHiddenAnnouncementIsTheSameForMultipleCurrencies() {
        let receipts = [paid, HomeReceipt(amount: 789, unit: "usd", homeOwned: true)]
        XCTAssertEqual(HomeReceipt.announcement(for: receipts, hideBalances: true), "Payment received")
    }

    func testAnnouncementUsesCurrentVisibilityForTheSameReceipts() {
        let receipts = [HomeReceipt(amount: 789, unit: "usd", homeOwned: true)]
        let visible = HomeReceipt.announcement(for: receipts, hideBalances: false)
        XCTAssertEqual(visible, "Received $7.89")
        XCTAssertEqual(HomeReceipt.announcement(for: receipts, hideBalances: true), "Payment received")
        XCTAssertEqual(HomeReceipt.announcement(for: receipts, hideBalances: false), visible)
    }

    private func loaded(_ presence: HomePresence) -> HomeArrivalLedger {
        var ledger = HomeArrivalLedger(presence: presence)
        ledger.update(live: base, loaded: true)
        return ledger
    }
}

@MainActor
final class HomeArrivalCoordinatorTests: XCTestCase {
    func testPiecesLandingSeparatelyPlayAsOneBeat() async {
        let coordinator = HomeArrivalCoordinator(quiet: .milliseconds(30), rowWait: .seconds(1))
        var beats: [HomeBeat] = []
        let played = expectation(description: "beat played")
        coordinator.onBeat = { beat in
            beats.append(beat)
            coordinator.present(beat)
            played.fulfill()
        }
        coordinator.setPresence(.onScreen)
        coordinator.update(live: snapshot(rows: [("old", true)], sats: 1_000), rows: [], loaded: true)

        // Notification, then balance, then the row, as the mint-quote sync
        // publishes them.
        coordinator.receive(HomeReceipt(amount: 2_500, unit: "sat", homeOwned: true))
        coordinator.update(live: snapshot(rows: [("old", true)], sats: 3_500), rows: [], loaded: true)
        try? await Task.sleep(for: .milliseconds(80))
        XCTAssertTrue(beats.isEmpty, "the beat waits for the row")
        let landed = snapshot(rows: [("new", true), ("old", true)], sats: 3_500)
        coordinator.update(live: landed, rows: [], loaded: true)

        await fulfillment(of: [played], timeout: 2)
        try? await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(beats, [HomeBeat(
            snapshot: landed,
            deltas: ["sat": 2_500],
            confirmations: [HomeReceipt(amount: 2_500, unit: "sat", homeOwned: true)]
        )])
        XCTAssertEqual(coordinator.shown, landed)
    }

    func testLateRowConfirmsOnTimeAndBeatsWhenItLands() async {
        let coordinator = HomeArrivalCoordinator(quiet: .milliseconds(20), rowWait: .milliseconds(60))
        var beats: [HomeBeat] = []
        var confirmed: [[HomeReceipt]] = []
        coordinator.onBeat = { beats.append($0); coordinator.present($0) }
        coordinator.onConfirm = { confirmed.append($0) }
        coordinator.setPresence(.onScreen)
        coordinator.update(live: snapshot(rows: [("old", true)], sats: 1_000), rows: [], loaded: true)

        let paid = HomeReceipt(amount: 2_500, unit: "sat", homeOwned: true)
        coordinator.receive(paid)
        try? await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(confirmed, [[paid]], "the haptic doesn't wait on a slow mint")
        XCTAssertTrue(beats.isEmpty)

        let landed = snapshot(rows: [("new", true), ("old", true)], sats: 3_500)
        coordinator.update(live: landed, rows: [], loaded: true)
        try? await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(beats, [HomeBeat(snapshot: landed, deltas: ["sat": 2_500], confirmations: [])])
        XCTAssertEqual(confirmed.count, 1)
    }
}

final class CashuRequestActivityTests: XCTestCase {
    func testRequestSortsByItsLatestPayment() {
        let created = Date(timeIntervalSince1970: 1_000)
        let unpaid = CashuRequest(encoded: "creqA", createdAt: created)
        XCTAssertEqual(unpaid.lastActivityAt(transactionDates: [:]), created)

        let linked = Date(timeIntervalSince1970: 5_000)
        let paidAt = Date(timeIntervalSince1970: 4_000)
        let paid = CashuRequest(
            encoded: "creqA",
            createdAt: created,
            receivedPayments: [
                CashuRequestPayment(transactionId: "tx1", amount: 10, receivedAt: linked),
                CashuRequestPayment(transactionId: "tx2", amount: 10, receivedAt: Date(timeIntervalSince1970: 2_000)),
            ]
        )
        XCTAssertEqual(paid.lastActivityAt(transactionDates: [:]), linked)
        XCTAssertEqual(
            paid.lastActivityAt(transactionDates: ["tx1": paidAt]),
            paidAt,
            "the wallet's own payment time wins over when it was linked"
        )
    }
}

private func snapshot(rows: [(String, Bool)], sats: UInt64) -> HomeSnapshot {
    HomeSnapshot(
        rows: rows.map { HomeSnapshot.Row(id: $0.0, isIncoming: $0.1) },
        balancesByUnit: ["sat": sats],
        satBalance: sats
    )
}
