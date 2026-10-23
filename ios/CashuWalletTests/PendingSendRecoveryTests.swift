import Cdk
import XCTest
@testable import CashuWallet

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
        let database = try LifecycleSafeWalletDatabase(filePath: path)
        let record = """
        {"id":"\(operationID)","kind":"send","mint_url":"https://mint.example","unit":"usd","amount":21,
         "state":{"type":"send","state":"token_created"},"quote_id":null,"created_at":1,"updated_at":1,"version":0,
         "data":{"kind":"send","data":{"amount":21,"memo":null,"counter_start":null,"counter_end":null,"token":"test-token","proofs":null}}}
        """
        try await database.addSaga(sagaJson: record)
        let reopened = try LifecycleSafeWalletDatabase(filePath: path)
        let persisted = try await reopened.getIncompleteSagas()
        let context = PendingSendRecovery(mintURL: account.mintURL, unit: "usd")
        let recovered = try resolvePendingSendRecovery(context: context, mintURL: context.mintURL,
            sagaJSON: persisted, pendingIDs: [operationID], transactions: [:])
        XCTAssertEqual(recovered.outcome, .pending)
        XCTAssertEqual(recovered.sends, [PendingSend(id: operationID, amount: 21, token: "test-token")])
        XCTAssertEqual(recovered.unit, "usd")
    }

}
