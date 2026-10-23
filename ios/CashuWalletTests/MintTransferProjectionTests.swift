import XCTest
@testable import CashuWallet

/// Android parity: `MintTransferProjectionTest.kt`.
final class MintTransferProjectionTests: XCTestCase {
    private let source = "https://source.example"
    private let destination = "https://destination.example"

    func testCompletedTransferShowsAsOneNeutralRow() {
        let rows = project([payment(), receipt()], [record(.completed)])

        XCTAssertEqual(rows.map(\.id), ["payment"])
        let row = rows[0]
        XCTAssertEqual(row.displayTitle, "Transfer")
        XCTAssertEqual(row.status, .completed)
        XCTAssertEqual(row.type, .outgoing, "An outgoing row is unsigned and never green")
        XCTAssertEqual(row.amount, 40)
        XCTAssertEqual(row.fee, 2)
        XCTAssertEqual(row.transfer?.sourceMintURL, source)
        XCTAssertEqual(row.transfer?.destinationMintURL, destination)
    }

    /// The invoice was the wallet's own, so the row must never offer it as a
    /// code to scan, copy or pay.
    func testTransferRowCarriesNoPaymentCodeOrInvoiceDescription() {
        var pending = payment(status: .pending)
        pending.memo = "Destination mint boilerplate"

        let row = project([pending], [record(.committed)])[0]

        XCTAssertNil(row.invoice)
        XCTAssertNil(row.displayDescription)
        XCTAssertFalse(row.hasActionablePaymentCode)
    }

    func testPaidButNotYetIssuedReadsAsArriving() {
        let row = project([payment()], [record(.committed)])[0]

        XCTAssertEqual(row.status, .pending)
        XCTAssertEqual(row.displayStatusText, "Arriving at Destination")
        XCTAssertEqual(row.mintQuoteIdForStatusRefresh, "mint-quote")
    }

    func testPaymentStillSettlingReadsAsInProgress() {
        let row = project([payment(status: .pending)], [record(.committed)])[0]

        XCTAssertEqual(row.status, .pending)
        XCTAssertEqual(row.displayStatusText, "Payment in progress")
    }

    func testFailedPaymentReadsAsAFailedTransfer() {
        let row = project([payment(status: .failed)], [record(.failed)])[0]

        XCTAssertEqual(row.displayTitle, "Transfer")
        XCTAssertEqual(row.status, .failed)
        XCTAssertNil(row.mintQuoteIdForStatusRefresh)
    }

    /// A sweep can issue a transfer the wallet had written off.
    func testIssuedReceiptCompletesEvenAFailedRecord() {
        let rows = project([payment(status: .failed), receipt()], [record(.failed)])

        XCTAssertEqual(rows.map(\.id), ["payment"])
        XCTAssertEqual(rows[0].status, .completed)
    }

    func testRowsWithoutARecordAreLeftAsCDKWroteThem() {
        let rows = project([payment(), receipt()], [])

        XCTAssertEqual(rows.map(\.displayTitle), ["Lightning paid", "Lightning received"])
    }

    func testDraftRecordChangesNothing() {
        let rows = project([payment(), receipt()], [record(.draft)])

        XCTAssertEqual(rows.map(\.displayTitle), ["Lightning paid", "Lightning received"])
    }

    /// Without the source payment there is nothing to anchor the transfer on,
    /// so the receipt stays visible rather than the money disappearing.
    func testReceiptStaysWhenTheSourcePaymentIsMissing() {
        let rows = project([receipt()], [record(.completed)])

        XCTAssertEqual(rows.map(\.displayTitle), ["Lightning received"])
    }

    func testSameQuoteIDAtAnotherMintIsUntouched() {
        var stranger = receipt()
        stranger.mintUrl = "https://other.example"

        let rows = project([payment(), stranger], [record(.completed)])

        XCTAssertEqual(rows.map(\.displayTitle), ["Transfer", "Lightning received"])
    }

    func testProjectingTwiceChangesNothingMore() {
        let once = project([payment(), receipt()], [record(.committed)])
        let twice = project(once, [record(.committed)])

        XCTAssertEqual(twice.map(\.id), once.map(\.id))
        XCTAssertEqual(twice.map(\.status), once.map(\.status))
        XCTAssertEqual(twice.map(\.transfer), once.map(\.transfer))
    }

    /// After a failed read the source row is the one folded on the last load,
    /// while the destination's receipt may be freshly read. The receipt is
    /// still the transfer's, and an "Arriving" row is not mistaken for a
    /// payment that is still in progress.
    func testRowFoldedOnAnEarlierLoadStaysFoldedAndAbsorbsAFreshReceipt() {
        let arriving = project([payment()], [record(.committed)])

        let stillArriving = project(arriving, [record(.committed)])
        XCTAssertEqual(stillArriving[0].displayStatusText, "Arriving at Destination")

        let completed = project(arriving + [receipt()], [record(.committed)])
        XCTAssertEqual(completed.map(\.id), ["payment"])
        XCTAssertEqual(completed[0].status, .completed)
    }

    func testHomeRecentShowsACompletedTransferOnce() {
        let rows = project([payment(), receipt()], [record(.completed)])

        XCTAssertEqual(HomeActivity.recentTransactions(from: rows, limit: 5).map(\.displayTitle), ["Transfer"])
    }

    func testTransferIsFoundBySearchingItsTitle() {
        let row = project([payment(), receipt()], [record(.completed)])[0]

        XCTAssertTrue(HistorySearch.matches(query: "transfer", transaction: row))
    }

    /// Rows and the detail title name where the ecash went, so a list of
    /// transfers can be told apart.
    func testTransferTitleNamesTheDestination() {
        let row = project([payment(), receipt()], [record(.completed)])[0]
        let mints = [
            MintInfo(url: source, name: "antifiat mint", isActive: true, balance: 0),
            MintInfo(url: destination, name: "macadamia Mint", isActive: true, balance: 40),
        ]

        XCTAssertEqual(row.displayTitle(mints: mints), "Transfer to macadamia Mint")
        XCTAssertTrue(HistorySearch.matches(query: "macadamia", transaction: row, mints: mints))
    }

    /// A destination the wallet no longer holds is still named, by its host.
    func testTransferTitleFallsBackToTheDestinationHost() {
        let row = project([payment(), receipt()], [record(.completed)])[0]

        XCTAssertEqual(row.displayTitle(mints: []), "Transfer to destination.example")
    }

    func testOtherRowsKeepTheirTitleWhenMintsAreKnown() {
        let rows = project([payment(), receipt()], [])

        XCTAssertEqual(rows.map { $0.displayTitle(mints: []) }, ["Lightning paid", "Lightning received"])
    }

    // MARK: - Fixtures

    private func project(_ rows: [WalletTransaction], _ records: [MintTransferRecord]) -> [WalletTransaction] {
        MintTransferProjection.project(rows, records: records) { url in
            url == self.destination ? "Destination" : "Source"
        }
    }

    private func payment(status: WalletTransaction.TransactionStatus = .completed) -> WalletTransaction {
        var row = WalletTransaction(
            id: "payment", amount: 40, type: .outgoing, kind: .lightning,
            date: Date(timeIntervalSince1970: 100), status: status, mintUrl: source, invoice: "lnbc400n1transfer"
        )
        row.fee = 2
        row.quoteId = "melt-quote"
        row.paymentMethod = .bolt11
        return row
    }

    private func receipt() -> WalletTransaction {
        var row = WalletTransaction(
            id: "receipt", amount: 40, type: .incoming, kind: .lightning,
            date: Date(timeIntervalSince1970: 101), status: .completed, mintUrl: destination,
            invoice: "lnbc400n1transfer"
        )
        row.quoteId = "mint-quote"
        row.paymentMethod = .bolt11
        return row
    }

    private func record(_ state: MintTransferRecord.State) -> MintTransferRecord {
        MintTransferRecord(
            id: "transfer", sourceMintURL: source, destinationMintURL: destination,
            mintQuoteID: "mint-quote", meltQuoteID: "melt-quote",
            amount: 40, createdAt: Date(timeIntervalSince1970: 99), state: state
        )
    }
}
