package com.cashu.me.Core

import com.cashu.me.Core.Protocols.SecureStorage
import com.cashu.me.Core.Protocols.deleteMnemonic
import com.cashu.me.Core.Protocols.deleteNostrPrivateKey
import com.cashu.me.Core.Protocols.hasMnemonic
import com.cashu.me.Core.Protocols.hasNostrPrivateKey
import com.cashu.me.Core.Protocols.loadMnemonic
import com.cashu.me.Core.Protocols.loadNostrPrivateKey
import com.cashu.me.Core.Protocols.saveMnemonic
import com.cashu.me.Core.Protocols.saveNostrPrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureStorageProtocolTest {
    @Test
    fun mnemonicConvenienceMethodsUseSwiftCompatibleKey() {
        val storage = MemorySecureStorage()

        assertFalse(storage.hasMnemonic())
        assertNull(storage.loadMnemonic())

        storage.saveMnemonic("abandon abandon")

        assertTrue(storage.hasMnemonic())
        assertEquals("abandon abandon", storage.loadMnemonic())

        storage.deleteMnemonic()

        assertFalse(storage.hasMnemonic())
        assertNull(storage.loadMnemonic())
    }

    @Test
    fun nostrPrivateKeyConvenienceMethodsUseSwiftCompatibleKey() {
        val storage = MemorySecureStorage()

        storage.saveNostrPrivateKey("abcdef")

        assertTrue(storage.hasNostrPrivateKey())
        assertEquals("abcdef", storage.loadNostrPrivateKey())

        storage.deleteNostrPrivateKey()

        assertFalse(storage.hasNostrPrivateKey())
        assertNull(storage.loadNostrPrivateKey())
    }

    private class MemorySecureStorage : SecureStorage {
        private val values = mutableMapOf<String, String>()

        override fun loadString(key: String): String? = values[key]
        override fun saveString(key: String, value: String) {
            values[key] = value
        }

        override fun delete(key: String) {
            values.remove(key)
        }

        override fun contains(key: String): Boolean = key in values
    }
}
