package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.DepositRecoveryRequest
import com.cashu.me.Core.DepositRecoveryStatus
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.cashudevkit.WalletSqliteDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DepositRecoveryInstrumentedTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val mint get() = args.getString("cashu.cdkMintUrl") ?: "http://127.0.0.1:3339"

    @Test fun seedOnlyAccountScansBeforeAllocatingNewMintOutputs() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "counter-recovery-${UUID.randomUUID()}")
        directory.mkdirs()
        val gateway = CdkWalletGatewayImpl()
        try {
            val seed = gateway.generateMnemonic()
            for ((name, amount) in listOf("original" to 16L, "restored" to 100L)) {
                gateway.openWalletRepository(seed, File(directory, "$name.db").path)
                gateway.ensureWallet(mint, "sat")
                val quote = gateway.createMintQuote(amount, PaymentMethodKind.Bolt11, mint, "sat")
                repeat(40) {
                    if (gateway.checkMintQuote(quote.id).amountPaid == 0L) delay(100)
                }
                assertEquals(amount, gateway.mintTokens(quote.id))
                assertEquals(if (name == "original") 16L else 116L, gateway.totalBalance(mint))
                gateway.closeWalletRepository()
            }
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun lostUsdDepositRequiresDeeperSearchAndCanOnlyBeClaimedOnce() = runBlocking {
        withQuote(PaymentMethodKind.Bolt11, "usd", 1_000u) { seed, quoteId, expected ->
            AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint, mnemonic = seed).use { fixture ->
                val manager = fixture.container.walletManager
                val shallow = DepositRecoveryRequest(mint, quoteId, PaymentMethodKind.Bolt11, "usd")
                assertEquals(DepositRecoveryStatus.KeyNotFound, manager.recoverDeposit(shallow).status)
                val deep = shallow.copy(searchLimit = 10_000u)
                val result = manager.recoverDeposit(deep)
                assertEquals(DepositRecoveryStatus.Recovered, result.status)
                assertEquals(21L, result.amount)
                assertEquals("usd", result.unit)
                assertEquals(21L, manager.state.value.balancesByUnit["usd"])
                assertEquals(DepositRecoveryStatus.AlreadyClaimed, manager.recoverDeposit(deep).status)
                assertEquals(21L, manager.state.value.balancesByUnit["usd"])
                assertEquals(1, manager.state.value.transactions.count { it.quoteId == quoteId })
            }
        }
    }

    @Test fun originalSeedRecoversLightningOffer() = recoverMethod(PaymentMethodKind.Bolt12)
    @Test fun originalSeedRecoversOnchainDeposit() = recoverMethod(PaymentMethodKind.Onchain)

    private fun recoverMethod(method: PaymentMethodKind) = runBlocking {
        withQuote(method, "sat") { seed, quoteId, expected ->
            AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint, mnemonic = seed).use { fixture ->
                val manager = fixture.container.walletManager
                val request = DepositRecoveryRequest(mint, quoteId, method)
                val result = manager.recoverDeposit(request)
                assertEquals(DepositRecoveryStatus.Recovered, result.status)
                assertEquals(expected, result.amount)
                assertEquals(DepositRecoveryStatus.AlreadyClaimed, manager.recoverDeposit(request).status)
                assertEquals(expected, manager.state.value.balance)
            }
        }
    }

    @Test fun wrongSeedCannotClaimTheDepositOrTrackItsMint() = runBlocking {
        withQuote(PaymentMethodKind.Bolt11, "sat") { _, quoteId, _ ->
            AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint).use { fixture ->
                val result = fixture.container.walletManager.recoverDeposit(DepositRecoveryRequest(mint, quoteId, PaymentMethodKind.Bolt11))
                assertEquals(DepositRecoveryStatus.KeyNotFound, result.status)
                assertEquals(0L, fixture.container.walletManager.state.value.balance)
                assertTrue(fixture.container.walletStore.loadMints().isEmpty())
            }
        }
    }

    @Test fun previouslyIssuedProofsAreRestoredBeforeClaimingTheLostDeposit() = runBlocking {
        withQuote(PaymentMethodKind.Bolt11, "sat", priorIssued = 13) { seed, quoteId, expected ->
            AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint, mnemonic = seed).use { fixture ->
                val manager = fixture.container.walletManager
                val request = DepositRecoveryRequest(mint, quoteId, PaymentMethodKind.Bolt11)
                val result = manager.recoverDeposit(request)
                assertEquals(DepositRecoveryStatus.Recovered, result.status)
                assertEquals(expected, result.amount)
                assertEquals(13 + expected, manager.state.value.balance)
                assertEquals(DepositRecoveryStatus.AlreadyClaimed, manager.recoverDeposit(request).status)
                assertEquals(13 + expected, manager.state.value.balance)
            }
        }
    }

    private suspend fun withQuote(method: PaymentMethodKind, unit: String, keyIndex: UInt = 0u, priorIssued: Long = 0,
        exercise: suspend (String, String, Long) -> Unit) {
        assumeTrue(args.getString("cashu.nativeWalletLocalMintIntegration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val gateway = CdkWalletGatewayImpl()
        try {
            val seed = gateway.generateMnemonic()
            val path = File(directory, "original.db").path
            gateway.openWalletRepository(seed, path)
            gateway.ensureWallet(mint, unit)
            if (priorIssued > 0) {
                val prior = gateway.createMintQuote(priorIssued, PaymentMethodKind.Bolt11, mint, unit)
                repeat(40) {
                    if (gateway.checkMintQuote(prior.id).amountPaid > 0) return@repeat
                    delay(100)
                }
                assertEquals(priorIssued, gateway.mintTokens(prior.id))
            }
            WalletSqliteDatabase(path).use { db -> db.incrementDerivationCounter("nut20_quote", keyIndex) }
            val created = gateway.createMintQuote(21, method, mint, unit)
            var quote = created
            repeat(40) {
                if (quote.amountPaid > 0 || quote.state == MintQuoteState.Paid) return@repeat
                quote = gateway.checkMintQuote(created.id)
                delay(100)
            }
            assertTrue("Local fake mint must report paid credit", quote.amountPaid > 0)
            gateway.closeWalletRepository()
            exercise(seed, quote.id, quote.amountPaid)
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }
}
