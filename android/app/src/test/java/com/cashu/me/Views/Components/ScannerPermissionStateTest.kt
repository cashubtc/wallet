package com.cashu.me.Views.Components

import com.cashu.me.Core.AnimatedUrDecodeUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun frameProcessorCompletesOncePerSessionAndRearmsOnNewSession() {
        val processor = ScannerFrameProcessor()
        var resetCount = 0

        val first = processor.process(
            sessionId = 1,
            rawCode = "  cashuAfirst  ",
            receiveUrPart = { error("Non-UR payload should not invoke UR decoder") },
            resetUrDecoder = { resetCount += 1 },
        )
        assertEquals(ScannerFrameOutcome.Completed(sessionId = 1, content = "cashuAfirst"), first)
        assertEquals(1, resetCount)

        // Duplicate frames in the same session are dropped off-main.
        val duplicate = processor.process(
            sessionId = 1,
            rawCode = "cashuAfirst",
            receiveUrPart = { error("Duplicate frame should be dropped") },
            resetUrDecoder = { resetCount += 1 },
        )
        assertNull(duplicate)
        assertEquals(1, resetCount)

        // Rapidly reopening bumps sessionId, immediately re-arming processing.
        val reopened = processor.process(
            sessionId = 2,
            rawCode = "cashuAsecond",
            receiveUrPart = { error("Non-UR payload should not invoke UR decoder") },
            resetUrDecoder = { resetCount += 1 },
        )
        assertEquals(ScannerFrameOutcome.Completed(sessionId = 2, content = "cashuAsecond"), reopened)
        assertEquals(2, resetCount)
    }

    @Test
    fun frameProcessorStreamsAnimatedUrProgressAndCompletesOnce() {
        val processor = ScannerFrameProcessor()
        val updates = ArrayDeque(
            listOf(
                AnimatedUrDecodeUpdate(content = null, progress = 0.5f),
                AnimatedUrDecodeUpdate(content = null, progress = 0.5f, errorMessage = "Bad frame"),
                AnimatedUrDecodeUpdate(content = "cashuAdecoded", progress = 1f),
            ),
        )

        val progress = processor.process(
            sessionId = 1,
            rawCode = "ur:bytes/1-2/abc",
            receiveUrPart = { updates.removeFirst() },
            resetUrDecoder = {},
        )
        assertEquals(
            ScannerFrameOutcome.Progress(sessionId = 1, progress = 0.5f, errorMessage = null),
            progress,
        )

        val frameError = processor.process(
            sessionId = 1,
            rawCode = "ur:bytes/bad",
            receiveUrPart = { updates.removeFirst() },
            resetUrDecoder = {},
        )
        assertEquals(
            ScannerFrameOutcome.Progress(sessionId = 1, progress = 0.5f, errorMessage = "Bad frame"),
            frameError,
        )

        val completed = processor.process(
            sessionId = 1,
            rawCode = "ur:bytes/2-2/def",
            receiveUrPart = { updates.removeFirst() },
            resetUrDecoder = {},
        )
        assertEquals(
            ScannerFrameOutcome.Completed(sessionId = 1, content = "cashuAdecoded"),
            completed,
        )

        // Trailing UR frames in the same session do not re-enter the decoder.
        val trailing = processor.process(
            sessionId = 1,
            rawCode = "ur:bytes/1-2/abc",
            receiveUrPart = { error("Trailing frame after completion should be ignored") },
            resetUrDecoder = {},
        )
        assertNull(trailing)
    }
}
