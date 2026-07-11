package com.cashu.me.Views.Components

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerPermissionStateTest {
    @Test
    fun grantedResultStartsScanner() {
        assertEquals(
            CameraPermissionState.Granted,
            cameraPermissionResultState(granted = true, canShowRationale = false),
        )
    }

    @Test
    fun deniedResultThatCanBeRequestedAgainShowsAllowAction() {
        assertEquals(
            CameraPermissionState.CanRequest,
            cameraPermissionResultState(granted = false, canShowRationale = true),
        )
    }

    @Test
    fun permanentlyDeniedResultShowsSettingsAction() {
        assertEquals(
            CameraPermissionState.NeedsSettings,
            cameraPermissionResultState(granted = false, canShowRationale = false),
        )
    }
}
