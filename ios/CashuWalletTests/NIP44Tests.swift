import XCTest
import Cdk
@testable import CashuWallet

/// CDK 0.18.2 UniFFI boundary tests, independent of the local NIP-17 decoder.
/// Fixed payloads from https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
final class NIP44Tests: XCTestCase {
    private let alice = String(repeating: "0", count: 63) + "1"
    private let bob = String(repeating: "0", count: 63) + "2"

    func testOfficialVectors() throws {
        XCTAssertEqual(try nip44Decrypt(nostrSecretKey: "0000000000000000000000000000000000000000000000000000000000000002", senderPubkey: nostrGetPubkey(nostrSecretKey: "0000000000000000000000000000000000000000000000000000000000000001"), payload: "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"), "a")
        XCTAssertEqual(try nip44Decrypt(nostrSecretKey: "0000000000000000000000000000000000000000000000000000000000000001", senderPubkey: nostrGetPubkey(nostrSecretKey: "0000000000000000000000000000000000000000000000000000000000000002"), payload: "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"), "🍕🫃")
        XCTAssertEqual(try nip44Decrypt(nostrSecretKey: "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d", senderPubkey: nostrGetPubkey(nostrSecretKey: "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a"), payload: "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="), "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀")
    }

    func testRoundTripsAcrossPaddingBoundaries() throws {
        for plaintext in [" ", "こんにちは世界 🌍", String(repeating: "A", count: 33), String(repeating: "B", count: 257), String(repeating: "C", count: 2_000), String(repeating: "D", count: 65_408)] {
            let payload = try nip44Encrypt(nostrSecretKey: alice, recipientPubkey: nostrGetPubkey(nostrSecretKey: bob), plaintext: plaintext)
            XCTAssertEqual(try nip44Decrypt(nostrSecretKey: bob, senderPubkey: nostrGetPubkey(nostrSecretKey: alice), payload: payload), plaintext)
        }
    }

    func testRejectsMalformedPayloadsWrongKeysAndTampering() throws {
        let sender = try nostrGetPubkey(nostrSecretKey: alice)
        let payload = try nip44Encrypt(nostrSecretKey: alice, recipientPubkey: nostrGetPubkey(nostrSecretKey: bob), plaintext: "secret")
        for invalid in ["", "not-base64!!!", "dG9vc2hvcnQ="] {
            XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: bob, senderPubkey: sender, payload: invalid))
        }
        XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: String(repeating: "0", count: 63) + "3", senderPubkey: sender, payload: payload))
        // Version, nonce, ciphertext and authentication tag are independently corrupted.
        for offset in [0, 1, 50, Data(base64Encoded: payload)!.count - 1] {
            var raw = Data(base64Encoded: payload)!
            raw[offset] ^= 1
            XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: bob, senderPubkey: sender, payload: raw.base64EncodedString()))
        }
    }

    func testRejectsInvalidKeysAndPlaintextLengths() throws {
        let pubkey = try nostrGetPubkey(nostrSecretKey: bob)
        for key in ["", "invalid", String(repeating: "0", count: 64), String(repeating: "f", count: 64)] {
            XCTAssertThrowsError(try nip44Encrypt(nostrSecretKey: key, recipientPubkey: pubkey, plaintext: "test"))
        }
        for key in ["", "79be", String(repeating: "f", count: 64)] {
            XCTAssertThrowsError(try nip44Encrypt(nostrSecretKey: alice, recipientPubkey: key, plaintext: "test"))
        }
        for text in ["", String(repeating: "A", count: 65_409), String(repeating: "A", count: 65_535), String(repeating: "A", count: 65_536)] {
            XCTAssertThrowsError(try nip44Encrypt(nostrSecretKey: alice, recipientPubkey: pubkey, plaintext: text))
        }
    }

    func testNIP17UnwrapUsesCDKForBothLayers() throws {
        let sender = try nostrGetPubkey(nostrSecretKey: alice)
        let recipient = try nostrGetPubkey(nostrSecretKey: bob)
        let ephemeral = String(repeating: "0", count: 63) + "4"
        let rumorJSON = "{\"id\":\"rumor-1\",\"pubkey\":\"\(sender)\",\"created_at\":100,\"kind\":14,\"tags\":[],\"content\":\"cashu request\"}"
        let inner = try nip44Encrypt(nostrSecretKey: alice, recipientPubkey: recipient, plaintext: rumorJSON)
        let privateKey = Data(repeating: 0, count: 31) + Data([2])
        func wrap(inner: String) throws -> NostrIncomingEvent {
            let seal = NostrIncomingEvent(id: "seal-1", pubkey: sender, createdAt: 101, kind: 13, tags: [], content: inner, sig: "fixture")
            let content = try nip44Encrypt(nostrSecretKey: ephemeral, recipientPubkey: recipient, plaintext: String(decoding: JSONEncoder().encode(seal), as: UTF8.self))
            return NostrIncomingEvent(id: "wrap-1", pubkey: try nostrGetPubkey(nostrSecretKey: ephemeral), createdAt: 102, kind: 1059, tags: [["p", recipient]], content: content, sig: "fixture")
        }
        let event = try wrap(inner: inner)
        let rumor = try NIP17.unwrap(giftWrap: event, recipientPrivateKey: privateKey)
        XCTAssertEqual(rumor.id, "rumor-1")
        XCTAssertEqual(rumor.pubkey, sender)
        XCTAssertEqual(rumor.kind, 14)
        XCTAssertEqual(rumor.content, "cashu request")
        XCTAssertThrowsError(try NIP17.unwrap(giftWrap: event, recipientPrivateKey: Data(repeating: 0, count: 31) + Data([3])))
        let invalidInner = try wrap(inner: "not-base64!!!")
        XCTAssertThrowsError(try NIP17.unwrap(giftWrap: invalidInner, recipientPrivateKey: privateKey))
    }
}
