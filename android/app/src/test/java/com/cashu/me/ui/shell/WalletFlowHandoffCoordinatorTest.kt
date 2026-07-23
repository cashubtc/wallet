package com.cashu.me.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletFlowHandoffCoordinatorTest {
    @Test
    fun scannerOpensOnlyAfterSheetDismissalCompletes() {
        val events = mutableListOf<String>()
        val coordinator = WalletFlowHandoffCoordinator()

        coordinator.requestScanner { events += "close-sheet" }

        assertEquals(listOf("close-sheet"), events)

        coordinator.completeDismissal(
            openScanner = { events += "open-scanner" },
            openContactless = { events += "open-contactless" },
        )

        assertEquals(listOf("close-sheet", "open-scanner"), events)
    }

    @Test
    fun completedHandoffIsConsumedExactlyOnce() {
        val events = mutableListOf<String>()
        val coordinator = WalletFlowHandoffCoordinator()

        coordinator.requestContactless { events += "close-sheet" }
        repeat(2) {
            coordinator.completeDismissal(
                openScanner = { events += "open-scanner" },
                openContactless = { events += "open-contactless" },
            )
        }

        assertEquals(listOf("close-sheet", "open-contactless"), events)
    }
}
