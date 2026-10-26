package com.cashu.me.Core.CDK

internal suspend fun estimateReceiveFee(
    proofCount: Int,
    calculateFee: suspend () -> Long,
    keysetFee: suspend () -> Long,
): Long {
    if (proofCount <= 0) return 0
    return runCatching { calculateFee() }
        .getOrElse { keysetFee() * proofCount }
}

/** NUT-02: round once after summing input fees; missing metadata must propagate. */
internal suspend fun receiveFeeForKeysets(
    keysetIds: List<String>,
    lookup: suspend (String) -> Long,
): Long {
    val fees = mutableMapOf<String, Long>()
    var total = 0L
    for (id in keysetIds) {
        val fee = fees.getOrPut(id) { lookup(id) }
        require(fee >= 0) { "The mint returned an invalid receive fee." }
        total = Math.addExact(total, fee)
    }
    return total / 1000 + if (total % 1000 == 0L) 0 else 1
}
