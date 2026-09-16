import Foundation

/// Signed BOLT12 payer proof (`lnp1…`) vs the Lightning preimage CDK stores
/// today in `Transaction.payment_proof`. The encodings stay separate so a
/// future FFI field or `create_bolt12_payer_proof(quote_id)` bind does not
/// overwrite NUT-25 preimages. Verify opens lnproof.space, which decodes the
/// proof entirely on-device.
enum Bolt12PayerProof {
    static let bech32Prefix = "lnp1"
    static let explorerHost = "https://lnproof.space/"
    /// Bech32 charset (BIP-173). Mixed case and non-charset bytes are not proofs.
    private static let bech32Charset = Set("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    /// Bech32 checksum is 6 characters; shorter payloads are not proofs.
    private static let minimumPayloadCount = 6

    static func isSigned(_ value: String?) -> Bool {
        guard let proof = normalized(value) else { return false }
        let payload = proof.dropFirst(bech32Prefix.count)
        return payload.count >= minimumPayloadCount
            && payload.allSatisfy { bech32Charset.contains($0) }
    }

    static func verifyURL(for value: String) -> URL? {
        guard let proof = normalized(value), isSigned(proof) else { return nil }
        return URL(string: explorerHost + proof)
    }

    struct Split {
        let preimage: String?
        let payerProof: String?
    }

    /// Classify a CDK `payment_proof` string. Hex (or any non-`lnp1`) stays
    /// the Lightning preimage; `lnp1…` is the signed payer proof.
    static func split(_ paymentProof: String?) -> Split {
        guard let value = paymentProof?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else {
            return Split(preimage: nil, payerProof: nil)
        }
        if isSigned(value) {
            return Split(preimage: nil, payerProof: normalized(value))
        }
        return Split(preimage: value, payerProof: nil)
    }

    /// Value shown on the Payment Proof receipt row: signed proof when
    /// present, otherwise the hex preimage.
    static func displayedProof(payerProof: String?, preimage: String?) -> String? {
        if let proof = normalized(payerProof), isSigned(proof) { return proof }
        return preimage
    }

    private static func normalized(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else { return nil }
        let proof = value.lowercased()
        guard proof.hasPrefix(bech32Prefix) else { return nil }
        return proof
    }
}
