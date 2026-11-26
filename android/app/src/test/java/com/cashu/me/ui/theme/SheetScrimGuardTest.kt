package com.cashu.me.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bottom sheet dims the screen behind it by the same amount.
 *
 * Material's `ModalBottomSheet` defaults to black at 32%, and that default is
 * applied per call, so a new sheet that forgets `scrimColor` would dim the
 * white canvas more heavily than every sheet around it. These tests fail in CI
 * instead of relying on a reviewer spotting a slightly greyer backdrop.
 */
class SheetScrimGuardTest {
    @Test
    fun `light sheets dim by the 20 percent iOS uses and dark keeps Material's 32`() {
        assertEquals(Color.Black.copy(alpha = 0.20f), LightCashuColors.sheetScrim)
        assertEquals(Color.Black.copy(alpha = 0.32f), DarkCashuColors.sheetScrim)
    }

    @Test
    fun `every ModalBottomSheet passes the shared scrim`() {
        val offenders = sources().flatMap { (path, text) ->
            sheetCalls(text)
                .filterNot { it.contains("scrimColor = CashuTheme.colors.sheetScrim") }
                .map { path }
        }

        assertTrue(
            "Pass scrimColor = CashuTheme.colors.sheetScrim to ModalBottomSheet, or the sheet " +
                "falls back to Material's 32% black. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scan finds the sheets it is guarding`() {
        val calls = sources().sumOf { (_, text) -> sheetCalls(text).size }
        assertTrue("expected to find ModalBottomSheet calls, found $calls", calls >= 10)
    }

    private val uiRoot: File =
        sequenceOf(File("src/main/java/com/cashu/me/ui"), File("app/src/main/java/com/cashu/me/ui"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate the ui source root")

    private fun sources(): List<Pair<String, String>> =
        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(uiRoot).path to it.readText() }
            .toList()

    /** The argument list of each direct `ModalBottomSheet(...)` call, comments removed. */
    private fun sheetCalls(source: String): List<String> {
        val code = source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
        return SHEET_CALL.findAll(code).map { match ->
            val open = match.range.last
            var depth = 0
            var end = open
            while (end < code.length) {
                when (code[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            code.substring(open, minOf(end + 1, code.length))
        }.toList()
    }

    private companion object {
        // A call, not the `fun CashuModalBottomSheet(` declaration or a call to that wrapper.
        val SHEET_CALL = Regex("""(?<![A-Za-z0-9_])ModalBottomSheet\(""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
