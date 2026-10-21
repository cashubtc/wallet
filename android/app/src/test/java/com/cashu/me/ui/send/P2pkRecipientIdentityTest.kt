package com.cashu.me.ui.send

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pkRecipientIdentityTest {
    private val xOnlyKey = "a".repeat(64)

    @Test
    fun primaryCompressedKeyIsRecognizedAsOwnRecipient() {
        assertTrue(
            isPrimaryP2pkRecipient(
                recipient = "02$xOnlyKey",
                primaryPublicKey = "02$xOnlyKey",
            ),
        )
    }

    @Test
    fun storedXOnlyKeyIsRecognizedAsOwnRecipient() {
        assertTrue(
            isPrimaryP2pkRecipient(
                recipient = "03$xOnlyKey",
                primaryPublicKey = xOnlyKey,
            ),
        )
    }

    @Test
    fun externalKeyIsNotMarkedAsOwnRecipient() {
        assertFalse(
            isPrimaryP2pkRecipient(
                recipient = "02${"b".repeat(64)}",
                primaryPublicKey = "02$xOnlyKey",
            ),
        )
    }
}
