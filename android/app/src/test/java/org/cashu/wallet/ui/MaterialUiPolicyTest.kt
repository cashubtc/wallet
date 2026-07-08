package org.cashu.wallet.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialUiPolicyTest {
    private val sourceRoot = File("src/main/java/org/cashu/wallet")

    @Test
    fun materialComponentsCoverNativeAndroidSurfaces() {
        val expectations = mapOf(
            "ui/shell/WalletScaffold.kt" to listOf("NavigationBar", "NavigationBarItem"),
            "ui/history/HistoryScreen.kt" to listOf("CenterAlignedTopAppBar", "PullToRefreshBox", "AlertDialog", "IconButton"),
            "ui/mints/MintsScreen.kt" to listOf("CenterAlignedTopAppBar", "ModalBottomSheet", "AlertDialog", "SwipeToDismissBox"),
            "ui/settings/NostrScreen.kt" to listOf("SingleChoiceSegmentedButtonRow", "SegmentedButton", "AlertDialog"),
            "ui/settings/CurrencyPickerSheet.kt" to listOf("ModalBottomSheet"),
            "ui/components/MintChip.kt" to listOf("AssistChip"),
            "ui/components/Buttons.kt" to listOf("FilledTonalButton"),
        )

        expectations.forEach { (path, requiredTokens) ->
            val text = source(path).readText()
            requiredTokens.forEach { token ->
                assertTrue("Expected $path to use Material token $token", token in text)
            }
        }
    }

    @Test
    fun androidUiDoesNotImportIosLiquidGlassImplementationNames() {
        val forbiddenTokens = listOf(
            "LiquidGlass",
            "UIBlurEffect",
            "VisualEffect",
            ".ultraThinMaterial",
            "glassEffect",
        )

        androidUiSources().forEach { file ->
            val text = file.readText()
            forbiddenTokens.forEach { token ->
                assertFalse("Android UI source ${file.name} must not use iOS visual primitive $token", token in text)
            }
        }
    }

    @Test
    fun screenLevelSectionsStayOffDecorativeCards() {
        val cardRegex = Regex("""\b(ElevatedCard|OutlinedCard|Card)\s*\(""")

        androidUiSources()
            .filterNot { it.invariantSeparatorsPath.contains("/ui/components/") }
            .forEach { file ->
                assertFalse(
                    "Screen source ${file.name} should keep page sections on the bare canvas instead of cards.",
                    cardRegex.containsMatchIn(file.readText()),
                )
            }
    }

    private fun source(relativePath: String): File = File(sourceRoot, relativePath)

    private fun androidUiSources(): Sequence<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.invariantSeparatorsPath
                "/ui/" in path || "/Views/" in path
            }
}
