import Foundation
import SwiftUI
import Security
import P256K

// MARK: - Signer Type

/// Type of Nostr key source (matches cashu.me implementation)
enum NostrSignerType: String, Codable, CaseIterable {
    case seed = "SEED"           // Uses wallet seed (default)
    case privateKey = "PRIVATEKEY" // Uses custom nsec/private key
    
    var displayName: String {
        switch self {
        case .seed: return "Wallet Seed"
        case .privateKey: return "Custom Key"
        }
    }
    
    var description: String {
        switch self {
        case .seed: return "Derived from your wallet's seed phrase"
        case .privateKey: return "Use a custom Nostr private key"
        }
    }
}

/// Service for Nostr key management and NIP-98 HTTP authentication
/// Uses swift-secp256k1 P256K library for proper BIP-340 Schnorr signatures
@MainActor
class NostrService: ObservableObject {
    static let shared = NostrService()
    
    // MARK: - Published Properties
    
    /// Public key in hex format (32 bytes x-only)
    @Published var publicKeyHex: String = ""
    
    /// Public key in bech32 npub format
    @Published var npub: String = ""
    
    /// Private key in bech32 nsec format (for display/export)
    @Published var nsec: String = ""
    
    /// Whether the keypair has been initialized
    @Published var isInitialized: Bool = false
    
    /// Current signer type
    @Published var signerType: NostrSignerType = .seed {
        didSet {
            settingsStore.nostrSignerType = signerType.rawValue
        }
    }
    
    // MARK: - Private Properties
    
    private var privateKey: P256K.Schnorr.PrivateKey?
    private var currentSeed: Data?  // Store seed for switching back
    private let keychain = KeychainService()
    private let settingsStore = SettingsStore.shared
    
    // MARK: - Initialization
    
    private init() {
        // Load saved signer type
        if let savedType = settingsStore.nostrSignerType,
           let type = NostrSignerType(rawValue: savedType) {
            self.signerType = type
        }
    }
    
    // MARK: - Key Derivation
    
    /// Derive Nostr keypair from the wallet's NIP-06 secret key
    /// The caller passes the NIP-06-derived secret key (m/44'/1237'/0'/0/0
    /// from the BIP39 seed); the first 32 bytes are used as the private key.
    func deriveKeypair(from seed: Data) throws {
        guard seed.count >= 32 else {
            throw NostrError.invalidSeed
        }
        
        // Store seed for later use (switching back to seed-derived key)
        self.currentSeed = seed
        
        // Check if we should use a custom private key
        if signerType == .privateKey, let customKeyHex = try? keychain.loadNostrPrivateKey() {
            try setPrivateKey(fromHex: customKeyHex)
            return
        }
        
        // Use seed-derived key
        signerType = .seed
        let privateKeyBytes = Array(seed.prefix(32))
        try setPrivateKey(fromBytes: privateKeyBytes)
    }
    
    /// Set private key from raw bytes
    private func setPrivateKey(fromBytes privateKeyBytes: [UInt8]) throws {
        do {
            // Create P256K Schnorr private key
            let privKey = try P256K.Schnorr.PrivateKey(dataRepresentation: privateKeyBytes)
            self.privateKey = privKey
            
            // Get x-only public key (32 bytes)
            let xonlyPubKey = privKey.xonly
            let pubKeyBytes = xonlyPubKey.bytes
            
            // Set public key hex
            self.publicKeyHex = Data(pubKeyBytes).hexEncodedString
            
            // Convert to bech32 npub
            self.npub = try Bech32.encode(hrp: "npub", data: Data(pubKeyBytes))
            
            // Convert private key to nsec for display/export
            self.nsec = try Bech32.encode(hrp: "nsec", data: Data(privateKeyBytes))
            
            self.isInitialized = true
            
            print("Nostr keypair initialized (\(signerType.displayName)):")
            print("  pubkey: \(publicKeyHex)")
            print("  npub: \(npub)")
        } catch {
            print("Failed to create Nostr keys: \(error)")
            throw NostrError.keypairCreationFailed
        }
    }
    
    /// Set private key from hex string
    private func setPrivateKey(fromHex hex: String) throws {
        guard let data = Data(hexString: hex), data.count == 32 else {
            throw NostrError.invalidPrivateKey
        }
        try setPrivateKey(fromBytes: Array(data))
    }
    
    // MARK: - Key Management
    
    /// Generate a new random Nostr keypair
    func generateRandomKeypair() throws {
        var randomBytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, 32, &randomBytes)
        guard status == errSecSuccess else {
            throw NostrError.randomGenerationFailed
        }
        
        // Save to keychain
        let hexKey = Data(randomBytes).hexEncodedString
        try keychain.saveNostrPrivateKey(hexKey)
        
        // Set as current key
        signerType = .privateKey
        try setPrivateKey(fromBytes: randomBytes)
        
        print("Generated new random Nostr keypair")
    }
    
    /// Import a private key from nsec (bech32) format
    func importNsec(_ nsecString: String) throws {
        // Decode nsec to get raw private key bytes
        let privateKeyBytes = try Bech32.decode(hrp: "nsec", bech32: nsecString)
        guard privateKeyBytes.count == 32 else {
            throw NostrError.invalidNsec
        }
        
        // Save to keychain
        let hexKey = Data(privateKeyBytes).hexEncodedString
        try keychain.saveNostrPrivateKey(hexKey)
        
        // Set as current key
        signerType = .privateKey
        try setPrivateKey(fromBytes: privateKeyBytes)
        
        print("Imported nsec successfully")
    }
    
    /// Reset to seed-derived key
    func resetToSeedKey() throws {
        guard let seed = currentSeed else {
            throw NostrError.noSeedAvailable
        }
        
        // Delete custom key from keychain
        try? keychain.deleteNostrPrivateKey()
        
        // Switch back to seed
        signerType = .seed
        let privateKeyBytes = Array(seed.prefix(32))
        try setPrivateKey(fromBytes: privateKeyBytes)
        
        print("Reset to seed-derived Nostr key")
    }
    
    /// Switch signer type (called from settings)
    func switchSignerType(to type: NostrSignerType) throws {
        switch type {
        case .seed:
            try resetToSeedKey()
        case .privateKey:
            // Check if we have a custom key stored
            if let customKeyHex = try? keychain.loadNostrPrivateKey() {
                signerType = .privateKey
                try setPrivateKey(fromHex: customKeyHex)
            } else {
                // No custom key exists, generate one
                try generateRandomKeypair()
            }
        }
    }
    
    /// Check if a custom private key is stored
    func hasCustomPrivateKey() -> Bool {
        return keychain.hasNostrPrivateKey()
    }

    func resetForWalletBoundary(deleteStoredKey: Bool = true) {
        if deleteStoredKey {
            try? keychain.deleteNostrPrivateKey()
        }
        settingsStore.nostrSignerType = nil
        signerType = .seed
        privateKey = nil
        currentSeed = nil
        publicKeyHex = ""
        npub = ""
        nsec = ""
        isInitialized = false
    }

    /// Current signing key in its copyable Nostr representation.
    func getNsec() -> String { nsec }

    /// Get private key hex (for internal use)
    func getPrivateKeyHex() -> String? {
        guard let privKey = privateKey else { return nil }
        // Get raw bytes from the private key
        let bytes = privKey.dataRepresentation
        return Data(bytes).hexEncodedString
    }
    
}

// MARK: - Error Types

enum NostrError: LocalizedError {
    case invalidSeed
    case keypairCreationFailed
    case notInitialized
    case signingFailed
    case invalidPrivateKey
    case invalidNsec
    case randomGenerationFailed
    case noSeedAvailable
    case bech32DecodingFailed
    
    var errorDescription: String? {
        switch self {
        case .invalidSeed:
            return "Invalid seed data - must be at least 32 bytes"
        case .keypairCreationFailed:
            return "Failed to create Nostr keypair"
        case .notInitialized:
            return "Nostr service not initialized"
        case .signingFailed:
            return "Failed to sign Nostr event"
        case .invalidPrivateKey:
            return "Invalid private key - must be 32 bytes"
        case .invalidNsec:
            return "Invalid nsec format"
        case .randomGenerationFailed:
            return "Failed to generate random bytes"
        case .noSeedAvailable:
            return "No wallet seed available"
        case .bech32DecodingFailed:
            return "Failed to decode bech32 string"
        }
    }
}

// MARK: - Bech32 Encoding

/// Simple Bech32 encoder/decoder for npub/nsec format
enum Bech32 {
    private static let charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    
    static func encode(hrp: String, data: Data) throws -> String {
        // Convert 8-bit data to 5-bit groups
        let converted = try convertBits(data: Array(data), fromBits: 8, toBits: 5, pad: true)
        
        // Calculate checksum
        let checksum = createChecksum(hrp: hrp, data: converted)
        
        // Encode data + checksum
        var result = hrp + "1"
        for value in converted + checksum {
            let index = charset.index(charset.startIndex, offsetBy: Int(value))
            result.append(charset[index])
        }
        
        return result
    }
    
    /// Decode a bech32 string to raw bytes
    static func decode(hrp expectedHrp: String, bech32: String) throws -> [UInt8] {
        // Nostr key import historically accepts mixed case; Bitcoin framing stays strict.
        let decoded = try decodeWords(bech32.lowercased())
        guard decoded.hrp == expectedHrp, decoded.checksum == 1 else { throw NostrError.bech32DecodingFailed }
        return try convertBits(data: decoded.words, fromBits: 5, toBits: 8, pad: false)
    }

    /// Validates Bech32/Bech32m framing while preserving the five-bit payload
    /// for protocols such as SegWit that include a version word.
    static func decodeWords(_ encoded: String) throws -> (hrp: String, words: [UInt8], checksum: UInt32) {
        let scalars = encoded.unicodeScalars
        guard scalars.allSatisfy({ (33...126).contains($0.value) }),
              encoded == encoded.lowercased() || encoded == encoded.uppercased() else { throw NostrError.bech32DecodingFailed }
        let lowered = encoded.lowercased()
        guard let separator = lowered.lastIndex(of: "1"), separator != lowered.startIndex else { throw NostrError.bech32DecodingFailed }
        let hrp = String(lowered[..<separator])
        let payload = lowered[lowered.index(after: separator)...]
        guard payload.count >= 6 else { throw NostrError.bech32DecodingFailed }
        var words: [UInt8] = []
        for character in payload {
            guard let index = charset.firstIndex(of: character) else { throw NostrError.bech32DecodingFailed }
            words.append(UInt8(charset.distance(from: charset.startIndex, to: index)))
        }
        let checksum = polymod(hrpExpand(hrp) + words)
        guard checksum == 1 || checksum == 0x2bc830a3 else { throw NostrError.bech32DecodingFailed }
        return (hrp, Array(words.dropLast(6)), checksum)
    }

    static func convertBits(data: [UInt8], fromBits: Int, toBits: Int, pad: Bool) throws -> [UInt8] {
        var acc = 0
        var bits = 0
        var result: [UInt8] = []
        let maxv = (1 << toBits) - 1
        let maxAccumulator = (1 << (fromBits + toBits - 1)) - 1
        
        for value in data {
            guard (Int(value) >> fromBits) == 0 else { throw NostrError.bech32DecodingFailed }
            acc = ((acc << fromBits) | Int(value)) & maxAccumulator
            bits += fromBits
            while bits >= toBits {
                bits -= toBits
                result.append(UInt8((acc >> bits) & maxv))
            }
        }
        
        if pad {
            if bits > 0 {
                result.append(UInt8((acc << (toBits - bits)) & maxv))
            }
        } else if bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0 {
            throw NSError(domain: "Bech32", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid padding"])
        }
        
        return result
    }
    
    private static func polymod(_ values: [UInt8]) -> UInt32 {
        let generator: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        var chk: UInt32 = 1
        
        for value in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ UInt32(value)
            for i in 0..<5 {
                if ((top >> i) & 1) == 1 {
                    chk ^= generator[i]
                }
            }
        }
        
        return chk
    }
    
    private static func hrpExpand(_ hrp: String) -> [UInt8] {
        var result: [UInt8] = []
        for char in hrp {
            guard let ascii = char.asciiValue else { return [] }
            result.append(UInt8(ascii >> 5))
        }
        result.append(0)
        for char in hrp {
            guard let ascii = char.asciiValue else { return [] }
            result.append(UInt8(ascii & 31))
        }
        return result
    }
    
    private static func createChecksum(hrp: String, data: [UInt8]) -> [UInt8] {
        let values = hrpExpand(hrp) + data + [0, 0, 0, 0, 0, 0]
        let polymod = polymod(values) ^ 1
        var result: [UInt8] = []
        for i in 0..<6 {
            result.append(UInt8((polymod >> (5 * (5 - i))) & 31))
        }
        return result
    }
}
