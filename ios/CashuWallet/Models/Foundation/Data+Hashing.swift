import Foundation
import CryptoKit

extension Data {
    /// SHA256 hash of the data
    func sha256() -> Data {
        let hash = SHA256.hash(data: self)
        return Data(hash)
    }

    func sha512() -> Data {
        Data(SHA512.hash(data: self))
    }
}


extension Data {
    var hexEncodedString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        guard hexString.utf8.count.isMultiple(of: 2),
              hexString.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hexString.utf8.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}

/// Canonical x-only identity for comparing compressed and bare P2PK keys.
enum P2PKPublicKey {
    static func validatedCompressed(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard let data = Data(hexString: trimmed) else { return nil }
        if data.count == 32 { return "02" + trimmed }
        guard data.count == 33, data.first == 2 || data.first == 3 else { return nil }
        return trimmed
    }

    static func normalizedForComparison(_ publicKey: String) -> String {
        let normalized = publicKey.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if normalized.count == 66, normalized.hasPrefix("02") || normalized.hasPrefix("03") {
            return String(normalized.dropFirst(2))
        }
        return normalized
    }
}
