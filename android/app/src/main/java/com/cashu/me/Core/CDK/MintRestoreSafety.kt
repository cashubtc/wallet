package com.cashu.me.Core.CDK

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cashudevkit.ProofState
import org.cashudevkit.WalletSqliteDatabase

/** Issue sagas have no input proofs; all other same-mint operations retain the scan barrier. */
internal fun mintRestoreAllowed(sagas: List<String>, mintUrl: String, hasProtectedProofs: Boolean): Boolean {
    if (hasProtectedProofs) return false
    return sagas.all { raw ->
        runCatching {
            val saga = Json.parseToJsonElement(raw).jsonObject
            val mint = requireNotNull(saga["mint_url"]?.jsonPrimitive?.content)
            val kind = requireNotNull(saga["kind"]?.jsonPrimitive?.content)
            !mintRemovalUrlsMatch(mint, mintUrl) || kind == "mint"
        }.getOrDefault(false)
    }
}

internal suspend fun requireSafeMintRestore(db: WalletSqliteDatabase, mintUrl: String) {
    // CDK scans all units. A reservation in any unit at this mint must survive.
    val protected = db.getProofs(null, null, null, null).any {
        mintRemovalUrlsMatch(it.mintUrl.url, mintUrl) &&
            (it.usedByOperation != null || it.state in setOf(ProofState.RESERVED, ProofState.PENDING, ProofState.PENDING_SPENT))
    }
    check(mintRestoreAllowed(db.getIncompleteSagas(), mintUrl, protected)) {
        "This mint has an interrupted send or reserved funds. Recover that payment before restoring this mint. Other mints can still be restored."
    }
}

/** A seed import must scan an empty account before allocating issuance outputs. */
internal suspend fun prepareEmptyMintAccount(db: WalletSqliteDatabase, wallet: org.cashudevkit.Wallet, mintUrl: String) {
    if (db.getProofs(null, null, null, null).any { mintRemovalUrlsMatch(it.mintUrl.url, mintUrl) }) return
    requireSafeMintRestore(db, mintUrl)
    wallet.restore()
}
