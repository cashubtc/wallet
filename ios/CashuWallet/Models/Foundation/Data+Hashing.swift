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
