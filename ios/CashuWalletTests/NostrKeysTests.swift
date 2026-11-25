import XCTest
import Cdk
@testable import CashuWallet

/// Pins CDK-backed Nostr and P2PK key derivation on iOS to the public keys the
/// wallet's previous P256K implementation produced, so existing seed-derived
/// identities, custom identities, npubs and P2PK keys remain unchanged.
@MainActor
final class NostrKeysTests: XCTestCase {
    private static let nip06Secret = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private static let nip06Public = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
    private static let nip06Npub = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"

    private static let vectors: [(secret: String, expectedPublic: String)] = [
        (
            "0000000000000000000000000000000000000000000000000000000000000001",
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        ),
        (
            "0000000000000000000000000000000000000000000000000000000000000003",
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        ),
        (nip06Secret, nip06Public),
        (
            "c15d739894c81a2fcfd3a2df85a0d2c0dbc47a280d092799f144d73d7ae78add",
            "d41b22899549e1f3d335a31002cfd382174006e166d3e658e3a5eecdb6463573"
        ),
        // n - 1: odd-Y point whose x-only key equals the generator's.
        (
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140",
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        ),
    ]

    func testPublicKeysMatchKnownVectors() throws {
        for vector in Self.vectors {
            XCTAssertEqual(
                try nostrGetPubkey(nostrSecretKey: vector.secret),
                vector.expectedPublic,
                "CDK pubkey mismatch for \(vector.secret)"
            )
            XCTAssertEqual(
                try NostrService.publicKeyHex(forPrivateKeyHex: vector.secret),
                vector.expectedPublic,
                "NostrService pubkey mismatch for \(vector.secret)"
            )
        }
    }

    func testMalformedSecretKeysFailCleanly() {
        let invalidKeys = [
            String(repeating: "0", count: 64),
            // The secp256k1 group order n is out of range.
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
            String(repeating: "f", count: 64),
            String(repeating: "01", count: 31),
            String(repeating: "zz", count: 32),
            "",
        ]

        for secret in invalidKeys {
            XCTAssertThrowsError(
                try NostrService.publicKeyHex(forPrivateKeyHex: secret),
                "Expected invalid secret key to throw: \(secret)"
            )
        }
    }

    func testGeneratedSecretKeysAreDistinctHexAcceptedByCdk() throws {
        let first = nostrGenerateSecretKey()
        let second = nostrGenerateSecretKey()

        for secret in [first, second] {
            XCTAssertTrue(isLowercaseHex64(secret), "Unexpected secret format: \(secret)")
            let pubkey = try nostrGetPubkey(nostrSecretKey: secret)
            XCTAssertTrue(isLowercaseHex64(pubkey), "Unexpected pubkey format: \(pubkey)")
        }
        XCTAssertNotEqual(first, second)
    }

    func testSeedIdentityKeepsItsExistingNpub() throws {
        let storage = InMemoryStorage()
        let service = NostrService(
            secureStorage: MemoryNostrSecureStorage(),
            settingsStore: SettingsStore(storage: storage)
        )

        let seed = try XCTUnwrap(Data(hexString: Self.nip06Secret))
        try service.deriveKeypair(from: seed)

        XCTAssertTrue(service.isInitialized)
        XCTAssertEqual(service.signerType, .seed)
        XCTAssertEqual(service.publicKeyHex, Self.nip06Public)
        XCTAssertEqual(service.npub, Self.nip06Npub)
        XCTAssertEqual(service.getPrivateKeyHex(), Self.nip06Secret)
    }

    func testGeneratedAndImportedCustomKeysRoundTrip() throws {
        let storage = InMemoryStorage()
        let secureStorage = MemoryNostrSecureStorage()
        let service = NostrService(
            secureStorage: secureStorage,
            settingsStore: SettingsStore(storage: storage)
        )
        let seed = try XCTUnwrap(Data(hexString: Self.nip06Secret))
        try service.deriveKeypair(from: seed)

        try service.generateRandomKeypair()
        let storedKey = try XCTUnwrap(secureStorage.secrets[KeychainService.nostrPrivateKeyKey])
        XCTAssertEqual(service.signerType, .privateKey)
        XCTAssertEqual(service.getPrivateKeyHex(), storedKey)
        XCTAssertEqual(service.publicKeyHex, try nostrGetPubkey(nostrSecretKey: storedKey))
        XCTAssertEqual(
            service.nsec,
            try Bech32.encode(hrp: "nsec", data: XCTUnwrap(Data(hexString: storedKey)))
        )

        let nip06Nsec = try Bech32.encode(hrp: "nsec", data: seed)
        try service.importNsec(nip06Nsec)
        XCTAssertEqual(service.signerType, .privateKey)
        XCTAssertEqual(service.publicKeyHex, Self.nip06Public)
        XCTAssertEqual(service.npub, Self.nip06Npub)
        XCTAssertEqual(secureStorage.secrets[KeychainService.nostrPrivateKeyKey], Self.nip06Secret)
    }

    func testInvalidImportedKeyLeavesIdentityAndStorageUnchanged() throws {
        let storage = InMemoryStorage()
        let secureStorage = MemoryNostrSecureStorage()
        let service = NostrService(
            secureStorage: secureStorage,
            settingsStore: SettingsStore(storage: storage)
        )
        let seed = try XCTUnwrap(Data(hexString: Self.nip06Secret))
        try service.deriveKeypair(from: seed)

        let zeroNsec = try Bech32.encode(hrp: "nsec", data: Data(repeating: 0, count: 32))
        XCTAssertThrowsError(try service.importNsec(zeroNsec))

        XCTAssertEqual(service.signerType, .seed)
        XCTAssertEqual(service.publicKeyHex, Self.nip06Public)
        XCTAssertEqual(service.npub, Self.nip06Npub)
        XCTAssertEqual(service.getPrivateKeyHex(), Self.nip06Secret)
        XCTAssertNil(secureStorage.secrets[KeychainService.nostrPrivateKeyKey])
    }

    func testP2PKGenerateAndImportUseCdkAndRejectInvalidNsec() throws {
        let storage = InMemoryStorage()
        let settingsStore = SettingsStore(storage: storage)
        let secureStorage = MemoryNostrSecureStorage()
        let manager = SettingsManager(
            settingsStore: settingsStore,
            secureStorage: secureStorage
        )

        let generated = try manager.generateP2PKKey()
        XCTAssertTrue(isLowercaseHex64(generated.privateKey))
        XCTAssertEqual(
            generated.publicKey,
            "02" + (try nostrGetPubkey(nostrSecretKey: generated.privateKey))
        )
        XCTAssertTrue(manager.isP2PKKeyUsable(generated.id))
        XCTAssertTrue(manager.isKnownP2PKPublicKey(generated.publicKey))

        let invalidScalars = [
            Data(repeating: 0, count: 32),
            try XCTUnwrap(Data(hexString: "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")),
        ]
        for scalar in invalidScalars {
            let nsec = try Bech32.encode(hrp: "nsec", data: scalar)
            XCTAssertThrowsError(try manager.importP2PKNsec(nsec)) { error in
                XCTAssertEqual(error as? SettingsFeatureError, .invalidNsec)
            }
        }
        XCTAssertEqual(manager.p2pkKeys.count, 1)
    }

    private func isLowercaseHex64(_ value: String) -> Bool {
        value.count == 64 && value.unicodeScalars.allSatisfy {
            ("0"..."9").contains($0) || ("a"..."f").contains($0)
        }
    }
}

private final class MemoryNostrSecureStorage: SecureStorageProtocol {
    var secrets: [String: String] = [:]

    func saveSecret(_ secret: String, forKey key: String) throws {
        secrets[key] = secret
    }

    func loadSecret(forKey key: String) throws -> String? {
        secrets[key]
    }

    func deleteSecret(forKey key: String) throws {
        secrets.removeValue(forKey: key)
    }

    func hasSecret(forKey key: String) -> Bool {
        secrets[key] != nil
    }
}
