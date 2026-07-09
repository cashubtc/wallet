package org.cashu.wallet.App

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidReleaseConfigurationTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun manifestDisablesBackupAndSentryAutoInit() {
        val manifest = xmlFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        val application = manifest.documentElement
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals("false", application.getAttributeNS(androidNamespace, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(androidNamespace, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(androidNamespace, "fullBackupContent"))
        val cleartextTraffic = application.getAttributeNS(androidNamespace, "usesCleartextTraffic")
        assertTrue(cleartextTraffic.isBlank() || cleartextTraffic == "false")

        val mainActivity = application.getElementsByTagName("activity")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .first { it.getAttributeNS(androidNamespace, "name") == ".App.MainActivity" }
        val backInvokedAtApp = application.getAttributeNS(androidNamespace, "enableOnBackInvokedCallback")
        val backInvokedAtActivity = mainActivity.getAttributeNS(androidNamespace, "enableOnBackInvokedCallback")
        assertTrue(backInvokedAtApp == "true" || backInvokedAtActivity == "true")

        val metaData = application.getElementsByTagName("meta-data")
        val sentryAutoInit = (0 until metaData.length)
            .map { metaData.item(it) as Element }
            .first { it.getAttributeNS(androidNamespace, "name") == "io.sentry.auto-init" }
        assertEquals("false", sentryAutoInit.getAttributeNS(androidNamespace, "value"))
    }

    @Test
    fun backupRulesExcludeSecureStoreAndWalletDatabase() {
        val backupRules = xmlFile("src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/backup_rules.xml")
        val dataExtractionRules = xmlFile(
            "src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
        )

        assertContainsExclusion(backupRules.documentElement, domain = "sharedpref", path = "secure_store.xml")
        assertContainsExclusion(backupRules.documentElement, domain = "file", path = "cashu-kotlin/")
        assertContainsExclusion(dataExtractionRules.documentElement, domain = "sharedpref", path = "secure_store.xml")
        assertContainsExclusion(dataExtractionRules.documentElement, domain = "file", path = "cashu-kotlin/")
    }

    @Test
    fun appLockUsesSecureWindowFlag() {
        val source = sourceFile(
            "src/main/java/org/cashu/wallet/ui/security/AppLockGate.kt",
            "app/src/main/java/org/cashu/wallet/ui/security/AppLockGate.kt",
        ).readText()

        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(source.contains("fun SecureWindowEffect"))
    }

    @Test
    fun sentryServiceChecksOptInBeforeStarting() {
        val source = sourceFile(
            "src/main/java/org/cashu/wallet/Core/SentryService.kt",
            "app/src/main/java/org/cashu/wallet/Core/SentryService.kt",
        ).readText()

        assertTrue(source.contains("if (!isEnabled()"))
        assertTrue(source.contains("gateway.start(BuildConfig.SENTRY_DSN)"))
    }

    private fun assertContainsExclusion(root: Element, domain: String, path: String) {
        val excludes = root.getElementsByTagName("exclude")
        val found = (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .any { it.getAttribute("domain") == domain && it.getAttribute("path") == path }
        assertTrue("Expected exclusion for $domain:$path", found)
    }

    private fun xmlFile(vararg candidates: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(sourceFile(*candidates))

    private fun sourceFile(vararg candidates: String): File {
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }
        return roots
            .flatMap { root -> candidates.asSequence().map { File(root, it) } }
            .firstOrNull { it.exists() }
            ?: error("Missing test fixture: ${candidates.joinToString()}")
    }
}
