package com.cashu.me.Core.CDK

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.cashudevkit.WalletSqliteDatabase
import org.cashudevkit.ProofState
import com.cashu.me.Core.toDomainUnit

internal suspend fun abandonedReservationOwners(db: WalletSqliteDatabase): Set<String> {
    val candidates = db.getProofs(null, null, listOf(ProofState.RESERVED), null).mapNotNull { it.usedByOperation }.toSet()
    val owners = mutableSetOf<String>()
    for (owner in candidates) {
        if (runCatching { java.util.UUID.fromString(owner) }.isSuccess && db.getSaga(owner) == null) owners += owner
    }
    return owners
}

internal fun abandonedReservationRecovery(db: WalletSqliteDatabase, owners: Set<String>) = AbandonedReservationRecovery(
    eligibleOwners = owners,
    loadProofs = {
        db.getProofs(null, null, null, null).map {
            ReservationProof(it.y.hex, it.usedByOperation, it.mintUrl.url, it.unit.toDomainUnit(), it.state == ProofState.RESERVED)
        }
    },
    hasOperation = { db.getSaga(it) != null },
    hasTransaction = { owner -> db.listTransactions(null, null, null).any { it.sagaId == owner } },
    hasActivity = { mintUrl, unit ->
        db.getIncompleteSagas().any { json ->
            val identity = Json.parseToJsonElement(json).jsonObject
            mintRemovalUrlsMatch(identity.getValue("mint_url").jsonPrimitive.content, mintUrl) &&
                identity.getValue("unit").jsonPrimitive.content == unit
        }
    },
    checkUnspent = { mintUrl, ids -> AbandonedReservationRecovery.checkMint(mintUrl, ids) },
    release = { db.releaseProofs(it) },
)

internal data class ReservationProof(
    val id: String,
    val owner: String?,
    val mintUrl: String,
    val unit: String,
    val reserved: Boolean,
)

/** Runs while the host holds its repository operation lock. */
internal class AbandonedReservationRecovery(
    private val eligibleOwners: Set<String>,
    private val loadProofs: suspend () -> List<ReservationProof>,
    private val hasOperation: suspend (String) -> Boolean,
    private val hasTransaction: suspend (String) -> Boolean,
    private val hasActivity: suspend (String, String) -> Boolean,
    private val checkUnspent: suspend (String, List<String>) -> Boolean,
    private val release: suspend (String) -> Unit,
) {
    suspend fun recover(mintUrl: String, unit: String): Set<String> {
        val recovered = mutableSetOf<String>()
        val snapshot = loadProofs()
        for (owner in eligibleOwners.sorted()) {
            val proofs = snapshot.filter { it.owner == owner }
            if (proofs.isEmpty() || proofs.any { !it.reserved || it.unit != unit || !mintRemovalUrlsMatch(it.mintUrl, mintUrl) }) continue
            val ids = proofs.map { it.id }
            if (ids.toSet().size != ids.size) continue
            if (hasOperation(owner) || hasTransaction(owner) || hasActivity(mintUrl, unit)) continue
            if (!checkUnspent(mintUrl, ids)) continue
            currentCoroutineContext().ensureActive()
            val current = loadProofs().filter { it.owner == owner }
            if (current.size != proofs.size || current.toSet() != proofs.toSet()) continue
            if (hasOperation(owner) || hasTransaction(owner) || hasActivity(mintUrl, unit)) continue
            currentCoroutineContext().ensureActive()
            release(owner)
            recovered += owner
        }
        return recovered
    }

    companion object {
        fun allUnspent(ys: List<String>, response: String): Boolean = runCatching {
            if (ys.isEmpty() || ys.toSet().size != ys.size) return false
            val states = Json.parseToJsonElement(response).jsonObject.getValue("states").jsonArray
            val returned = states.map { it.jsonObject.getValue("Y").jsonPrimitive.content.lowercase() }
            returned.size == ys.size && returned.toSet() == ys.map(String::lowercase).toSet() &&
                states.all { it.jsonObject.getValue("state").jsonPrimitive.content == "UNSPENT" }
        }.getOrDefault(false)

        fun checkMint(mintUrl: String, ys: List<String>): Boolean {
            val connection = URL(mintUrl.trimEnd('/') + "/v1/checkstate").openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = buildJsonObject { putJsonArray("Ys") { ys.forEach { add(it) } } }.toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                connection.responseCode == 200 && connection.inputStream.bufferedReader().use { allUnspent(ys, it.readText()) }
            } finally { connection.disconnect() }
        }
    }
}
