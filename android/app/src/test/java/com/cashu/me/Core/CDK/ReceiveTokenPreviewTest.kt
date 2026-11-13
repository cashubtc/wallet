package com.cashu.me.Core.CDK

import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.Proof
import org.cashudevkit.TokenData
import org.junit.Assert.*
import org.junit.Test

class ReceiveTokenPreviewTest {
    @Test fun unknownFeeIsUnavailableButZeroIsConfirmed() {
        assertThrows(IllegalArgumentException::class.java) { data(null).toReceiveTokenPreview() }
        assertEquals(0L, data(0uL).toReceiveTokenPreview().fee)
    }

    @Test fun preservesResolvedMetadataInEachUnit() {
        for ((unit, expected) in listOf(CurrencyUnit.Sat to "sat", CurrencyUnit.Usd to "usd",
            CurrencyUnit.Eur to "eur", CurrencyUnit.Msat to "msat")) {
            val preview = data(2uL, unit).toReceiveTokenPreview()
            assertEquals(8L, preview.info.amount)
            assertEquals("https://preview.invalid", preview.info.mint)
            assertEquals(expected, preview.info.unit)
            assertEquals("Preview memo", preview.info.memo)
            assertEquals(1, preview.info.proofCount)
            assertEquals(2L, preview.fee)
        }
    }

    @Test fun rejectsAmountsAndFeesThatCannotBeRepresentedInTheApp() {
        assertThrows(IllegalArgumentException::class.java) { data(ULong.MAX_VALUE).toReceiveTokenPreview() }
        assertThrows(IllegalArgumentException::class.java) {
            data(0uL).copy(value = Amount(ULong.MAX_VALUE)).toReceiveTokenPreview()
        }
    }

    private fun data(fee: ULong?, unit: CurrencyUnit = CurrencyUnit.Sat) = TokenData(
        mintUrl = MintUrl("https://preview.invalid"),
        proofs = listOf(Proof(Amount(8uL), "preview", "", "", null, null, null)),
        memo = "Preview memo", value = Amount(8uL), unit = unit, redeemFee = fee?.let(::Amount),
    )
}
