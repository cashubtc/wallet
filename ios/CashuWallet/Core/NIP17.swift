import Foundation
import CryptoKit
import P256K

struct NostrIncomingEvent: Codable, Hashable {
    let id: String
    let pubkey: String
    let createdAt: Int64
    let kind: Int
    let tags: [[String]]
    let content: String
    let sig: String

    enum CodingKeys: String, CodingKey {
        case id
        case pubkey
        case createdAt = "created_at"
        case kind
        case tags
        case content
        case sig
    }
}

struct NostrRumor: Hashable {
    let id: String
    let pubkey: String
    let createdAt: Int64
    let kind: Int
    let tags: [[String]]
    let content: String
}

enum NIP17 {
    enum Error: Swift.Error {
        case decryptFailed
        case invalidSeal
        case invalidRumor
        case canonicalizeFailed
        case randomKeyFailed
        case signingFailed
    }

    /// Unwrap an incoming kind:1059 gift wrap addressed to us. Returns the inner kind:14 rumor.
    static func unwrap(giftWrap event: NostrIncomingEvent, recipientPrivateKey: Data) throws -> NostrRumor {
        // Layer 1: decrypt wrap content with our private key + wrap.pubkey (ephemeral)
        let sealJSON: String
        do {
            sealJSON = try NIP44.decrypt(
                payload: event.content,
                recipientPrivateKey: recipientPrivateKey,
                senderPubkeyHex: event.pubkey
            )
        } catch {
            throw Error.decryptFailed
        }
        guard let seal = decodeEvent(sealJSON) else { throw Error.invalidSeal }
        guard seal.kind == 13 else { throw Error.invalidSeal }

        // Layer 2: decrypt seal content with our private key + seal.pubkey (real sender)
        let rumorJSON: String
        do {
            rumorJSON = try NIP44.decrypt(
                payload: seal.content,
                recipientPrivateKey: recipientPrivateKey,
                senderPubkeyHex: seal.pubkey
            )
        } catch {
            throw Error.decryptFailed
        }
        guard let rumor = decodeRumor(rumorJSON, expectedAuthor: seal.pubkey) else {
            throw Error.invalidRumor
        }
        return rumor
    }

    /// Create an anonymous NIP-59 gift wrap containing a NIP-17 private message.
    static func wrap(plaintext: String, recipientPubkeyHex: String) throws -> NostrIncomingEvent {
        guard Data(hex: recipientPubkeyHex)?.count == 32 else { throw Error.invalidRumor }
        let senderKey = try randomPrivateKey()
        let senderPubkey = try publicKeyHex(for: senderKey)
        let now = Int64(Date().timeIntervalSince1970)
        let rumorTags = [["p", recipientPubkeyHex]]
        let rumorId = try eventId(pubkey: senderPubkey, createdAt: now, kind: 14, tags: rumorTags, content: plaintext)
        let rumor = UnsignedEvent(
            id: rumorId,
            pubkey: senderPubkey,
            createdAt: now,
            kind: 14,
            tags: rumorTags,
            content: plaintext
        )
        let rumorJSON = try encodedString(rumor)

        let sealContent = try NIP44.encrypt(
            plaintext: rumorJSON,
            senderPrivateKey: senderKey,
            recipientPubkeyHex: recipientPubkeyHex
        )
        let seal = try signedEvent(privateKey: senderKey, createdAt: now, kind: 13, tags: [], content: sealContent)
        let sealJSON = try encodedString(seal)

        let giftKey = try randomPrivateKey()
        let giftContent = try NIP44.encrypt(
            plaintext: sealJSON,
            senderPrivateKey: giftKey,
            recipientPubkeyHex: recipientPubkeyHex
        )
        let backdated = now - Int64.random(in: 0...172_800)
        return try signedEvent(
            privateKey: giftKey,
            createdAt: backdated,
            kind: 1059,
            tags: [["p", recipientPubkeyHex]],
            content: giftContent
        )
    }

    private struct UnsignedEvent: Codable {
        let id: String
        let pubkey: String
        let createdAt: Int64
        let kind: Int
        let tags: [[String]]
        let content: String

        enum CodingKeys: String, CodingKey {
            case id, pubkey, kind, tags, content
            case createdAt = "created_at"
        }
    }

    private static func signedEvent(
        privateKey: Data,
        createdAt: Int64,
        kind: Int,
        tags: [[String]],
        content: String
    ) throws -> NostrIncomingEvent {
        let key = try P256K.Schnorr.PrivateKey(dataRepresentation: [UInt8](privateKey))
        let pubkey = key.xonly.bytes.map { String(format: "%02x", $0) }.joined()
        let id = try eventId(pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content)
        guard let idData = Data(hex: id), idData.count == 32 else { throw Error.signingFailed }
        var message = [UInt8](idData)
        var auxiliary = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, auxiliary.count, &auxiliary) == errSecSuccess else {
            throw Error.randomKeyFailed
        }
        let signature = try key.signature(message: &message, auxiliaryRand: &auxiliary).dataRepresentation
        guard signature.count == 64 else { throw Error.signingFailed }
        return NostrIncomingEvent(
            id: id,
            pubkey: pubkey,
            createdAt: createdAt,
            kind: kind,
            tags: tags,
            content: content,
            sig: signature.map { String(format: "%02x", $0) }.joined()
        )
    }

    private static func eventId(
        pubkey: String,
        createdAt: Int64,
        kind: Int,
        tags: [[String]],
        content: String
    ) throws -> String {
        let commitment: [Any] = [0, pubkey, createdAt, kind, tags, content]
        let data = try JSONSerialization.data(withJSONObject: commitment, options: [.withoutEscapingSlashes])
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func randomPrivateKey() throws -> Data {
        for _ in 0..<128 {
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
                throw Error.randomKeyFailed
            }
            if (try? P256K.Schnorr.PrivateKey(dataRepresentation: bytes)) != nil { return Data(bytes) }
        }
        throw Error.randomKeyFailed
    }

    private static func publicKeyHex(for privateKey: Data) throws -> String {
        let key = try P256K.Schnorr.PrivateKey(dataRepresentation: [UInt8](privateKey))
        return key.xonly.bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func encodedString<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        let data = try encoder.encode(value)
        guard let string = String(data: data, encoding: .utf8) else { throw Error.canonicalizeFailed }
        return string
    }

    // MARK: - JSON helpers

    private static func decodeEvent(_ jsonString: String) -> NostrIncomingEvent? {
        guard let data = jsonString.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(NostrIncomingEvent.self, from: data)
    }

    private static func decodeRumor(_ jsonString: String, expectedAuthor: String) -> NostrRumor? {
        guard let data = jsonString.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let pubkey = (obj["pubkey"] as? String) ?? expectedAuthor
        guard pubkey == expectedAuthor else { return nil }
        let id = (obj["id"] as? String) ?? ""
        let createdAt = (obj["created_at"] as? NSNumber)?.int64Value
            ?? (obj["created_at"] as? Int64)
            ?? Int64((obj["created_at"] as? Int) ?? 0)
        let kind = (obj["kind"] as? Int) ?? 0
        let tags = (obj["tags"] as? [[String]]) ?? []
        let content = (obj["content"] as? String) ?? ""
        return NostrRumor(id: id, pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content)
    }
}

struct NostrReportRecipient {
    let pubkeyHex: String
    let relays: [String]
}

enum NostrErrorTransport {
    static func parseNprofile(_ value: String) -> NostrReportRecipient? {
        guard let bytes = try? Bech32.decode(hrp: "nprofile", bech32: value) else { return nil }
        var offset = 0
        var pubkey: String?
        var relays: [String] = []
        while offset + 2 <= bytes.count {
            let type = Int(bytes[offset]); offset += 1
            let length = Int(bytes[offset]); offset += 1
            guard offset + length <= bytes.count else { return nil }
            let field = Array(bytes[offset..<(offset + length)]); offset += length
            switch type {
            case 0 where field.count == 32 && pubkey == nil:
                pubkey = field.map { String(format: "%02x", $0) }.joined()
            case 1:
                if let relay = String(bytes: field, encoding: .utf8), relay.hasPrefix("wss://"), !relays.contains(relay) {
                    relays.append(relay)
                }
            default: break
            }
        }
        guard offset == bytes.count, let pubkey, !relays.isEmpty else { return nil }
        return NostrReportRecipient(pubkeyHex: pubkey, relays: Array(relays.prefix(3)))
    }

    static func send(recipient: NostrReportRecipient, report: NostrErrorReport) async throws -> NostrReportReceipt {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        let reportData = try encoder.encode(report)
        guard let reportJSON = String(data: reportData, encoding: .utf8) else { throw NIP17.Error.canonicalizeFailed }
        let giftWrap = try NIP17.wrap(plaintext: reportJSON, recipientPubkeyHex: recipient.pubkeyHex)
        let eventData = try encoder.encode(giftWrap)
        guard let eventObject = try JSONSerialization.jsonObject(with: eventData) as? [String: Any] else {
            throw NIP17.Error.canonicalizeFailed
        }
        let accepted = await withTaskGroup(of: Bool.self, returning: [Bool].self) { group in
            for relay in recipient.relays {
                group.addTask { await publish(relay: relay, eventId: giftWrap.id, event: eventObject) }
            }
            var results: [Bool] = []
            for await result in group { results.append(result) }
            return results
        }
        let acceptedCount = accepted.filter { $0 }.count
        guard acceptedCount > 0 else { throw URLError(.cannotConnectToHost) }
        return NostrReportReceipt(
            eventId: giftWrap.id,
            acceptedRelays: UInt32(acceptedCount),
            failedRelays: UInt32(accepted.count - acceptedCount)
        )
    }

    private static func publish(relay: String, eventId: String, event: [String: Any]) async -> Bool {
        guard let url = URL(string: relay),
              let commandData = try? JSONSerialization.data(withJSONObject: ["EVENT", event]),
              let command = String(data: commandData, encoding: .utf8) else { return false }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 15
        let session = URLSession(configuration: configuration)
        let task = session.webSocketTask(with: url)
        task.resume()
        do {
            try await task.send(.string(command))
            let accepted = await withTaskGroup(of: Bool.self, returning: Bool.self) { group in
                group.addTask {
                    do {
                        while true {
                            let message = try await task.receive()
                            let text: String
                            switch message {
                            case .string(let value): text = value
                            case .data(let data): text = String(data: data, encoding: .utf8) ?? ""
                            @unknown default: return false
                            }
                            guard let data = text.data(using: .utf8),
                                  let response = try? JSONSerialization.jsonObject(with: data) as? [Any],
                                  response.count >= 3 else { continue }
                            if response[0] as? String == "OK", response[1] as? String == eventId {
                                return response[2] as? Bool ?? false
                            }
                        }
                    } catch { return false }
                }
                group.addTask {
                    try? await Task.sleep(nanoseconds: 12_000_000_000)
                    return false
                }
                let first = await group.next() ?? false
                group.cancelAll()
                return first
            }
            task.cancel(with: .normalClosure, reason: nil)
            session.invalidateAndCancel()
            return accepted
        } catch {
            task.cancel(with: .goingAway, reason: nil)
            session.invalidateAndCancel()
            return false
        }
    }
}
