package org.cashu.wallet.ui.send

import org.junit.Assert.assertEquals
import org.junit.Test

class SendEcashP2PKTest {
    @Test
    fun p2pkKeyCandidateAcceptsBareAndSchemeWrappedKeys() {
        val key = "02abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"

        assertEquals(key, p2pkKeyCandidate(key))
        assertEquals(key, p2pkKeyCandidate("p2pk:$key"))
        assertEquals(key, p2pkKeyCandidate("cashu:p2pk:$key"))
        assertEquals(key, p2pkKeyCandidate("https://example.com/?p2pk=$key&label=alice"))
    }
}
