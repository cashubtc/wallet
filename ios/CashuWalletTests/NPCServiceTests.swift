import XCTest
import SwiftUI
import Cdk
@testable import CashuWallet

@MainActor
final class NPCServiceTests: XCTestCase {
    func testAddressReceiptExpandsToSharedSuccess() async throws {
        try await verifyAddressReceiptPresentation(colorScheme: .dark, dynamicTypeSize: .large)
    }

    func testAddressReceiptExpandsAtAccessibilityTextSize() async throws {
        try await verifyAddressReceiptPresentation(colorScheme: .light, dynamicTypeSize: .accessibility3)
    }

    private func verifyAddressReceiptPresentation(
        colorScheme: ColorScheme, dynamicTypeSize: DynamicTypeSize
    ) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let model = AddressReceiptPresentationModel()
        let host = UIHostingController(rootView: AddressReceiptPresentationHarness(
            model: model, dynamicTypeSize: dynamicTypeSize))
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = colorScheme == .dark ? .dark : .light
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            host.dismiss(animated: false)
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }
        try await Task.sleep(for: .milliseconds(600))
        let presented = try XCTUnwrap(host.presentedViewController)
        let qrImage = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let qrAttachment = XCTAttachment(image: qrImage)
        qrAttachment.name = "Lightning Address QR - \(colorScheme) - \(dynamicTypeSize)"
        qrAttachment.lifetime = .keepAlways
        add(qrAttachment)
        model.amount = "21 sats"
        try await Task.sleep(for: .milliseconds(900))
        XCTAssertEqual(presented.sheetPresentationController?.detents.map(\.identifier), [UISheetPresentationController.Detent.Identifier.large])
        XCTAssertGreaterThan(presented.view.bounds.height, window.bounds.height * 0.8)
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "Lightning Address success - \(colorScheme) - \(dynamicTypeSize)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testVisibleAddressChecksPromptlyWithoutPeriodicChecksAndStopsOnDismiss() async throws {
        let client = ControlledNPCClient()
        client.automaticResponsesAfter = 1
        let settings = makeSettings(enabled: true)
        let service = NPCService(settingsStore: settings, receiveRefreshInterval: 0.01, makeClient: { _, _ in client })
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let connection = Task { await service.connect() }
        client.complete(.success([]))
        await connection.value
        settings.checkIncomingInvoices = true
        settings.periodicallyCheckIncomingInvoices = false

        let monitor = Task { await service.monitorPayments(address: service.lightningAddress, openedAt: Date()) }
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertGreaterThanOrEqual(client.requestCount, 3, "The open sheet must not wait for the two-minute poll")
        monitor.cancel()
        await monitor.value
        let stoppedCount = client.requestCount
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(client.requestCount, stoppedCount)
        service.disconnect()
    }

    func testVisibleAddressRespectsDisabledPaymentChecks() async throws {
        let client = ControlledNPCClient()
        client.automaticResponsesAfter = 1
        let service = NPCService(settingsStore: makeSettings(enabled: true), receiveRefreshInterval: 0.01,
                                 makeClient: { _, _ in client })
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let connection = Task { await service.connect() }
        client.complete(.success([]))
        await connection.value
        let monitor = Task { await service.monitorPayments(address: service.lightningAddress, openedAt: Date()) }
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(client.requestCount, 1)
        monitor.cancel()
        await monitor.value
        service.disconnect()
    }

    func testAddressReceiptRejectsOldOtherWalletUnknownTimeAndZeroCredits() {
        let openedAt = Date(timeIntervalSince1970: 100.75)
        let fresh = NPCPaymentReceipt(quoteID: "new", address: "test@example.com", amount: 21, paidAt: 101)
        XCTAssertTrue(fresh.belongsToReceiveSession(address: fresh.address, openedAt: openedAt))
        XCTAssertFalse(fresh.belongsToReceiveSession(address: "other@example.com", openedAt: openedAt))
        for (amount, paidAt): (UInt64, UInt64?) in [(21, 99), (21, nil), (0, 101)] {
            let receipt = NPCPaymentReceipt(quoteID: "old", address: fresh.address, amount: amount, paidAt: paidAt)
            XCTAssertFalse(receipt.belongsToReceiveSession(address: fresh.address, openedAt: openedAt))
        }
    }

    func testSetupRetryRecoversRuntimeAndUsesStoredSeed() async throws {
        var ready = false
        var initializedSeed: Data?
        let seed = Data(repeating: 7, count: 64)
        try await LightningAddressSetupRecovery.retry(
            isRuntimeReady: { ready }, initializeRuntime: { ready = true },
            isAddressInitialized: { false }, loadSeed: { seed },
            initializeAddress: { initializedSeed = $0 }
        )
        XCTAssertEqual(initializedSeed, seed)
    }

    func testSetupRetryDoesNotReplaceAnExistingIdentity() async throws {
        try await LightningAddressSetupRecovery.retry(
            isRuntimeReady: { true }, initializeRuntime: { XCTFail("Must not reopen a healthy wallet") },
            isAddressInitialized: { true }, loadSeed: { XCTFail("Must not reload keys"); return Data() },
            initializeAddress: { _ in XCTFail("Must not replace identity") }
        )
    }

    func testSetupRetryStopsWhenRuntimeOrSeedRecoveryFails() async {
        for runtimeReady in [false, true] {
            do {
                try await LightningAddressSetupRecovery.retry(
                    isRuntimeReady: { runtimeReady }, initializeRuntime: {},
                    isAddressInitialized: { false }, loadSeed: { throw WalletError.notInitialized },
                    initializeAddress: { _ in XCTFail("Must not initialize without prerequisites") }
                )
                XCTFail("Expected recovery failure")
            } catch { }
        }
    }

    func testRestoredEnabledAddressConnectsWhenSeedBecomesAvailable() async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client)
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let connection = Task { await service.connect() }
        client.complete(.success([]))
        await connection.value

        XCTAssertTrue(service.isConnected)
        XCTAssertFalse(service.isLoading)
        XCTAssertNil(service.errorMessage)
        XCTAssertEqual(client.requestCount, 1)
        service.disconnect()
    }

    func testDisabledAddressDoesNotConnectAtStartupOrForeground() async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client, enabled: false)
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await service.initializeIfEnabled()
        XCTAssertEqual(client.requestCount, 0)
        XCTAssertFalse(service.isConnected)
    }

    func testEnablingBeforeKeysAreReadyWaitsForSeed() async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client, enabled: false)
        service.isEnabled = true
        await service.connect()
        XCTAssertNil(service.errorMessage)
        XCTAssertEqual(client.requestCount, 0)

        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let connection = Task { await service.connect() }
        client.complete(.success([]))
        await connection.value
        XCTAssertTrue(service.isConnected)
        service.disconnect()
    }

    func testConcurrentAndRepeatedRecoveryShareConnection() async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client)
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let first = Task { await service.initializeIfEnabled() }
        let second = Task { await service.connect() }
        await Task.yield()
        XCTAssertEqual(client.requestCount, 1)
        client.complete(.success([]))
        await first.value
        await second.value
        await service.initializeIfEnabled()
        XCTAssertEqual(client.requestCount, 1)
        XCTAssertTrue(service.isConnected)
        service.disconnect()
    }

    func testFailedStartupCanRecoverWithoutTogglingAddress() async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client)
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let initial = Task { await service.connect() }
        await Task.yield()
        client.complete(.failure(URLError(.notConnectedToInternet)))
        await initial.value
        XCTAssertFalse(service.isConnected)
        XCTAssertFalse(service.isLoading)
        XCTAssertNotNil(service.errorMessage)

        let foreground = Task { await service.initializeIfEnabled() }
        await client.nextRequest()
        client.complete(.success([]))
        await foreground.value
        XCTAssertTrue(service.isEnabled)
        XCTAssertTrue(service.isConnected)
        XCTAssertNil(service.errorMessage)
        service.disconnect()
    }

    func testLateSuccessCannotReconnectDisabledAddress() async throws {
        try await assertLateResponseIgnored(.success([]))
    }

    func testLateFailureCannotPublishErrorAfterDisable() async throws {
        try await assertLateResponseIgnored(.failure(URLError(.timedOut)))
    }

    func testOldConnectionCannotOverwriteNewWalletSession() async throws {
        let oldClient = ControlledNPCClient()
        let newClient = ControlledNPCClient()
        var clients = [oldClient, newClient]
        let settings = makeSettings(enabled: true)
        let service = NPCService(settingsStore: settings, makeClient: { _, _ in clients.removeFirst() })
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await oldClient.nextRequest()
        let oldConnection = Task { await service.connect() }
        await Task.yield()
        service.resetForWalletBoundary()
        try service.initializeWithSeed(Data(repeating: 2, count: 64))
        service.isEnabled = true
        await newClient.nextRequest()
        let newConnection = Task { await service.connect() }
        newClient.complete(.success([]))
        await newConnection.value
        let address = service.lightningAddress

        oldClient.complete(.failure(URLError(.timedOut)))
        await oldConnection.value
        XCTAssertTrue(service.isConnected)
        XCTAssertFalse(service.isLoading)
        XCTAssertNil(service.errorMessage)
        XCTAssertEqual(service.lightningAddress, address)
        service.disconnect()
    }

    func testPeriodicChecksRetryFailedStartup() async throws {
        let client = ControlledNPCClient()
        client.automaticResponsesAfter = 2
        let settings = makeSettings(enabled: true)
        settings.checkIncomingInvoices = true
        settings.periodicallyCheckIncomingInvoices = true
        let service = NPCService(settingsStore: settings, refreshInterval: 0.01, makeClient: { _, _ in client })
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let initial = Task { await service.connect() }
        await Task.yield()
        client.complete(.failure(URLError(.notConnectedToInternet)))
        await initial.value
        XCTAssertFalse(service.isConnected)

        // The service's timer starts a new attempt without any foreground/UI call.
        await client.nextRequest()
        let retry = Task { await service.connect() }
        client.complete(.success([]))
        await retry.value
        XCTAssertTrue(service.isConnected)
        XCTAssertNil(service.errorMessage)
        service.disconnect()
    }

    func testRapidDisableAndEnableIgnoresOldSuccessWithSameKeys() async throws {
        let oldClient = ControlledNPCClient()
        let newClient = ControlledNPCClient()
        var clients = [oldClient, newClient]
        let service = NPCService(settingsStore: makeSettings(enabled: true), makeClient: { _, _ in clients.removeFirst() })
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await oldClient.nextRequest()
        let oldConnection = Task { await service.connect() }
        await Task.yield()
        service.isEnabled = false
        service.isEnabled = true
        await newClient.nextRequest()
        oldClient.complete(.success([]))
        await oldConnection.value
        XCTAssertFalse(service.isConnected)
        XCTAssertTrue(service.isLoading)
        let newConnection = Task { await service.connect() }
        newClient.complete(.success([]))
        await newConnection.value
        XCTAssertTrue(service.isConnected)
        service.disconnect()
    }

    private func assertLateResponseIgnored(_ response: Result<[NpubCashQuote], Error>) async throws {
        let client = ControlledNPCClient()
        let service = makeService(client: client)
        try service.initializeWithSeed(Data(repeating: 1, count: 64))
        await client.nextRequest()
        let connection = Task { await service.connect() }
        await Task.yield()
        service.isEnabled = false
        client.complete(response)
        await connection.value
        XCTAssertFalse(service.isConnected)
        XCTAssertFalse(service.isLoading)
        XCTAssertNil(service.errorMessage)
    }

    private func makeService(client: ControlledNPCClient, enabled: Bool = true) -> NPCService {
        NPCService(settingsStore: makeSettings(enabled: enabled), makeClient: { _, _ in client })
    }

    private func makeSettings(enabled: Bool) -> SettingsStore {
        let settings = SettingsStore(storage: InMemoryStorage())
        settings.npcEnabled = enabled
        settings.checkIncomingInvoices = false
        return settings
    }
}

/// Deliberately ignores cancellation to exercise late FFI/network completions.
@MainActor
private final class ControlledNPCClient: NpubCashClientProtocol {
    private(set) var requestCount = 0
    var automaticResponsesAfter: Int?
    private var unobservedRequests = 0
    private var started: CheckedContinuation<Void, Never>?
    private var response: CheckedContinuation<[NpubCashQuote], Error>?

    func getQuotes(since: UInt64?) async throws -> [NpubCashQuote] {
        requestCount += 1
        if let automaticResponsesAfter, requestCount > automaticResponsesAfter { return [] }
        return try await withCheckedThrowingContinuation { continuation in
            response = continuation
            if let started {
                self.started = nil
                started.resume()
            } else {
                unobservedRequests += 1
            }
        }
    }

    func nextRequest() async {
        if unobservedRequests > 0 {
            unobservedRequests -= 1
            return
        }
        await withCheckedContinuation { started = $0 }
    }

    func complete(_ result: Result<[NpubCashQuote], Error>) {
        let continuation = response
        response = nil
        continuation?.resume(with: result)
    }

    func setMintUrl(mintUrl: String) async throws -> NpubCashUserResponse {
        NpubCashUserResponse(error: false, pubkey: "", mintUrl: mintUrl, lockQuote: false)
    }
    func getMissingQuotes(quoteIds: [String]) async throws -> [NpubCashQuote] { [] }
    func getUserInfo() async throws -> NpubCashUserResponse { throw NPCError.invalidResponse }
    func setQuoteLocking(lockQuotes: Bool) async throws -> NpubCashUserResponse { throw NPCError.invalidResponse }
}

@MainActor
private final class AddressReceiptPresentationModel: ObservableObject {
    @Published var amount: String?
}

private struct AddressReceiptPresentationHarness: View {
    @ObservedObject var model: AddressReceiptPresentationModel
    let dynamicTypeSize: DynamicTypeSize

    var body: some View {
        Color.clear.sheet(isPresented: .constant(true)) {
            QRCodeDetailSheet(title: "Lightning Address", content: "receiver@example.com",
                              receivedAmount: model.amount, showsContent: false)
                .environment(\.dynamicTypeSize, dynamicTypeSize)
        }
    }
}
