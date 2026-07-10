package com.cashu.me.Core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the unknown-mint receive glitch: claiming a token from
 * a mint that isn't tracked yet must add that mint (so refreshBalance and
 * loadTransactions, which only consider tracked mints, pick up the funds).
 */
class ReceivedTokenMintTrackingTest {
    @Test
    fun tracksTheTokenMintAfterASuccessfulReceive() = runBlocking {
        var tracked: String? = null

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            tokenMintUrl = { "https://mint.example.com" },
            ensureMintTracked = { tracked = it },
        )

        assertEquals("https://mint.example.com", tracked)
    }

    @Test
    fun tracksPlainHttpMintUrls() = runBlocking {
        var tracked: String? = null

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            tokenMintUrl = { "http://localhost:3338" },
            ensureMintTracked = { tracked = it },
        )

        assertEquals("http://localhost:3338", tracked)
    }

    @Test
    fun neverTracksTheUnknownMintPlaceholder() = runBlocking {
        var tracked: String? = null

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            // TokenParser.tokenInfo falls back to this literal when the mint
            // URL can't be decoded from the token.
            tokenMintUrl = { "Unknown mint" },
            ensureMintTracked = { tracked = it },
        )

        assertNull(tracked)
    }

    @Test
    fun skipsTrackingWhenTheMintUrlCannotBeResolved() = runBlocking {
        var tracked: String? = null

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            tokenMintUrl = { null },
            ensureMintTracked = { tracked = it },
        )

        assertNull(tracked)
    }

    @Test
    fun swallowsTrackingFailuresSoTheClaimStillSucceeds() = runBlocking {
        var reported: Throwable? = null

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            tokenMintUrl = { "https://mint.example.com" },
            onTrackingFailed = { reported = it },
            ensureMintTracked = { throw IllegalStateException("mint info fetch failed") },
        )

        assertEquals("mint info fetch failed", reported?.message)
    }

    @Test
    fun swallowsMintUrlResolutionFailures() = runBlocking {
        var reported: Throwable? = null
        var tracked = false

        trackMintForReceivedToken(
            tokenString = "cashuBtoken",
            tokenMintUrl = { throw IllegalStateException("decode failed") },
            onTrackingFailed = { reported = it },
            ensureMintTracked = { tracked = true },
        )

        assertEquals("decode failed", reported?.message)
        assertTrue(!tracked)
    }

    @Test
    fun defaultResolverIgnoresNonCashuPayloads() = runBlocking {
        var tracked = false

        // Uses the real TokenParser default: a non-token payload resolves to
        // no mint URL, so nothing is tracked and nothing throws.
        trackMintForReceivedToken(
            tokenString = "lnbc1invoice",
            ensureMintTracked = { tracked = true },
        )

        assertTrue(!tracked)
    }
}
