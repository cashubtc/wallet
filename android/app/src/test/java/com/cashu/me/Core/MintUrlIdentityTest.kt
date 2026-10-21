package com.cashu.me.Core

import com.cashu.me.Models.MintUrlIdentity
import org.junit.Assert.*
import org.junit.Test

class MintUrlIdentityTest {
    @Test fun identityNormalizesAuthorityWithoutMergingDistinctMints() {
        assertEquals(MintUrlIdentity.comparisonKey("HTTPS://MINT.EXAMPLE/Case/"), MintUrlIdentity.comparisonKey("https://mint.example/Case"))
        assertNotEquals(MintUrlIdentity.comparisonKey("https://mint.example/Case"), MintUrlIdentity.comparisonKey("https://mint.example/case"))
        assertNotEquals(MintUrlIdentity.comparisonKey("http://mint.example"), MintUrlIdentity.comparisonKey("https://mint.example"))
        assertNotEquals(MintUrlIdentity.comparisonKey("https://mint.example?q=A"), MintUrlIdentity.comparisonKey("https://mint.example?q=a"))
    }

    @Test fun identityPreservesEscapedPathsAndPorts() {
        assertEquals("https://mint.example:8443/Mint%2FOne", MintUrlIdentity.comparisonKey("https://MINT.EXAMPLE:8443/Mint%2FOne/"))
        assertNull(MintUrlIdentity.comparisonKey("  "))
    }

    @Test fun validationAllowsCleartextOnlyForExplicitLocalTestPolicy() {
        assertNotNull(MintUrlIdentity.validationError("http://localhost"))
        assertNull(MintUrlIdentity.validationError("http://localhost", true))
        assertNotNull(MintUrlIdentity.validationError("http://mint.example", true))
        assertEquals("https://mint.example/Case", MintUrlIdentity.withDefaultScheme(" mint.example/Case/ "))
        assertEquals("mint.example/Case", MintUrlIdentity.display("HTTPS://mint.example/Case/"))
    }
}
