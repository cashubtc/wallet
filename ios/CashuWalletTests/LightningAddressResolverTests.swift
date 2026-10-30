import XCTest
import Cdk
@testable import CashuWallet

@MainActor
final class LightningAddressResolverTests: XCTestCase {
    private let discovery = "Failed to request invoice from Lightning address service: Failed to fetch pay request data: Http transport error Some("
    private func failure(_ message: String) -> FfiError { .Cdk(code: 50000, errorMessage: message) }

    func testCandidateLoopPreservesMissingDiscoveryErrorForFallback() async throws {
        let service = LightningService(walletRepository: { nil }, walletDatabase: { nil }, getActiveMint: { nil })
        let candidates = [
            MintInfo(url: "https://first.example", name: "First", isActive: true, balance: 100),
            MintInfo(url: "https://second.example", name: "Second", isActive: false, balance: 100)
        ]
        var attempted: [String] = []
        var fallbackCalls = 0
        let quote = try await LightningAddressResolver.quote(
            lnurl: {
                try await service.firstAffordableMeltQuote(
                    in: candidates, failureMessage: "No quote",
                    shouldStopOnError: LightningAddressResolver.isRecipientError
                ) { mint in
                    attempted.append(mint.url)
                    if attempted.count == 1 { throw self.failure("\(self.discovery)404): missing") }
                    throw self.failure("Http transport error None: connection refused")
                }
            },
            bip353: {
                fallbackCalls += 1
                return MeltQuoteInfo(id: "fallback", mintUrl: candidates[0].url, amount: 10, feeReserve: 0, paymentMethod: .bolt12, state: .unpaid, expiry: nil)
            }
        )
        XCTAssertEqual(attempted, [candidates[0].url])
        XCTAssertEqual(fallbackCalls, 1)
        XCTAssertEqual(quote.id, "fallback")
    }

    func testCandidateLoopRetriesMintFailuresAndChecksQuoteFees() async throws {
        let service = LightningService(walletRepository: { nil }, walletDatabase: { nil }, getActiveMint: { nil })
        let candidates = (1...3).map { MintInfo(url: "https://mint\($0).example", name: "Mint", isActive: false, balance: 10) }
        var attempted: [String] = []
        let quote = try await service.firstAffordableMeltQuote(
            in: candidates, failureMessage: "No quote",
            shouldStopOnError: LightningAddressResolver.isRecipientError
        ) { mint in
            attempted.append(mint.url)
            if attempted.count == 1 { throw self.failure("Http transport error None: connection refused") }
            return MeltQuoteInfo(id: mint.url, mintUrl: mint.url, amount: 10, feeReserve: attempted.count == 2 ? 1 : 0, paymentMethod: .bolt11, state: .unpaid, expiry: nil)
        }
        XCTAssertEqual(attempted, candidates.map(\.url))
        XCTAssertEqual(quote.mintUrl, candidates[2].url)
    }

    func testLnurlWinsForBothBolt11OnlyAndBolt12Mints() async throws {
        for supportsBolt12 in [false, true] {
            var calls: [String] = []
            let quote = try await LightningAddressResolver.quote(
                lnurl: { calls.append("lnurl"); return "bolt11" },
                bip353: { calls.append("bip353"); XCTAssertTrue(supportsBolt12); return "bolt12" }
            )
            XCTAssertEqual(quote, "bolt11")
            XCTAssertEqual(calls, ["lnurl"])
        }
    }

    func testOnlyMissingDiscoveryEndpointFallsBack() async throws {
        for status in [404, 410] {
            var fallbackCalls = 0
            let quote = try await LightningAddressResolver.quote(
                lnurl: { () -> String in throw self.failure("\(self.discovery)\(status)): missing") },
                bip353: { fallbackCalls += 1; return "bolt12" }
            )
            XCTAssertEqual(quote, "bolt12")
            XCTAssertEqual(fallbackCalls, 1)
        }
    }

    func testTemporaryAndInvalidRecipientResponsesNeverFallBack() async {
        let prefix = "Failed to request invoice from Lightning address service: "
        for message in [
            "\(discovery)502): Bad Gateway", "\(discovery)429): Slow down",
            "\(prefix)Failed to fetch pay request data: Http transport error None: DNS failed",
            "\(prefix)Http transport error Some(404): callback missing",
            "\(prefix)Returned invoice amount 1 msat does not match requested amount 1000 msat",
            "\(prefix)Invalid LNURL callback URL: callback host must match lightning address domain",
            "\(prefix)Failed to fetch pay request data: Http transport error None: invalid JSON",
            "Http transport error Some(404): mint quote missing"
        ] {
            var fallbackCalls = 0
            do {
                _ = try await LightningAddressResolver.quote(
                    lnurl: { () -> String in throw self.failure(message) },
                    bip353: { fallbackCalls += 1; return "bolt12" }
                )
                XCTFail("Expected failure")
            } catch {
                guard case let FfiError.Cdk(_, detail) = error else { return XCTFail("Lost FFI error") }
                XCTAssertEqual(detail, message)
                XCTAssertEqual(fallbackCalls, 0)
            }
        }
    }

    func testErrorsKeepRecipientDnsAndMintOrigins() async {
        XCTAssertEqual(failure("\(discovery)502): Bad Gateway").userFacingWalletMessage,
                       "The recipient’s Lightning address service is unavailable (HTTP 502). Try again later.")
        do {
            _ = try await LightningAddressResolver.quote(
                lnurl: { () -> String in throw self.failure("\(self.discovery)404): missing") },
                bip353: { () -> String in throw self.failure("Failed to resolve BIP353 address: DNS timeout") }
            )
            XCTFail("Expected DNS failure")
        } catch {
            XCTAssertEqual(error.userFacingWalletMessage,
                           "Couldn't resolve the recipient’s BIP-353 address. Check the address or try again later.")
        }
        XCTAssertEqual(failure("Http transport error None: connection refused").userFacingWalletMessage,
                       "Couldn't reach the mint. Check your connection and try again.")
    }
}

/// Opt-in network tests: production service -> generated Swift binding -> native
/// CDK -> TLS LNURL / DNSSEC / mint stubs. No resolver or FFI errors are mocked.
@MainActor
final class LightningAddressBindingTests: XCTestCase {
    private var fixture = ""
    private var repositories: [WalletRepository] = []
    private var paths: [String] = []

    override func setUp() async throws {
        fixture = ProcessInfo.processInfo.environment["LIGHTNING_FIXTURE_URL"] ?? ""
        try XCTSkipIf(fixture.isEmpty, "Run the Lightning Address fixture harness")
        let (certificate, _) = try await URLSession.shared.data(from: URL(string: fixture + "/ca.pem")!)
        let path = NSTemporaryDirectory() + "lightning-fixture-ca.pem"
        try certificate.write(to: URL(fileURLWithPath: path))
        setenv("SSL_CERT_FILE", path, 1)
        setenv("LIGHTNING_DNS_STUB", "1", 1)
    }

    override func tearDown() async throws {
        repositories.removeAll()
        for path in paths { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        paths.removeAll()
        try await super.tearDown()
    }

    private func call(_ path: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: fixture + path)!)
        if let body {
            request.httpMethod = "POST"
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    private func service(_ scenario: [String: Any] = [:]) async throws -> LightningService {
        _ = try await call("/scenario", body: scenario)
        let path = NSTemporaryDirectory() + "lightning-\(UUID().uuidString).sqlite"
        paths.append(path)
        let repo = try WalletRepository(mnemonic: generateMnemonic(), store: .sqlite(path: path))
        repositories.append(repo)
        let url = fixture
        try await repo.createWallet(mintUrl: MintUrl(url: url), unit: .sat, targetProofCount: nil)
        var mint = MintInfo(url: url, name: "Fixture", isActive: true, balance: 1000)
        mint.supportedMeltMethods = scenario["bolt12"] as? Bool == false ? [.bolt11] : [.bolt11, .bolt12]
        return LightningService(walletRepository: { repo }, walletDatabase: { nil }, getActiveMint: { mint }, getMints: { [mint] })
    }

    private func events() async throws -> [[String: Any]] {
        try await call("/state")["events"] as! [[String: Any]]
    }

    private func assertTrace(method: String?, dns: Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        let trace = try await events()
        let quotes = trace.filter { $0["kind"] as? String == "mint-quote" }
        XCTAssertEqual(quotes.compactMap { $0["method"] as? String }, method.map { [$0] } ?? [], file: file, line: line)
        XCTAssertEqual(trace.contains { $0["kind"] as? String == "dns" }, dns, file: file, line: line)
        XCTAssertEqual(trace.filter { $0["kind"] as? String == "lnurl-discovery" }.count, 1, file: file, line: line)
    }

    func testDualNameWithBolt11OnlyMintUsesLnurl() async throws {
        let service = try await service(["bolt12": false])
        let quote = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
        XCTAssertEqual(quote.paymentMethod, .bolt11)
        XCTAssertEqual(quote.amount, 100)
        try await assertTrace(method: "bolt11", dns: false)
    }

    func testDualNameWithBolt12MintStillUsesLnurl() async throws {
        let service = try await service()
        let quote = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
        XCTAssertEqual(quote.paymentMethod, .bolt11)
        try await assertTrace(method: "bolt11", dns: false)
    }

    func testRecipient502DoesNotFallBackOrReachMint() async throws {
        let service = try await service(["lnurl_status": 502])
        do {
            _ = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
            XCTFail("Expected recipient failure")
        } catch {
            XCTAssertEqual(error.userFacingWalletMessage, "The recipient’s Lightning address service is unavailable (HTTP 502). Try again later.")
        }
        try await assertTrace(method: nil, dns: false)
    }

    func testAbsentLnurlUsesValidatedBip353AndBolt12() async throws {
        let service = try await service(["lnurl_status": 404])
        let quote = try await service.createHumanReadableMeltQuote(address: "matt@mattcorallo.com", amount: 100)
        XCTAssertEqual(quote.paymentMethod, .bolt12)
        XCTAssertEqual(quote.amount, 100)
        try await assertTrace(method: "bolt12", dns: true)
    }

    func testActualMintConnectionFailureKeepsMintMessage() async throws {
        let service = try await service(["mint_failure": true])
        do {
            _ = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
            XCTFail("Expected mint failure")
        } catch {
            XCTAssertEqual(error.userFacingWalletMessage, "Couldn't reach the mint. Check your connection and try again.")
        }
        try await assertTrace(method: "bolt11", dns: false)
    }

    func testDnsFailureKeepsBip353Origin() async throws {
        let service = try await service(["lnurl_status": 404, "dns_failure": true])
        do {
            _ = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
            XCTFail("Expected DNS failure")
        } catch {
            XCTAssertEqual(error.userFacingWalletMessage, "Couldn't resolve the recipient’s BIP-353 address. Check the address or try again later.")
        }
        try await assertTrace(method: nil, dns: true)
    }

    func testAbsentLnurlCannotUseBolt11OnlyMintForBip353() async throws {
        let service = try await service(["lnurl_status": 404, "bolt12": false])
        do {
            _ = try await service.createHumanReadableMeltQuote(address: "test@twelve.cash", amount: 100)
            XCTFail("Expected unsupported mint")
        } catch {
            XCTAssertEqual(error.userFacingWalletMessage, "No mint supports BOLT12 payments required for this address.")
        }
        try await assertTrace(method: nil, dns: false)
    }
}
