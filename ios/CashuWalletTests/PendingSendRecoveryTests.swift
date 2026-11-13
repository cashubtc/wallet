import Cdk
import XCTest
@testable import CashuWallet

@MainActor
final class ContactlessSpendPolicyTests: XCTestCase {
    private var stored: Data?
    private var time = Date(timeIntervalSince1970: 86_400)
    private enum Fault: Error { case rejected, storage, write }
    private func policy() -> ContactlessSpendPolicy {
        ContactlessSpendPolicy(load: { self.stored }, save: { self.stored = $0 }, now: { self.time })
    }

    func testDefaultAndExactTapBoundary() async throws {
        let p = policy()
        XCTAssertEqual(try p.state().limits, ContactlessLimits(perTap: 1000, daily: 10000))
        try await p.authorizeAndRecord(total: 1000, isActive: { true }, authenticate: { XCTFail("Unexpected authentication"); return false })
        XCTAssertEqual(try p.state().spent, 1000)
    }

    func testLargerPaymentRequiresAuthentication() async throws {
        let p = policy()
        do { try await p.authorizeAndRecord(total: 1001, isActive: { true }, authenticate: { false }); XCTFail("Expected denial") }
        catch ContactlessPaymentError.authentication { }
        XCTAssertEqual(try p.state().spent, 0)
        try await p.authorizeAndRecord(total: 1001, isActive: { true }, authenticate: { true })
        XCTAssertEqual(try p.state().spent, 1001)
    }

    func testDailyCapIncludesTotalAndSurvivesRecreation() async throws {
        try await policy().authorizeAndRecord(total: 9999, isActive: { true }, authenticate: { true })
        do { try await policy().authorizeAndRecord(total: 2, isActive: { true }, authenticate: { true }); XCTFail("Expected cap") }
        catch ContactlessPaymentError.dailyLimit { }
        try await policy().authorizeAndRecord(total: 1, isActive: { true }, authenticate: { true })
        XCTAssertEqual(try policy().state().spent, 10000)
    }

    func testDayRolloverAndClockRollback() async throws {
        try await policy().authorizeAndRecord(total: 10000, isActive: { true }, authenticate: { true })
        time = Date(timeIntervalSince1970: 0)
        do { try await policy().authorizeAndRecord(total: 1, isActive: { true }, authenticate: { true }); XCTFail("Expected cap") }
        catch ContactlessPaymentError.dailyLimit { }
        time = Date(timeIntervalSince1970: 172800)
        try await policy().authorizeAndRecord(total: 1, isActive: { true }, authenticate: { true })
        XCTAssertEqual(try policy().state().spent, 1)
    }

    func testLimitsChangePreservesUsageAndZeroRequiresAuthentication() async throws {
        let p = policy()
        try await p.authorizeAndRecord(total: 1000, isActive: { true }, authenticate: { true })
        try p.saveLimits(ContactlessLimits(perTap: 0, daily: 2000))
        XCTAssertEqual(try p.state().spent, 1000)
        do { try await p.authorizeAndRecord(total: 1, isActive: { true }, authenticate: { false }); XCTFail("Expected denial") }
        catch ContactlessPaymentError.authentication { }
    }

    func testForegroundRecheckedAfterAuthentication() async throws {
        let p = policy()
        var active = true
        do {
            try await p.authorizeAndRecord(total: 1001, isActive: { active }, authenticate: { active = false; return true })
            XCTFail("Expected stopped payment")
        } catch ContactlessPaymentError.inactive { }
        XCTAssertEqual(try p.state().spent, 0)
    }

    func testCorruptStateAndPersistenceFailuresFailClosed() async throws {
        stored = Data("invalid".utf8)
        do { try await policy().authorizeAndRecord(total: 1, isActive: { true }, authenticate: { true }); XCTFail("Expected storage error") }
        catch ContactlessPaymentError.storage { }
        let p = ContactlessSpendPolicy(load: { nil }, save: { _ in throw Fault.storage }, now: { self.time })
        do { try await p.authorizeAndRecord(total: 1, isActive: { true }, authenticate: { true }); XCTFail("Expected storage error") }
        catch Fault.storage { }
    }

    func testInvalidAndOverflowSizedLimitsAreRejected() async throws {
        XCTAssertThrowsError(try policy().saveLimits(ContactlessLimits(perTap: 101, daily: 100)))
        XCTAssertThrowsError(try policy().saveLimits(ContactlessLimits(perTap: 0, daily: 0)))
        do { try await policy().authorizeAndRecord(total: UInt64.max, isActive: { true }, authenticate: { true }); XCTFail("Expected cap") }
        catch ContactlessPaymentError.dailyLimit { }
    }

    func testRejectedApprovalCancelsBeforeConfirm() async throws {
        var events: [String] = []
        do {
            _ = try await completeContactlessSend(
                approve: { events.append("approve"); throw Fault.rejected },
                confirm: { events.append("confirm"); return "token" },
                cancel: { events.append("cancel") }, reclaim: { events.append("reclaim") },
                deliver: { _ in events.append("deliver") })
            XCTFail("Expected denial")
        } catch Fault.rejected { }
        XCTAssertEqual(events, ["approve", "cancel"])
    }

    func testWriteFailureReclaimsOnce() async throws {
        var events: [String] = []
        do {
            _ = try await completeContactlessSend(
                approve: { events.append("approve") }, confirm: { events.append("confirm"); return "token" },
                cancel: { events.append("cancel") }, reclaim: { events.append("reclaim") },
                deliver: { _ in events.append("deliver"); throw Fault.write })
            XCTFail("Expected write failure")
        } catch Fault.write { }
        XCTAssertEqual(events, ["approve", "confirm", "deliver", "reclaim"])
    }

    func testSuccessDoesNotReclaim() async throws {
        let result = try await completeContactlessSend(approve: {}, confirm: { "token" },
            cancel: { XCTFail("cancel") }, reclaim: { XCTFail("reclaim") }, deliver: { XCTAssertEqual($0, "token") })
        XCTAssertEqual(result, "token")
    }

    func testConcurrentApprovalCannotSpendTheSameBudget() async throws {
        let p = policy()
        var resume: CheckedContinuation<Bool, Never>?
        let first = Task { try await p.authorizeAndRecord(total: 6000, isActive: { true }, authenticate: {
            await withCheckedContinuation { resume = $0 }
        }) }
        while resume == nil { await Task.yield() }
        do {
            try await p.authorizeAndRecord(total: 6000, isActive: { true }, authenticate: { true })
            XCTFail("Expected in-progress rejection")
        } catch ContactlessPaymentError.busy { }
        resume?.resume(returning: true)
        try await first.value
        XCTAssertEqual(try p.state().spent, 6000)
    }

    func testCancelledDeliveryStillRunsCleanup() async throws {
        var started = false
        var reclaimed = false
        let payment = Task {
            try await completeContactlessSend(approve: {}, confirm: { "token" },
                cancel: { XCTFail("Must reclaim the confirmed token") },
                reclaim: { try Task.checkCancellation(); reclaimed = true },
                deliver: { _ in started = true; try await Task.sleep(for: .seconds(60)) })
        }
        while !started { await Task.yield() }
        payment.cancel()
        do { _ = try await payment.value; XCTFail("Expected cancellation") }
        catch is CancellationError { }
        XCTAssertTrue(reclaimed)
    }
}

@MainActor
final class CashuRequestDeliveryRecoveryTests: PaymentFixtureTestCase {
    func testContactlessApprovalCancellationReleasesNativePreparation() async throws {
        let payer = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        let prepared = try await send(payer, amount: 21)
        do {
            _ = try await completeContactlessSend(
                approve: { throw ContactlessPaymentError.authentication },
                confirm: { try await prepared.confirm(memo: nil) },
                cancel: { try await prepared.cancel() },
                reclaim: { _ = try await payer.revokeSend(operationId: prepared.operationId()) },
                deliver: { _ in XCTFail("Must not deliver") })
            XCTFail("Expected cancelled approval")
        } catch ContactlessPaymentError.authentication { }
        let available = try await balance(payer)
        let pending = try await payer.getPendingSends()
        XCTAssertEqual(available, 100)
        XCTAssertTrue(pending.isEmpty)
    }

    func testContactlessWriteFailureReturnsNativeFundsAcrossReopen() async throws {
        let payer = try await makeWallet("controlled")
        let receiver = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        let prepared = try await send(payer, amount: 21)
        do {
            _ = try await completeContactlessSend(
                approve: {}, confirm: { try await prepared.confirm(memo: nil) },
                cancel: { try await prepared.cancel() },
                reclaim: { _ = try await payer.revokeSend(operationId: prepared.operationId()) },
                deliver: { _ in throw NFCPaymentError.nfcWriteFailed("Test write failure") })
            XCTFail("Expected write failure")
        } catch NFCPaymentError.nfcWriteFailed { }
        let pending = try await payer.getPendingSends()
        XCTAssertTrue(pending.isEmpty)
        let reopened = try await reopen(payer)
        let available = try await balance(reopened)
        XCTAssertEqual(available, 100)
        let token = try await send(reopened, amount: 100).confirm(memo: nil)
        let received = try await receive(receiver, token: token)
        XCTAssertEqual(received, 100)
    }

    func testContactlessSuccessfulDeliveryIsNotRevoked() async throws {
        let payer = try await makeWallet("controlled")
        let receiver = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        let prepared = try await send(payer, amount: 21)
        _ = try await completeContactlessSend(
            approve: {}, confirm: { try await prepared.confirm(memo: nil) },
            cancel: { XCTFail("Must not cancel") }, reclaim: { XCTFail("Must not reclaim") },
            deliver: { token in
                let received = try await self.receive(receiver, token: token)
                XCTAssertEqual(received, 21)
            })
        let available = try await balance(payer)
        XCTAssertEqual(available, 79)
    }

    private func pay(_ wallet: Wallet, target: String = "unavailable",
                     beforeReclaim: () async throws -> Void = {}) async throws {
        let encoded = try await call(root + "/request", method: "POST", body: [
            "target": fixture + root + "/" + target, "amount": 21, "mints": [wallet.mintUrl().url]
        ])["request"] as! String
        let request = try PaymentRequest.fromString(encoded: encoded)
        let context = PendingSendRecovery(mintURL: wallet.mintUrl().url, unit: "sat")
        let db = try database(of: wallet)
        try await performRecoverablePaymentRequest(
            context: context,
            inspect: { ids in
                var current = context
                current.operationIDs = ids
                let pending = try await wallet.getPendingSends()
                let sagas = try await db.getIncompleteSagas()
                let transactions = try await wallet.listTransactions(direction: .outgoing)
                return try resolvePendingSendRecovery(
                    context: current, mintURL: current.mintURL, sagaJSON: sagas, pendingIDs: pending,
                    transactions: Dictionary(uniqueKeysWithValues: transactions.map {
                        ($0.id.hex, $0.status == .completed ? SendRecoveryTransactionStatus.completed
                            : $0.status == .pending ? .pending : .failed)
                    }))
            },
            recover: { _ = try await wallet.recoverIncompleteSagas() },
            prepare: { try await wallet.preparePayRequest(paymentRequest: request, customAmount: nil) },
            reclaim: { id in
                try await beforeReclaim()
                _ = try await wallet.revokeSend(operationId: id)
            }
        )
    }

    func testFailedDeliveryReturnsSpendableFundsAcrossReopen() async throws {
        let payer = try await makeWallet("controlled")
        let recipient = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        do { try await pay(payer); XCTFail("Expected returned payment") }
        catch is CashuRequestDeliveryReturned { }
        let pending = try await payer.getPendingSends()
        XCTAssertTrue(pending.isEmpty)
        let receipts = try await payer.listTransactions(direction: .outgoing)
        XCTAssertEqual(receipts.count, 1)
        XCTAssertEqual(receipts.first?.status, .failed)
        let reopened = try await reopen(payer)
        let available = try await balance(reopened)
        XCTAssertEqual(available, 100)
        let token = try await send(reopened, amount: 100).confirm(memo: nil)
        let credited = try await receive(recipient, token: token)
        XCTAssertEqual(credited, 100)
    }

    func testFailedAcknowledgementInvalidatesUnclaimedDeliveredToken() async throws {
        let payer = try await makeWallet("controlled")
        let recipient = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        do { try await pay(payer, target: "receive?fail=true"); XCTFail("Expected returned payment") }
        catch is CashuRequestDeliveryReturned { }
        let encoded = try await call(root + "/received-token")["token"] as! String
        let token = try Token.decode(encodedToken: encoded)
        do { _ = try await receive(recipient, token: token); XCTFail("Revoked token must not redeem") }
        catch { }
        let senderBalance = try await balance(payer)
        let receiverBalance = try await balance(recipient)
        XCTAssertEqual(senderBalance, 100)
        XCTAssertEqual(receiverBalance, 0)
    }

    func testRecipientClaimBeforeReclaimIsCompletedNotRefunded() async throws {
        let payer = try await makeWallet("controlled")
        let recipient = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        do {
            try await pay(payer, target: "receive?fail=true") {
                let encoded = try await self.call(self.root + "/received-token")["token"] as! String
                let credited = try await self.receive(recipient, token: Token.decode(encodedToken: encoded))
                XCTAssertEqual(credited, 21)
            }
            XCTFail("Expected completed recovery")
        } catch let recovery as PendingSendRecovery { XCTAssertEqual(recovery.outcome, .completed) }
        let senderBalance = try await balance(payer)
        let receiverBalance = try await balance(recipient)
        XCTAssertEqual(senderBalance, 79)
        XCTAssertEqual(receiverBalance, 21)
    }

    func testFailedReclaimRemainsPendingAndBlocksRetry() async throws {
        let payer = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        do {
            try await pay(payer) { try await self.arm("/v1/swap", action: "reject", count: 20) }
            XCTFail("Expected pending recovery")
        } catch let recovery as PendingSendRecovery { XCTAssertEqual(recovery.outcome, .pending) }
        let pending = try await payer.getPendingSends()
        do { try await pay(payer); XCTFail("Expected retry blocked") }
        catch let recovery as PendingSendRecovery { XCTAssertEqual(recovery.outcome, .pending) }
        let after = try await payer.getPendingSends()
        let available = try await balance(payer)
        XCTAssertEqual(after, pending)
        XCTAssertEqual(available, 79)
    }

    func testExistingSharedTokenIsNotReclaimed() async throws {
        let payer = try await makeWallet("controlled")
        let recipient = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        let token = try await send(payer, amount: 21).confirm(memo: nil)
        do { try await pay(payer); XCTFail("Expected existing pending send") }
        catch let recovery as PendingSendRecovery { XCTAssertEqual(recovery.outcome, .pending) }
        let credited = try await receive(recipient, token: token)
        XCTAssertEqual(credited, 21)
    }

    func testSuccessfulDeliveryIsNotReclaimed() async throws {
        let payer = try await makeWallet("controlled")
        let recipient = try await makeWallet("controlled")
        try await fund(payer, controlled: "controlled")
        try await pay(payer, target: "receive")
        let encoded = try await call(root + "/received-token")["token"] as! String
        let credited = try await receive(recipient, token: Token.decode(encodedToken: encoded))
        let available = try await balance(payer)
        XCTAssertEqual(credited, 21)
        XCTAssertEqual(available, 79)
    }
}

@MainActor
final class PendingSendRecoveryTests: XCTestCase {
    private enum Fault: Error { case transport, database }
    private let account = PendingSendRecovery(mintURL: "https://mint.example", unit: "sat")

    private func snapshot(_ outcome: PendingSendRecovery.Outcome, ids: [String] = []) -> PendingSendRecovery {
        var value = account
        value.outcome = outcome
        value.operationIDs = ids
        if outcome == .pending { value.sends = [PendingSend(id: "send-1", amount: 21, token: "test-token")] }
        return value
    }

    func testPendingSendBlocksPreparationAndPreservesToken() async {
        var prepares = 0
        do {
            let _: Int = try await performRecoverableSend(
                context: account,
                inspect: { _ in self.snapshot(.pending, ids: ["send-1"]) },
                recover: { XCTFail("Preflight must not reclaim") },
                send: { _ in prepares += 1; return 21 }
            )
            XCTFail("Expected recovery")
        } catch let recovery as PendingSendRecovery {
            XCTAssertEqual(recovery.sends.first?.token, "test-token")
            XCTAssertFalse(String(reflecting: recovery).contains("test-token"))
            XCTAssertEqual(recovery.operationIDs, ["send-1"])
        } catch { XCTFail("Unexpected error") }
        XCTAssertEqual(prepares, 0)
    }

    func testFailureAfterTokenCreationAndRepeatedRetryPrepareOnlyOnce() async {
        var pending = false
        var prepares = 0
        for _ in 0..<2 {
            do {
                let _: Int = try await performRecoverableSend(
                    context: account,
                    inspect: { ids in self.snapshot(pending ? .pending : .returned, ids: pending ? ["send-1"] : ids) },
                    recover: {},
                    send: { remember in
                        prepares += 1
                        remember("send-1")
                        pending = true
                        throw Fault.transport
                    }
                )
                XCTFail("Expected pending state")
            } catch let recovery as PendingSendRecovery {
                XCTAssertEqual(recovery.outcome, .pending)
                XCTAssertEqual(recovery.operationIDs, ["send-1"])
            } catch { XCTFail("Unexpected error") }
        }
        XCTAssertEqual(prepares, 1)
    }

    func testCompensatedProofReservationAllowsFreshAttempt() async throws {
        var prepares = 0
        var recovered = false
        do {
            let _: Int = try await performRecoverableSend(
                context: account, inspect: { _ in self.snapshot(.returned) },
                recover: { recovered = true },
                send: { remember in prepares += 1; remember("reserved"); throw Fault.transport }
            )
            XCTFail("Expected original failure")
        } catch Fault.transport {} catch { XCTFail("Expected retryable failure") }
        XCTAssertTrue(recovered)
        let value = try await performRecoverableSend(
            context: account, inspect: { _ in self.snapshot(.returned) }, recover: {},
            send: { _ in prepares += 1; return 21 }
        )
        XCTAssertEqual(value, 21)
        XCTAssertEqual(prepares, 2)
    }

    func testClaimedOperationIsTerminalAfterDeliveryError() async {
        var attempted = false
        do {
            let _: Int = try await performRecoverableSend(
                context: account,
                inspect: { ids in self.snapshot(attempted ? .completed : .returned, ids: ids) },
                recover: {},
                send: { remember in remember("delivered"); attempted = true; throw Fault.transport }
            )
            XCTFail("Expected terminal state")
        } catch let recovery as PendingSendRecovery {
            XCTAssertEqual(recovery.outcome, .completed)
            XCTAssertEqual(recovery.operationIDs, ["delivered"])
            XCTAssertEqual(recovery.walletMessage.recoverability, .terminal)
        } catch { XCTFail("Unexpected error") }
    }

    func testFailedPreflightReadNeverPrepares() async {
        do {
            let _: Int = try await performRecoverableSend(
                context: account, inspect: { _ in throw Fault.database }, recover: {},
                send: { _ in XCTFail("Must not prepare"); return 0 }
            )
            XCTFail("Expected unresolved state")
        } catch let recovery as PendingSendRecovery {
            XCTAssertEqual(recovery.outcome, .unverified)
        } catch { XCTFail("Unexpected error") }
    }

    func testRecoveryFailureRetainsPreparedOperation() async {
        do {
            let _: Int = try await performRecoverableSend(
                context: account, inspect: { _ in self.snapshot(.returned) },
                recover: { throw Fault.database },
                send: { remember in remember("uncertain"); throw Fault.transport }
            )
            XCTFail("Expected unresolved state")
        } catch let recovery as PendingSendRecovery {
            XCTAssertEqual(recovery.outcome, .unverified)
            XCTAssertEqual(recovery.operationIDs, ["uncertain"])
        } catch { XCTFail("Unexpected error") }
    }

    func testFailedPostRecoveryReadRetainsPreparedOperation() async {
        var attempted = false
        do {
            let _: Int = try await performRecoverableSend(
                context: account,
                inspect: { _ in
                    if attempted { throw Fault.database }
                    return self.snapshot(.returned)
                }, recover: {},
                send: { remember in remember("uncertain"); attempted = true; throw Fault.transport }
            )
            XCTFail("Expected unresolved state")
        } catch let recovery as PendingSendRecovery {
            XCTAssertEqual(recovery.operationIDs, ["uncertain"])
            XCTAssertEqual(recovery.outcome, .unverified)
        } catch { XCTFail("Unexpected error") }
    }

    func testSuccessfulSendDoesNotRunRecovery() async throws {
        let value = try await performRecoverableSend(
            context: account, inspect: { _ in self.snapshot(.returned) },
            recover: { XCTFail("Successful send") }, send: { _ in 21 }
        )
        XCTAssertEqual(value, 21)
    }
    private let operationID = "11111111-1111-4111-8111-111111111111"

    private func saga(unit: String = "sat", state: String = "token_created", token: String = "\"test-token\"") -> String {
        """
        {"id":"\(operationID)","kind":"send","mint_url":"https://mint.example","unit":"\(unit)",
         "amount":21,"state":{"type":"send","state":"\(state)"},"data":{"kind":"send","data":{"token":\(token)}}}
        """
    }

    func testPersistedPendingTokenIsRecoveredAfterReopening() throws {
        let result = try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
            sagaJSON: [saga()], pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(result.outcome, .pending)
        XCTAssertEqual(result.sends, [PendingSend(id: operationID, amount: 21, token: "test-token")])
    }

    func testOtherCurrencyDoesNotBlockThisAccount() throws {
        let result = try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
            sagaJSON: [saga(unit: "usd")], pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(result.outcome, .returned)
        XCTAssertTrue(result.sends.isEmpty)
    }

    func testUnfinishedReservationAndRollbackRemainUnverified() throws {
        for state in ["proofs_reserved", "rolling_back"] {
            let result = try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
                sagaJSON: [saga(state: state)], pendingIDs: [], transactions: [:])
            XCTAssertEqual(result.outcome, .unverified)
            XCTAssertTrue(result.sends.isEmpty)
        }
    }

    func testMissingTokenPreviewStillOffersReclaim() throws {
        let result = try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
            sagaJSON: [saga(token: "null")], pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(result.outcome, .pending)
        XCTAssertEqual(result.sends.first?.id, operationID)
        XCTAssertNil(result.sends.first?.token)
    }

    func testClaimedAndReclaimedTransactionsRemainDistinct() throws {
        var known = account
        known.operationIDs = [operationID]
        let txID = try XCTUnwrap(SagaTransactionId.transactionIdHex(operationId: operationID))
        for (status, expected) in [(SendRecoveryTransactionStatus.completed, PendingSendRecovery.Outcome.completed), (.failed, .returned), (.pending, .unverified)] {
            let result = try resolvePendingSendRecovery(context: known, mintURL: account.mintURL,
                sagaJSON: [], pendingIDs: [], transactions: [txID: status])
            XCTAssertEqual(result.outcome, expected)
        }
    }

    func testMissingOrCorruptSagaCannotAuthorizeRetry() throws {
        let missing = try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
            sagaJSON: [], pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(missing.outcome, .unverified)
        XCTAssertThrowsError(try resolvePendingSendRecovery(context: account, mintURL: account.mintURL,
            sagaJSON: ["invalid"], pendingIDs: [], transactions: [:]))
    }

    func testCDKDatabaseRoundTripKeepsRecoveryTokenAndAccount() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("wallet.sqlite").path
        let database = try WalletSqliteDatabase(filePath: path)
        let record = """
        {"id":"\(operationID)","kind":"send","mint_url":"https://mint.example","unit":"usd","amount":21,
         "state":{"type":"send","state":"token_created"},"quote_id":null,"created_at":1,"updated_at":1,"version":0,
         "data":{"kind":"send","data":{"amount":21,"memo":null,"counter_start":null,"counter_end":null,"token":"test-token","proofs":null}}}
        """
        try await database.addSaga(sagaJson: record)
        let reopened = try WalletSqliteDatabase(filePath: path)
        let persisted = try await reopened.getIncompleteSagas()
        let context = PendingSendRecovery(mintURL: account.mintURL, unit: "usd")
        let recovered = try resolvePendingSendRecovery(context: context, mintURL: context.mintURL,
            sagaJSON: persisted, pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(recovered.outcome, .pending)
        XCTAssertEqual(recovered.sends, [PendingSend(id: operationID, amount: 21, token: "test-token")])
        XCTAssertEqual(recovered.unit, "usd")
    }

}
