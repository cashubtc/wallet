package org.cashu.wallet.ui.history

import java.time.LocalDateTime
import java.time.ZoneId
import org.cashu.wallet.Core.HistoryFilter
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTimelineTest {
    @Test
    fun suppressesTransactionsClaimedByCashuRequests() {
        val request = CashuRequest(
            id = "request-1",
            encoded = "creq",
            createdAtEpochMillis = 3_000,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "event-1",
                    amount = 21,
                    receivedAtEpochMillis = 2_000,
                ),
            ),
        )

        val items = unifiedFiltered(
            transactions = listOf(
                transaction(id = "event-1", date = 2_000),
                transaction(id = "other", date = 1_000),
            ),
            requests = listOf(request),
            filter = HistoryFilter.All,
            query = "",
        )

        assertEquals(listOf("req:request-1", "tx:other"), items.map { it.key })
    }

    @Test
    fun suppressesTransactionsClaimedByQuoteId() {
        val request = CashuRequest(
            id = "request-1",
            encoded = "lno...",
            quoteId = "quote-1",
            quoteKind = "Bolt12",
            createdAtEpochMillis = 3_000,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "quote-1",
                    amount = 21,
                    receivedAtEpochMillis = 2_000,
                ),
            ),
        )

        val items = unifiedFiltered(
            transactions = listOf(
                transaction(id = "tx-row", quoteId = "quote-1", date = 2_000),
                transaction(id = "other", date = 1_000),
            ),
            requests = listOf(request),
            filter = HistoryFilter.All,
            query = "",
        )

        assertEquals(listOf("req:request-1", "tx:other"), items.map { it.key })
    }

    @Test
    fun searchesRequestReceivedAmount() {
        val request = CashuRequest(
            id = "request-1",
            encoded = "creq",
            amount = null,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "event-1",
                    amount = 42_000,
                    receivedAtEpochMillis = 2_000,
                ),
            ),
        )

        val items = unifiedFiltered(
            transactions = emptyList(),
            requests = listOf(request),
            filter = HistoryFilter.All,
            query = "42000",
        )

        assertEquals(listOf("req:request-1"), items.map { it.key })
    }

    @Test
    fun windowsLargeLedgersByVisibleCount() {
        val items = (1..35).map {
            HistoryItem.Tx(transaction(id = "tx-$it", date = it.toLong()))
        }

        val visible = visibleHistoryItems(items, visibleCount = HISTORY_PAGE_STEP)

        assertEquals(30, visible.size)
        assertEquals("tx:tx-1", visible.first().key)
        assertEquals("tx:tx-30", visible.last().key)
    }

    @Test
    fun groupsHistoryItemsIntoIosDateBuckets() {
        val zone = ZoneId.of("UTC")
        val now = epochMillis(2026, 5, 21, zone)
        val items = listOf(
            HistoryItem.Tx(transaction("today", epochMillis(2026, 5, 21, zone))),
            HistoryItem.Tx(transaction("yesterday", epochMillis(2026, 5, 20, zone))),
            HistoryItem.Tx(transaction("week", epochMillis(2026, 5, 18, zone))),
            HistoryItem.Tx(transaction("month", epochMillis(2026, 5, 2, zone))),
            HistoryItem.Tx(transaction("earlier", epochMillis(2026, 4, 30, zone))),
        )

        val groups = groupHistoryItems(items, nowEpochMillis = now, zoneId = zone)

        assertEquals(listOf("Today", "Yesterday", "This Week", "This Month", "Earlier"), groups.map { it.title })
        assertEquals(listOf("tx:today"), groups[0].items.map { it.key })
        assertEquals(listOf("tx:yesterday"), groups[1].items.map { it.key })
        assertEquals(listOf("tx:week"), groups[2].items.map { it.key })
        assertEquals(listOf("tx:month"), groups[3].items.map { it.key })
        assertEquals(listOf("tx:earlier"), groups[4].items.map { it.key })
    }

    @Test
    fun emptyStateCopyMatchesIos() {
        assertEquals(
            HistoryEmptyStateCopy("No Results", "No activity matches \"bolt12\"."),
            historyEmptyStateCopy(HistoryFilter.All, hasQuery = true, query = " bolt12 "),
        )
        assertEquals(
            HistoryEmptyStateCopy("Nothing Here", "No transactions match this filter."),
            historyEmptyStateCopy(HistoryFilter.Pending, hasQuery = false, query = ""),
        )
        assertEquals(
            HistoryEmptyStateCopy("No History Yet", "Your payments will appear here."),
            historyEmptyStateCopy(HistoryFilter.All, hasQuery = false, query = ""),
        )
    }

    private fun transaction(
        id: String,
        date: Long,
        quoteId: String? = null,
    ) = WalletTransaction(
        id = id,
        amount = 21,
        type = TransactionType.Incoming,
        kind = TransactionKind.Ecash,
        dateEpochMillis = date,
        status = TransactionStatus.Completed,
        quoteId = quoteId,
    )

    private fun epochMillis(year: Int, month: Int, day: Int, zone: ZoneId): Long =
        LocalDateTime.of(year, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()
}
