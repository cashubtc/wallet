import Foundation
import Cdk
import CryptoKit

/// Typed contents of a BIP-21 `bitcoin:` URI (or a bare address).
/// `amountSats`/`label`/`message` come from the query parameters that
/// `normalizeBitcoinRequest` would otherwise discard.
struct Bip21Payment: Equatable {
    let address: String
    let amountSats: UInt64?
    let label: String?
    let message: String?
    /// Bundled unified-QR Lightning invoice/offer, if any (`lightning=` param).
    let lightning: String?
}

enum PaymentRequestParser {
    static func normalizeLightningRequest(_ request: String) -> String {
        let trimmedRequest = request.trimmingCharacters(in: .whitespacesAndNewlines)
        let lightningPrefixes = ["lightning://", "lightning:"]

        for prefix in lightningPrefixes where trimmedRequest.lowercased().hasPrefix(prefix) {
            return String(trimmedRequest.dropFirst(prefix.count))
        }

        return trimmedRequest
    }

    static func normalizeBitcoinRequest(_ request: String) -> String {
        let trimmedRequest = request.trimmingCharacters(in: .whitespacesAndNewlines)
        let bitcoinPrefixes = ["bitcoin://", "bitcoin:"]

        let withoutScheme: String
        if let prefix = bitcoinPrefixes.first(where: { trimmedRequest.lowercased().hasPrefix($0) }) {
            withoutScheme = String(trimmedRequest.dropFirst(prefix.count))
        } else {
            withoutScheme = trimmedRequest
        }

        return withoutScheme.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
            .first
            .map(String.init) ?? withoutScheme
    }

    static func isBitcoinAddress(_ request: String) -> Bool {
        let normalizedRequest = normalizeBitcoinRequest(request)
        return BitcoinAddressValidator.isValidAddress(normalizedRequest)
    }

    /// Parse a BIP-21 URI (or bare address) into its typed parts, so callers
    /// can honor `amount=`/`label=` instead of silently dropping them. Returns
    /// nil when the address part isn't a valid Bitcoin address.
    static func parseBitcoinRequest(_ request: String) -> Bip21Payment? {
        let address = normalizeBitcoinRequest(request)
        guard BitcoinAddressValidator.isValidAddress(address) else {
            return nil
        }

        let trimmedRequest = request.trimmingCharacters(in: .whitespacesAndNewlines)
        let querySplit = trimmedRequest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        guard querySplit.count == 2 else {
            return Bip21Payment(address: address, amountSats: nil, label: nil, message: nil, lightning: nil)
        }

        var amountSats: UInt64?
        var label: String?
        var message: String?
        var lightning: String?

        for pair in querySplit[1].split(separator: "&") {
            let keyValue = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard keyValue.count == 2 else { continue }
            let key = keyValue[0].lowercased()
            let rawValue = String(keyValue[1])

            switch key {
            case "amount":
                amountSats = satsFromBTCAmount(rawValue)
            case "label":
                label = rawValue.removingPercentEncoding ?? rawValue
            case "message":
                message = rawValue.removingPercentEncoding ?? rawValue
            case "lightning", "lightninginvoice":
                lightning = rawValue.removingPercentEncoding ?? rawValue
            default:
                break
            }
        }

        return Bip21Payment(
            address: address,
            amountSats: amountSats,
            label: label?.isEmpty == false ? label : nil,
            message: message?.isEmpty == false ? message : nil,
            lightning: lightning?.isEmpty == false ? lightning : nil
        )
    }

    /// BIP-21 `amount` is a decimal BTC value with `.` as separator. Returns
    /// nil for malformed, negative, fractional-sat, or out-of-range values —
    /// a bad amount must degrade to manual entry, never to a wrong figure.
    private static func satsFromBTCAmount(_ value: String) -> UInt64? {
        let parts = value.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        guard !parts.isEmpty, parts.count <= 2,
              parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else {
            return nil
        }

        guard let wholeBTC = UInt64(parts[0]), wholeBTC <= 21_000_000 else {
            return nil
        }

        var fractionalSats: UInt64 = 0
        if parts.count == 2 {
            let fraction = parts[1]
            guard fraction.count <= 8 else { return nil }
            let padded = fraction.padding(toLength: 8, withPad: "0", startingAt: 0)
            guard let sats = UInt64(padded) else { return nil }
            fractionalSats = sats
        }

        let sats = wholeBTC * 100_000_000 + fractionalSats
        return sats > 0 ? sats : nil
    }

    static func isHumanReadableLightningAddress(_ request: String) -> Bool {
        let trimmedRequest = request.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let atIndex = trimmedRequest.firstIndex(of: "@") else { return false }
        let user = trimmedRequest[trimmedRequest.startIndex..<atIndex]
        let domain = trimmedRequest[trimmedRequest.index(after: atIndex)...]
        return !user.isEmpty && domain.contains(".") && !domain.hasPrefix(".") && !domain.hasSuffix(".")
    }

    static func paymentMethod(for request: String) -> PaymentMethodKind? {
        if isHumanReadableLightningAddress(request) {
            return nil
        }

        let normalizedRequest = PaymentRequestDecoder.encodedLightningRequest(from: request)
            ?? normalizeLightningRequest(request)
        if !normalizedRequest.isEmpty,
           let decodedRequest = try? decodeInvoice(invoiceStr: normalizedRequest) {
            switch decodedRequest.paymentType {
            case .bolt11:
                return .bolt11
            case .bolt12:
                return .bolt12
            }
        }

        if isBitcoinAddress(request) {
            return .onchain
        }

        return nil
    }
}

private enum BitcoinAddressValidator {
    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    private static let bech32Alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    private static let bech32Generator = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    private static let base58VersionBytes: Set<UInt8> = [0x00, 0x05, 0x6f, 0xc4]
    private static let bech32Hrp: Set<String> = ["bc", "tb", "bcrt"]

    private static var base58Values: [Character: Int] {
        Dictionary(uniqueKeysWithValues: base58Alphabet.enumerated().map { ($0.element, $0.offset) })
    }

    private static var bech32Values: [Character: Int] {
        Dictionary(uniqueKeysWithValues: bech32Alphabet.enumerated().map { ($0.element, $0.offset) })
    }

    static func isValidAddress(_ address: String) -> Bool {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else {
            return false
        }

        if trimmed.contains("@") {
            return false
        }

        return isValidBech32Address(trimmed) || isValidBase58CheckAddress(trimmed)
    }

    private static func isValidBase58CheckAddress(_ address: String) -> Bool {
        guard let decoded = decodeBase58(address), decoded.count == 25 else {
            return false
        }

        let payload = decoded.prefix(21)
        let checksum = decoded.suffix(4)
        guard let version = payload.first, base58VersionBytes.contains(version) else {
            return false
        }

        let firstHash = SHA256.hash(data: Data(payload))
        let secondHash = SHA256.hash(data: Data(firstHash))
        return Array(secondHash.prefix(4)) == Array(checksum)
    }

    private static func decodeBase58(_ address: String) -> [UInt8]? {
        var bytes: [UInt8] = []

        for character in address {
            guard let value = base58Values[character] else {
                return nil
            }

            var carry = value
            for index in bytes.indices.reversed() {
                let total = Int(bytes[index]) * 58 + carry
                bytes[index] = UInt8(total & 0xff)
                carry = total >> 8
            }

            while carry > 0 {
                bytes.insert(UInt8(carry & 0xff), at: 0)
                carry >>= 8
            }
        }

        let leadingZeroes = address.prefix { $0 == "1" }.count
        return Array(repeating: UInt8(0), count: leadingZeroes) + bytes
    }

    private static func isValidBech32Address(_ address: String) -> Bool {
        let scalars = address.unicodeScalars
        guard scalars.allSatisfy({ $0.value >= 33 && $0.value <= 126 }) else {
            return false
        }

        let hasLowercase = scalars.contains { CharacterSet.lowercaseLetters.contains($0) }
        let hasUppercase = scalars.contains { CharacterSet.uppercaseLetters.contains($0) }
        guard !(hasLowercase && hasUppercase) else {
            return false
        }

        let lowercasedAddress = address.lowercased()
        guard let separatorIndex = lowercasedAddress.lastIndex(of: "1") else {
            return false
        }

        let hrp = String(lowercasedAddress[..<separatorIndex])
        let dataStart = lowercasedAddress.index(after: separatorIndex)
        let dataPart = lowercasedAddress[dataStart...]

        guard bech32Hrp.contains(hrp), dataPart.count >= 7 else {
            return false
        }

        let dataValues = dataPart.compactMap { bech32Values[$0] }
        guard dataValues.count == dataPart.count else {
            return false
        }

        let checksum = bech32Polymod(hrpExpand(hrp) + dataValues)
        let encodingIsBech32 = checksum == 1
        let encodingIsBech32m = checksum == 0x2bc830a3
        guard encodingIsBech32 || encodingIsBech32m else {
            return false
        }

        let witnessData = Array(dataValues.dropLast(6))
        guard let version = witnessData.first, version <= 16 else {
            return false
        }

        guard let program = convertBits(Array(witnessData.dropFirst()), fromBits: 5, toBits: 8, pad: false),
              (2...40).contains(program.count) else {
            return false
        }

        if version == 0 {
            return encodingIsBech32 && (program.count == 20 || program.count == 32)
        }

        return encodingIsBech32m
    }

    private static func hrpExpand(_ hrp: String) -> [Int] {
        let scalars = hrp.unicodeScalars.map { Int($0.value) }
        return scalars.map { $0 >> 5 } + [0] + scalars.map { $0 & 31 }
    }

    private static func bech32Polymod(_ values: [Int]) -> Int {
        var checksum = 1

        for value in values {
            let top = checksum >> 25
            checksum = ((checksum & 0x1ffffff) << 5) ^ value

            for index in 0..<5 where ((top >> index) & 1) == 1 {
                checksum ^= bech32Generator[index]
            }
        }

        return checksum
    }

    private static func convertBits(_ data: [Int], fromBits: Int, toBits: Int, pad: Bool) -> [UInt8]? {
        var accumulator = 0
        var bits = 0
        var result: [UInt8] = []
        let maxValue = (1 << toBits) - 1
        let maxAccumulator = (1 << (fromBits + toBits - 1)) - 1

        for value in data {
            guard value >= 0 && (value >> fromBits) == 0 else {
                return nil
            }

            accumulator = ((accumulator << fromBits) | value) & maxAccumulator
            bits += fromBits

            while bits >= toBits {
                bits -= toBits
                result.append(UInt8((accumulator >> bits) & maxValue))
            }
        }

        if pad {
            if bits > 0 {
                result.append(UInt8((accumulator << (toBits - bits)) & maxValue))
            }
        } else if bits >= fromBits || ((accumulator << (toBits - bits)) & maxValue) != 0 {
            return nil
        }

        return result
    }
}
