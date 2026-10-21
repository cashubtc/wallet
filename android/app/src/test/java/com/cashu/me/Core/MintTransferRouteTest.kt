package com.cashu.me.Core

import com.cashu.me.Core.MintTransferEligibility.Blocker
import com.cashu.me.Core.MintTransferRoute.Slot
import com.cashu.me.Models.Bolt11SatCapability
import com.cashu.me.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS parity: `MintTransferRouteTests` in `MintTransferEligibilityTests.swift`. */
class MintTransferRouteTest {

    // MARK: - Opening pair

    @Test
    fun needsTwoMints() {
        assertNull(MintTransferRoute.initial(listOf(mint(A, 100)), activeMintUrl = A))
    }

    /** From the Mints list the largest balance moves toward the default mint. */
    @Test
    fun opensFromTheLargestBalanceTowardTheDefaultMint() {
        val route = MintTransferRoute.initial(
            listOf(mint(A, 10), mint(B, 500), mint(C, 0)),
            activeMintUrl = A,
        )

        assertEquals(MintTransferRoute(sourceMintUrl = B, destinationMintUrl = A), route)
    }

    @Test
    fun defaultMintHoldingTheMostSendsToTheNextMintInTheList() {
        val route = MintTransferRoute.initial(
            listOf(mint(A, 500), mint(B, 10), mint(C, 0)),
            activeMintUrl = A,
        )

        assertEquals(MintTransferRoute(sourceMintUrl = A, destinationMintUrl = B), route)
    }

    @Test
    fun equalBalancesFallBackToListOrder() {
        val route = MintTransferRoute.initial(
            listOf(mint(A, 100), mint(B, 100), mint(C, 100)),
            activeMintUrl = C,
        )

        assertEquals(MintTransferRoute(sourceMintUrl = A, destinationMintUrl = C), route)
    }

    @Test
    fun openedFromAFundedMintSendsFromIt() {
        val route = MintTransferRoute.initial(
            listOf(mint(A, 500), mint(B, 10), mint(C, 0)),
            activeMintUrl = A,
            openedFromMintUrl = B,
        )

        assertEquals(MintTransferRoute(sourceMintUrl = B, destinationMintUrl = A), route)
    }

    /**
     * An empty mint has nothing to send, so opening a transfer on it means
     * filling it.
     */
    @Test
    fun openedFromAnEmptyMintSendsToIt() {
        val route = MintTransferRoute.initial(
            listOf(mint(A, 500), mint(B, 10), mint(C, 0)),
            activeMintUrl = A,
            openedFromMintUrl = C,
        )

        assertEquals(MintTransferRoute(sourceMintUrl = A, destinationMintUrl = C), route)
    }

    @Test
    fun mintThatCannotSendIsNotTheOpeningSource() {
        val receiveOnly = mint(A, 900).copy(bolt11Sat = Bolt11SatCapability(canMint = true, canMelt = false))

        val route = MintTransferRoute.initial(listOf(receiveOnly, mint(B, 10)), activeMintUrl = B)

        assertEquals(MintTransferRoute(sourceMintUrl = B, destinationMintUrl = A), route)
    }

    // MARK: - Changing the pair

    @Test
    fun swapTradesTheTwoMints() {
        val route = MintTransferRoute(sourceMintUrl = A, destinationMintUrl = B)

        assertEquals(MintTransferRoute(sourceMintUrl = B, destinationMintUrl = A), route.swapped)
    }

    @Test
    fun choosingAnotherMintReplacesOnlyThatEnd() {
        val route = MintTransferRoute(sourceMintUrl = A, destinationMintUrl = B)

        assertEquals(MintTransferRoute(sourceMintUrl = C, destinationMintUrl = B), route.choosing(C, Slot.Source))
        assertEquals(MintTransferRoute(sourceMintUrl = A, destinationMintUrl = C), route.choosing(C, Slot.Destination))
    }

    /** A pick never dead-ends on "already in use". */
    @Test
    fun choosingTheMintAtTheOtherEndSwaps() {
        val route = MintTransferRoute(sourceMintUrl = A, destinationMintUrl = B)

        assertEquals(route.swapped, route.choosing(B, Slot.Source))
        assertEquals(route.swapped, route.choosing("https://A.example:443/", Slot.Destination))
    }

    // MARK: - Entry

    @Test
    fun entryIsReadyOnlyForAnAmountTheSourceHolds() {
        val source = mint(A, 100)
        val destination = mint(B, 0)

        assertEquals(MintTransferEntry.Empty, MintTransferEntry.validation(0, source, destination))
        assertEquals(MintTransferEntry.Ready, MintTransferEntry.validation(100, source, destination))
        assertEquals(MintTransferEntry.OverBalance, MintTransferEntry.validation(101, source, destination))
    }

    @Test
    fun blockedPairIsReportedBeforeTheAmount() {
        val sendOnly = mint(B, 0).copy(bolt11Sat = Bolt11SatCapability(canMint = false, canMelt = true))

        assertEquals(
            MintTransferEntry.Blocked(Blocker.DestinationCannotReceive),
            MintTransferEntry.validation(0, mint(A, 100), sendOnly),
        )
    }

    /** The fee comes on top, so a typed whole balance is flagged; a Max quote never is. */
    @Test
    fun wholeBalanceIsFlaggedOnlyWhenTypedByHand() {
        val source = mint(A, 100)

        assertTrue(MintTransferEntry.isWholeBalance(MintTransferEntry.Ready, 100, source, holdsMaxQuote = false))
        assertFalse(MintTransferEntry.isWholeBalance(MintTransferEntry.Ready, 100, source, holdsMaxQuote = true))
        assertFalse(MintTransferEntry.isWholeBalance(MintTransferEntry.Ready, 99, source, holdsMaxQuote = false))
        assertFalse(MintTransferEntry.isWholeBalance(MintTransferEntry.OverBalance, 100, source, holdsMaxQuote = false))
    }

    private fun mint(url: String, balance: Long) = MintInfo(url = url, name = url, isActive = true, balance = balance)

    private companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"
        const val C = "https://c.example"
    }
}
