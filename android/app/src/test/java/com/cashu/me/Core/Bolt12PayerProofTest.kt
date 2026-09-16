package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bolt12PayerProofTest {
    private val hex = "0123456789abcdef0123456789abcdef"
    private val lnp1 = "lnp1pgd9xatswphhyapqgf85c4p3xgsxgetkv4kx7urdv4h8gys0we5kucm9deax7urpd3sh57n0"
    private val mixedCase = "LNP1PGD9XATSWPHHYAPQGF85C4P3XGSXGETKV4KX7URDV4H8GYS0WE5KUCM9DEAX7URPD3SH57N0"

    @Test
    fun splitKeepsHexAsPreimageAndLnp1AsPayerProof() {
        val hexSplit = Bolt12PayerProof.split(hex)
        assertEquals(hex, hexSplit.preimage)
        assertNull(hexSplit.payerProof)

        val proofSplit = Bolt12PayerProof.split(lnp1)
        assertNull(proofSplit.preimage)
        assertEquals(lnp1, proofSplit.payerProof)

        assertEquals(lnp1, Bolt12PayerProof.split(mixedCase).payerProof)
        assertNull(Bolt12PayerProof.split(null).preimage)
        assertNull(Bolt12PayerProof.split("  ").payerProof)
    }

    @Test
    fun displayedProofPrefersSignedPayerProof() {
        assertEquals(lnp1, Bolt12PayerProof.displayedProof(lnp1, hex))
        assertEquals(lnp1, Bolt12PayerProof.displayedProof(mixedCase, hex))
        assertEquals(hex, Bolt12PayerProof.displayedProof(null, hex))
        assertNull(Bolt12PayerProof.displayedProof(null, null))
        assertFalse(Bolt12PayerProof.isSigned(hex))
        assertTrue(Bolt12PayerProof.isSigned(mixedCase))
        assertFalse(Bolt12PayerProof.isSigned("LNP1abc"))
        assertFalse(Bolt12PayerProof.isSigned("lnp1://evil.example/x"))
        assertFalse(Bolt12PayerProof.isSigned("lnp1"))
    }

    @Test
    fun verifyUrlExistsOnlyForSignedProofs() {
        assertEquals("https://lnproof.space/$lnp1", Bolt12PayerProof.verifyUrl(mixedCase))
        assertNull(Bolt12PayerProof.verifyUrl(hex))
        assertNull(Bolt12PayerProof.verifyUrl("  "))
        assertNull(Bolt12PayerProof.verifyUrl("lnp1://evil.example"))
    }
}
