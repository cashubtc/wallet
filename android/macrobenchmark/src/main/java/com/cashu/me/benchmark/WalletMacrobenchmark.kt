package com.cashu.me.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun settingsOpenAndScrollFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                startActivityAndWait()
                device.wait(Until.hasObject(By.text("Wallet")), WaitMs)
            },
        ) {
            openTab("Settings")
            device.wait(Until.hasObject(By.text("Settings")), WaitMs)
            scrollVertical()
            device.findObject(By.text("Privacy"))?.click()
            device.wait(Until.hasObject(By.text("Privacy")), WaitMs)
            scrollVertical()
        }
    }

    @Test
    fun homeHistoryAndMintsListScrollFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                startActivityAndWait()
                device.wait(Until.hasObject(By.text("Wallet")), WaitMs)
            },
        ) {
            scrollVertical()
            openTab("History")
            device.wait(Until.hasObject(By.text("History")), WaitMs)
            scrollVertical()
            openTab("Mints")
            device.wait(Until.hasObject(By.text("Mints")), WaitMs)
            scrollVertical()
        }
    }

    private fun MacrobenchmarkScope.openTab(label: String) {
        val tab = device.wait(Until.findObject(By.text(label)), WaitMs)
        tab?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.scrollVertical() {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 4,
            20,
        )
        device.waitForIdle()
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight / 4,
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            20,
        )
        device.waitForIdle()
    }

    private companion object {
        const val WaitMs = 5_000L
        const val TargetPackageArg = "targetPackage"
        const val CompilationModeArg = "compilationMode"
        const val DefaultTargetPackage = "com.cashu.me.debug"
    }

    private val targetPackage: String
        get() = InstrumentationRegistry.getArguments().getString(TargetPackageArg) ?: DefaultTargetPackage

    private val compilationMode: CompilationMode
        get() = when (InstrumentationRegistry.getArguments().getString(CompilationModeArg)?.lowercase()) {
            "none" -> CompilationMode.None()
            else -> CompilationMode.Partial()
        }
}
