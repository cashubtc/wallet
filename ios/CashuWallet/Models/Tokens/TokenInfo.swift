import Foundation
import Cdk

struct TokenInfo {
    let amount: UInt64
    let mint: String
    let unit: String
    let memo: String?
    let proofCount: Int
    
    /// Parse a cashu token string
    static func parse(_ tokenString: String) -> TokenInfo? {
        TokenParser.tokenInfo(from: tokenString)
    }
}

/// CDK's resolved metadata and a known redemption fee, ready for review.
struct ReceiveTokenPreview {
    let info: TokenInfo
    let fee: UInt64

    init(data: TokenData) throws {
        guard let fee = data.redeemFee?.value else {
            throw WalletError.networkError("The mint's receive fee is not available. Try again.")
        }
        info = TokenInfo(
            amount: data.value.value,
            mint: data.mintUrl.url,
            unit: PaymentRequestDecoder.unitDescription(data.unit),
            memo: data.memo,
            proofCount: data.proofs.count
        )
        self.fee = fee
    }
}
