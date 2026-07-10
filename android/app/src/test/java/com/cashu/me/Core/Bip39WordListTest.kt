package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39WordListTest {

    @Test
    fun wordListHasExactly2048UniqueWords() {
        assertEquals(2048, Bip39WordList.words.size)
    }

    @Test
    fun wordListSpansAbandonToZoo() {
        assertTrue("abandon" in Bip39WordList.words)
        assertTrue("zoo" in Bip39WordList.words)
        assertTrue("cashu" !in Bip39WordList.words)
    }

    @Test
    fun normalizeCollapsesWhitespaceAndLowercases() {
        assertEquals(
            "abandon ability able",
            Bip39WordList.normalize("  Abandon\n ABILITY\t able  "),
        )
    }

    @Test
    fun validPhraseHasNoInvalidIndices() {
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(emptyList<Int>(), Bip39WordList.invalidWordIndices(phrase))
    }

    @Test
    fun invalidWordsAreReportedByNormalizedIndex() {
        // "cashu" (index 1) and "zzz" (index 3) are not BIP-39 words.
        assertEquals(
            listOf(1, 3),
            Bip39WordList.invalidWordIndices("abandon cashu zoo zzz"),
        )
    }

    @Test
    fun casingAndSpacingDoNotFlagValidWords() {
        assertEquals(
            emptyList<Int>(),
            Bip39WordList.invalidWordIndices("  ZOO   Wolf\nabandon "),
        )
    }

    @Test
    fun emptyInputHasNoInvalidIndices() {
        assertEquals(emptyList<Int>(), Bip39WordList.invalidWordIndices("   "))
    }
}
