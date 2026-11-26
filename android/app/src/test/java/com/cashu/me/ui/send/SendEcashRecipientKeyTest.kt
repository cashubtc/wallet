package com.cashu.me.ui.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendEcashRecipientKeyTest {
    @Test
    fun xOnlyInputIsCanonicalizedForEveryInputSource() {
        val xOnly = "A".repeat(64)
        val inputs = listOf(
            xOnly, // typed
            " $xOnly\n", // pasted
            xOnly.lowercase(), // scanned
        )

        val validations = inputs.map(::validateP2PKRecipientKey)

        assertEquals(1, validations.map { it.normalizedKey }.distinct().size)
        assertEquals("02${xOnly.lowercase()}", validations.first().normalizedKey)
        validations.forEach { assertNull(it.errorMessage) }
    }

    @Test
    fun invalidInputUsesTheSameErrorForEveryInputSource() {
        val invalid = "04${"b".repeat(64)}"

        val validations = listOf(invalid, " $invalid ", invalid.uppercase())
            .map(::validateP2PKRecipientKey)

        validations.forEach { assertNull(it.normalizedKey) }
        assertEquals(1, validations.map { it.errorMessage }.distinct().size)
        assertEquals(LockEcashCopy.InvalidRecipientKey, validations.first().errorMessage)
    }

    @Test
    fun blankInputIsAwaitingARecipientRatherThanInvalid() {
        val validation = validateP2PKRecipientKey(" \n ")

        assertNull(validation.normalizedKey)
        assertNull(validation.errorMessage)
    }

    @Test
    fun clipboardPresenceRequiresPrimaryClipAndTextMimeType() {
        assertTrue(hasClipboardText(hasPrimaryClip = true, hasTextMimeType = true))
        assertFalse(hasClipboardText(hasPrimaryClip = false, hasTextMimeType = true))
        assertFalse(hasClipboardText(hasPrimaryClip = true, hasTextMimeType = false))
        assertFalse(hasClipboardText(hasPrimaryClip = false, hasTextMimeType = false))
    }

    @Test
    fun p2pkQuickActionsReadClipboardLazilyOnTapOnly() {
        val key = "02${"a".repeat(64)}"
        var clipboardReads = 0

        val specs = p2pkScannerQuickActionSpecs(
            showQuickLockToMyKey = true,
            primaryPublicKey = key,
            hasClipboardText = true,
            readClipboardText = {
                clipboardReads += 1
                "  $key  "
            },
        )

        // Building quick actions on scanner open must never read clipboard text.
        assertEquals(0, clipboardReads)
        assertEquals(
            listOf(P2pkScannerQuickActionKind.LockToMyKey, P2pkScannerQuickActionKind.PasteKey),
            specs.map { it.kind },
        )

        // Tapping "Lock to my key" still never touches the clipboard.
        assertEquals(key, specs[0].resolveKey())
        assertEquals(0, clipboardReads)

        // Only tapping "Paste key" reads the clipboard.
        assertEquals(key, specs[1].resolveKey())
        assertEquals(1, clipboardReads)
    }

    @Test
    fun p2pkQuickActionsOmitPasteWhenClipboardHasNoTextAndDropBlankPaste() {
        val noClipboard = p2pkScannerQuickActionSpecs(
            showQuickLockToMyKey = false,
            primaryPublicKey = "02${"a".repeat(64)}",
            hasClipboardText = false,
            readClipboardText = { error("Should not read when clipboard has no text") },
        )
        assertTrue(noClipboard.isEmpty())

        val blankOnTap = p2pkScannerQuickActionSpecs(
            showQuickLockToMyKey = false,
            primaryPublicKey = null,
            hasClipboardText = true,
            readClipboardText = { "   \n  " },
        )
        assertEquals(1, blankOnTap.size)
        assertNull(blankOnTap.single().resolveKey())
    }
}
