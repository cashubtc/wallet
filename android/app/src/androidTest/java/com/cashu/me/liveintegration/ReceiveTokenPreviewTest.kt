package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.KeySetInfo
import org.cashudevkit.MintUrl
import org.cashudevkit.Token
import org.cashudevkit.WalletSqliteDatabase
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Real CDK previews with persisted metadata; these tests require no mint server. */
class ReceiveTokenPreviewTest {
    private val mint = "https://preview.invalid"
    private val ids = listOf("0011223344556677", "00ffeeddccbbaa99")

    @Test fun roundsAcrossKeysetsWithoutRedeeming() = runBlocking {
        for ((fees, proofIds, expected) in listOf(
            Triple(listOf(0uL, 0uL), listOf(ids[0]), 0L),
            Triple(listOf(100uL, 0uL), listOf(ids[0]), 1L),
            Triple(listOf(1001uL, 0uL), listOf(ids[0]), 2L),
            Triple(listOf(100uL, 800uL), listOf(ids[0], ids[1], ids[0]), 1L),
        )) {
            withGateway(ids.zip(fees).map { (id, fee) -> KeySetInfo(id, CurrencyUnit.Sat, true, fee) }) { gateway ->
                val preview = gateway.previewReceiveToken(token(proofIds))
                assertEquals(expected, preview.fee)
                assertEquals(proofIds.size * 8L, preview.info.amount)
                assertEquals("sat", preview.info.unit)
                assertEquals("Preview memo", preview.info.memo)
                assertEquals(proofIds.size, preview.info.proofCount)
                assertEquals(0L, gateway.totalBalance(mint))
                assertTrue(gateway.listTransactions(mapOf(mint to listOf("sat"))).isEmpty())
                assertTrue(gateway.receiveRecoveryCandidates().isEmpty())
            }
        }
    }

    @Test fun resolvesShortIDv2InTheTokensUnit() = runBlocking {
        val encoded = "cashuBpGFtd2h0dHBzOi8vcHJldmlldy5pbnZhbGlkYXVjdXNkYWRsUHJldmlldyBtZW1vYXSBomFpSAGrq6urq6urYXCBo2FhCGFzaXByZXZpZXctMGFjWCECeb5mfvncu6xVoGKVzocLBwKb_NstzijZWfKBWxb4F5g"
        assertThrows(Exception::class.java) { Token.decode(encoded).proofsSimple() }
        withGateway(listOf(KeySetInfo("01abababababababababababababababababababababababababababababababab", CurrencyUnit.Usd, true, 100uL))) { gateway ->
            val preview = gateway.previewReceiveToken(encoded)
            assertEquals(8L, preview.info.amount)
            assertEquals("usd", preview.info.unit)
            assertEquals("Preview memo", preview.info.memo)
            assertEquals(1, preview.info.proofCount)
            assertEquals(1L, preview.fee)
            assertEquals(0L, gateway.unitBalance(mint, "usd"))
        }
    }

    @Test fun missingKeysetFeesPropagate() = runBlocking {
        withGateway(listOf(KeySetInfo(ids[0], CurrencyUnit.Sat, true, 0uL))) { gateway ->
            try {
                gateway.previewReceiveToken(token(listOf(ids[1])))
                fail("A missing fee must not become a free receive")
            } catch (_: org.cashudevkit.FfiException) { }
        }
    }

    private suspend fun withGateway(keysets: List<KeySetInfo>, check: suspend (CdkWalletGatewayImpl) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-${UUID.randomUUID()}")
        directory.mkdirs()
        val gateway = CdkWalletGatewayImpl()
        try {
            val path = File(directory, "preview.sqlite").absolutePath
            val db = WalletSqliteDatabase(path)
            try {
                db.addMint(MintUrl(mint), null)
                db.addMintKeysets(MintUrl(mint), keysets)
            } finally { db.close() }
            gateway.openWalletRepository(gateway.generateMnemonic(), path)
            check(gateway)
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    private fun token(proofIds: List<String>): String {
        val proofs = JSONArray()
        proofIds.forEachIndexed { index, id ->
            proofs.put(JSONObject().put("amount", 8).put("id", id).put("secret", "preview-$index")
                .put("C", "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"))
        }
        val payload = JSONObject().put("token", JSONArray().put(JSONObject().put("mint", mint).put("proofs", proofs)))
            .put("unit", "sat").put("memo", "Preview memo")
        return "cashuA" + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
