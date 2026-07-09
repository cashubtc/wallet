package org.cashu.wallet.Core.CDK

internal suspend fun estimateReceiveFee(
    proofCount: Int,
    calculateFee: suspend () -> Long,
    keysetFee: suspend () -> Long,
): Long {
    if (proofCount <= 0) return 0
    return runCatching { calculateFee() }
        .getOrElse { keysetFee() * proofCount }
}
