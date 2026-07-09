import Foundation
@testable import CashuWallet

/// Volatile in-memory StorageProtocol implementation for unit tests.
final class InMemoryStorage: StorageProtocol {
    private var data: [String: Data] = [:]
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func set<T: Codable>(_ value: T, forKey key: String) throws {
        data[key] = try encoder.encode(value)
    }

    func get<T: Codable>(forKey key: String) throws -> T? {
        guard let raw = data[key] else { return nil }
        return try decoder.decode(T.self, from: raw)
    }

    func remove(forKey key: String) throws {
        data.removeValue(forKey: key)
    }

    func exists(forKey key: String) -> Bool {
        data[key] != nil
    }

    func keys(withPrefix prefix: String) -> [String] {
        data.keys.filter { $0.hasPrefix(prefix) }
    }
}

/// Volatile in-memory SecureStorageProtocol implementation for unit tests, so
/// WalletStore's Keychain-backed token storage never touches a real Keychain.
final class InMemorySecureStorage: SecureStorageProtocol {
    private(set) var secrets: [String: String] = [:]

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
