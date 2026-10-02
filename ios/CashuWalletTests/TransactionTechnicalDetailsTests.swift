import Cdk
import XCTest
@testable import CashuWallet

final class TransactionTechnicalDetailsTests: XCTestCase {
    private let cdkId = String(repeating: "a", count: 64)
    private let proof = String(repeating: "b", count: 64)
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    private func transaction(
        id: String? = nil,
        type: WalletTransaction.TransactionType = .incoming,
        kind: WalletTransaction.TransactionKind = .lightning,
        status: WalletTransaction.TransactionStatus = .completed
    ) -> WalletTransaction {
        var tx = WalletTransaction(
            id: id ?? cdkId, amount: 21, type: type, kind: kind,
            date: date, memo: nil, status: status
        )
        tx.mintUrl = "https://mint.example.com"
        return tx
    }

    private func labels(_ details: TransactionTechnicalDetails, _ title: String) -> [String] {
        details.sections.first { $0.title == title }?.rows.map(\.label) ?? []
    }

    private func row(_ details: TransactionTechnicalDetails, _ label: String) -> TransactionTechnicalDetails.Row? {
        details.sections.flatMap(\.rows).first { $0.label == label }
    }

    func testSettledBolt11MintShowsTransactionQuoteAndProof() {
        var tx = transaction()
        tx.quoteId = "quote-bolt11-0000000001"
        tx.invoice = "lnbc210n1pexampleinvoicewithalongtail"
        tx.paymentMethod = .bolt11
        tx.preimage = proof
        tx.sagaId = "5f1c2b3a-0000-4000-8000-00000000abcd"
        let quote = TransactionTechnicalDetails.MintQuoteSnapshot(
            request: "lnbc210n1pexampleinvoicewithalongtail", state: "Issued", paymentMethod: .bolt11,
            amountPaid: 21, amountIssued: 21, expiry: 1_790_000_600, updatedAt: 1_790_000_010
        )

        let details = TransactionTechnicalDetails(transaction: tx, mintQuote: quote)

        XCTAssertEqual(details.sections.map(\.title), ["Transaction", "Quote", "Payment"])
        XCTAssertEqual(labels(details, "Transaction"),
                       ["ID", "Method", "Direction", "Status", "Date", "Amount", "Fee", "Mint", "Saga ID"])
        XCTAssertEqual(labels(details, "Quote"),
                       ["Quote ID", "Type", "Request", "State", "Amount paid", "Amount issued", "Expiry", "Last updated"])
        XCTAssertEqual(labels(details, "Payment"), ["Payment Proof"])
        XCTAssertEqual(row(details, "ID")?.value, "aaaaaaaa…aaaaaa")
        XCTAssertEqual(row(details, "ID")?.fullValue, cdkId)
        XCTAssertEqual(row(details, "ID")?.isCopyable, true)
        XCTAssertEqual(row(details, "Method")?.value, "Lightning (BOLT11)")
        XCTAssertEqual(row(details, "Type")?.value, "Mint quote")
        XCTAssertEqual(row(details, "Amount")?.value, "21 sat")
        XCTAssertEqual(row(details, "Fee")?.value, "0 sat")
        XCTAssertEqual(row(details, "Mint")?.value, "https://mint.example.com")
        XCTAssertEqual(row(details, "Date")?.fullValue, "2026-09-21T14:13:20Z")
        XCTAssertEqual(row(details, "Payment Proof")?.fullValue, proof)
    }

    func testUnpaidInvoiceRowHasNoCdkIdOrProof() {
        var tx = transaction(id: "quote-pending", status: .pending)
        tx.quoteId = "quote-pending"
        tx.invoice = "lnbc1pendinginvoice"
        tx.isUnpaidInvoice = true

        let details = TransactionTechnicalDetails(transaction: tx)

        XCTAssertEqual(details.sections.map(\.title), ["Transaction", "Quote"])
        XCTAssertFalse(labels(details, "Transaction").contains("ID"))
        XCTAssertEqual(row(details, "Method")?.value, "Lightning (BOLT11)")
        XCTAssertEqual(labels(details, "Quote"), ["Quote ID", "Type", "Request"])
    }

    func testBolt12OfferResolvesMethodFromQuoteAndNeverExpires() {
        var tx = transaction()
        tx.quoteId = "offer-quote"
        tx.invoice = "lno1qexampleoffer"
        let quote = TransactionTechnicalDetails.MintQuoteSnapshot(
            request: "lno1qexampleoffer", state: "Paid", paymentMethod: .bolt12,
            amountPaid: 42, amountIssued: 21, expiry: 253_402_300_799, updatedAt: 0
        )

        let details = TransactionTechnicalDetails(transaction: tx, mintQuote: quote)

        XCTAssertEqual(row(details, "Method")?.value, "Lightning (BOLT12)")
        XCTAssertEqual(row(details, "Expiry")?.value, "Never")
        XCTAssertEqual(row(details, "Amount paid")?.value, "42 sat")
        XCTAssertEqual(row(details, "Amount issued")?.value, "21 sat")
        XCTAssertNil(row(details, "Last updated"))
    }

    func testPendingOnchainDepositCarriesConfirmationsAndTxid() {
        var tx = transaction(id: "deposit-quote", kind: .onchain, status: .pending)
        tx.quoteId = "deposit-quote"
        tx.invoice = "bc1qexampledepositaddress000000"
        tx.preimage = proof
        tx.statusNote = "Payment confirmed on-chain (45 confirmations)"
        let quote = TransactionTechnicalDetails.MintQuoteSnapshot(
            request: "bc1qexampledepositaddress000000", state: "Paid", paymentMethod: .onchain,
            amountPaid: 300_000, amountIssued: 0, expiry: 0, updatedAt: 1_790_000_000
        )

        let details = TransactionTechnicalDetails(transaction: tx, mintQuote: quote)

        XCTAssertEqual(row(details, "Method")?.value, "On-chain")
        XCTAssertEqual(row(details, "Status")?.value, "Pending")
        XCTAssertEqual(row(details, "Status detail")?.value, "Payment confirmed on-chain (45 confirmations)")
        XCTAssertEqual(row(details, "Amount paid")?.value, "300,000 sat")
        XCTAssertEqual(row(details, "Amount issued")?.value, "0 sat")
        XCTAssertEqual(row(details, "Expiry")?.value, "Never")
        XCTAssertEqual(labels(details, "Payment"), ["Transaction ID"])
        XCTAssertEqual(row(details, "Transaction ID")?.fullValue, proof)
    }

    func testCompletedOnchainDepositOmitsStatusDetail() {
        var tx = transaction(kind: .onchain)
        tx.quoteId = "deposit-quote"
        tx.invoice = "bc1qexampledepositaddress000000"
        tx.paymentMethod = .onchain
        tx.statusNote = "stale note"

        let details = TransactionTechnicalDetails(transaction: tx)

        XCTAssertEqual(row(details, "Status")?.value, "Completed")
        XCTAssertNil(row(details, "Status detail"))
        XCTAssertEqual(details.sections.map(\.title), ["Transaction", "Quote"])
    }

    func testMeltShowsQuoteAmountsAndFallsBackToQuoteProof() {
        var tx = transaction(type: .outgoing)
        tx.quoteId = "melt-quote"
        tx.invoice = "lnbc500n1pmeltinvoice"
        tx.paymentMethod = .bolt11
        tx.fee = 2
        let quote = TransactionTechnicalDetails.MeltQuoteSnapshot(
            request: "lnbc500n1pmeltinvoice", state: "Paid", paymentMethod: .bolt11,
            amount: 50, feeReserve: 4, expiry: 1_790_000_600, paymentProof: proof
        )

        let details = TransactionTechnicalDetails(transaction: tx, meltQuote: quote)

        XCTAssertEqual(row(details, "Type")?.value, "Melt quote")
        XCTAssertEqual(labels(details, "Quote"),
                       ["Quote ID", "Type", "Request", "State", "Quote amount", "Fee reserve", "Expiry"])
        XCTAssertEqual(row(details, "Fee")?.value, "2 sat")
        XCTAssertEqual(row(details, "Fee reserve")?.value, "4 sat")
        XCTAssertEqual(row(details, "Payment Proof")?.fullValue, proof)
    }

    func testMeltWithoutProofHasNoPaymentSection() {
        var tx = transaction(type: .outgoing, status: .pending)
        tx.quoteId = "melt-quote"
        tx.invoice = "lnbc500n1pmeltinvoice"

        let details = TransactionTechnicalDetails(transaction: tx)

        XCTAssertEqual(details.sections.map(\.title), ["Transaction", "Quote"])
        XCTAssertEqual(row(details, "Type")?.value, "Melt quote")
    }

    func testEcashSendNeverCopiesItsToken() {
        let token = "cashuBspendabletokenvalue0123456789"
        var tx = transaction(type: .outgoing, kind: .ecash, status: .pending)
        tx.token = token
        tx.sagaId = "5f1c2b3a-0000-4000-8000-00000000abcd"

        let details = TransactionTechnicalDetails(transaction: tx)

        XCTAssertEqual(details.sections.map(\.title), ["Transaction"])
        XCTAssertEqual(row(details, "Method")?.value, "Ecash")
        XCTAssertEqual(row(details, "Direction")?.value, "Outgoing")
        XCTAssertFalse(details.copyAllText.contains(token))
        XCTAssertFalse(details.sections.flatMap(\.rows).contains { $0.fullValue.contains(token) })
    }

    func testHeldEcashReceiveHasNoCdkId() {
        var tx = transaction(id: "token-hash", kind: .ecash, status: .pending)
        tx.token = "cashuBheldtoken"
        tx.isPendingReceiveToken = true
        tx.statusNote = "Not claimed yet"

        let details = TransactionTechnicalDetails(transaction: tx)

        XCTAssertFalse(labels(details, "Transaction").contains("ID"))
        XCTAssertEqual(row(details, "Status detail")?.value, "Not claimed yet")
        XCTAssertFalse(details.copyAllText.contains("cashuBheldtoken"))
    }

    func testStoredQuoteSecretKeyNeverReachesDetails() {
        let secret = String(repeating: "5", count: 64)
        let stored = MintQuote(
            id: "deposit-quote", amount: nil, unit: .sat, request: "bc1qexampledeposit", state: .paid,
            expiry: 0, mintUrl: MintUrl(url: "https://mint.example.com"),
            amountIssued: Amount(value: 0), amountPaid: Amount(value: 300_000), updatedAt: 1,
            estimatedBlocks: nil, paymentMethod: .onchain, secretKey: secret,
            usedByOperation: nil, version: 3
        )
        var tx = transaction(id: "deposit-quote", kind: .onchain, status: .pending)
        tx.quoteId = "deposit-quote"
        tx.invoice = "bc1qexampledeposit"

        let details = TransactionTechnicalDetails(
            transaction: tx,
            mintQuote: TransactionTechnicalDetails.MintQuoteSnapshot(stored)
        )

        XCTAssertEqual(row(details, "State")?.value, "Paid")
        XCTAssertFalse(details.copyAllText.contains(secret))
        XCTAssertFalse(details.sections.flatMap(\.rows).contains { $0.value.contains(secret) || $0.fullValue.contains(secret) })
    }

    func testCopyAllIsSectionedLabelValueTextWithFullValues() {
        var tx = transaction()
        tx.quoteId = "quote-bolt11-0000000001"
        tx.invoice = "lnbc210n1pexampleinvoicewithalongtail"
        tx.paymentMethod = .bolt11

        let text = TransactionTechnicalDetails(transaction: tx).copyAllText

        XCTAssertTrue(text.hasPrefix("Transaction\nID: \(cdkId)\nMethod: Lightning (BOLT11)\n"))
        XCTAssertTrue(text.contains("\n\nQuote\nQuote ID: quote-bolt11-0000000001\nType: Mint quote\n"))
        XCTAssertTrue(text.contains("Request: lnbc210n1pexampleinvoicewithalongtail"))
        XCTAssertTrue(text.contains("Date: 2026-09-21T14:13:20Z"))
    }

    func testCopyConfirmationsNameTheValue() {
        XCTAssertEqual(TransactionTechnicalDetails.copyConfirmation(for: "ID"), "Copied ID")
        XCTAssertEqual(TransactionTechnicalDetails.copyConfirmation(for: "Quote ID"), "Copied quote ID")
        XCTAssertEqual(TransactionTechnicalDetails.copyConfirmation(for: "Mint"), "Copied mint URL")
        XCTAssertEqual(TransactionTechnicalDetails.copyConfirmation(for: "Payment Proof"), "Copied payment proof")
        XCTAssertEqual(TransactionTechnicalDetails.copyConfirmation(for: "Transaction ID"), "Copied transaction ID")
    }
}
