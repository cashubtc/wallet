package com.cashu.me.Core

import com.cashu.me.Core.CDK.FederationInfo
import com.cashu.me.Core.CDK.federationFor
import com.cashu.me.Core.NfcReceive.NfcSettlementRoute
import com.cashu.me.Core.NfcReceive.validateNfcReceiveTerms
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.FederationMintMetadata
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.mintMatching
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FederationMintsTest {
    private val members = (1..5).map { "https://cdk-$it.federation.example" }
    private val federation = FederationInfo(
        federationId = "fed-1",
        localMintUrl = "https://cdk-1.federation.example/",
        memberMintUrls = members,
        memberCount = 5,
        signatureThreshold = 3,
        consensusThreshold = 3,
        units = listOf("sat"),
    )

    @Test
    fun entryIsKeyedByTheLocalUrlAndCarriesMemberMetadata() {
        val info = MintInfo(url = "https://cdk-1.federation.example", name = "Federated Mint", iconUrl = "https://icon")
        val entry = federationMintEntry(federation, info)

        assertEquals("https://cdk-1.federation.example", entry.url)
        assertEquals("Federated Mint", entry.name)
        assertEquals("https://icon", entry.iconUrl)
        assertTrue(entry.isFederation)
        assertEquals(
            FederationMintMetadata(
                federationId = "fed-1",
                memberMintUrls = members,
                memberCount = 5,
                signatureThreshold = 3,
            ),
            entry.federation,
        )
    }

    @Test
    fun entryWithoutMintInfoFallsBackToTheHostName() {
        val entry = federationMintEntry(federation, info = null)

        assertEquals("cdk-1.federation.example", entry.name)
        assertEquals(listOf("sat"), entry.units)
    }

    @Test
    fun summaryLabelMatchesTheSharedCopy() {
        assertEquals("Federation · 3 of 5", federationMintEntry(federation, null).federation?.summaryLabel)
    }

    @Test
    fun anyMemberUrlMatchesTheFederationEntry() {
        val entry = federationMintEntry(federation, null)

        assertTrue(entry.matchesMintUrl("https://cdk-1.federation.example"))
        assertTrue(entry.matchesMintUrl("https://CDK-3.federation.example/"))
        assertTrue(entry.matchesMintUrl(" https://cdk-5.federation.example "))
        assertFalse(entry.matchesMintUrl("https://cdk-6.federation.example"))
        assertFalse(entry.matchesMintUrl("http://cdk-2.federation.example"))
        assertFalse(entry.matchesMintUrl(null))
    }

    @Test
    fun plainMintMatchesOnlyItsOwnUrl() {
        val mint = MintInfo(url = "https://mint.example")

        assertTrue(mint.matchesMintUrl("https://MINT.example/"))
        assertFalse(mint.matchesMintUrl("https://other.example"))
    }

    @Test
    fun mintMatchingPrefersAnExactEntryOverAFederationAlias() {
        val entry = federationMintEntry(federation, null)
        val plain = MintInfo(url = "https://cdk-2.federation.example", name = "Plain")
        val mints = listOf(entry, plain)

        assertEquals(plain, mints.mintMatching("https://cdk-2.federation.example"))
        assertEquals(entry, mints.mintMatching("https://cdk-4.federation.example/"))
        assertNull(mints.mintMatching("https://unknown.example"))
    }

    @Test
    fun anotherMemberOfATrackedFederationIsFoundAsTheExistingEntry() {
        val entry = federationMintEntry(federation, null)
        val viaMember = federation.copy(localMintUrl = "https://cdk-2.federation.example")
        val sameIdOtherUrls = federation.copy(localMintUrl = "https://renamed.example", memberMintUrls = listOf("https://renamed.example"))
        val unrelated = federation.copy(federationId = "fed-2", localMintUrl = "https://x.example", memberMintUrls = listOf("https://x.example"))

        assertEquals(entry, listOf(entry).entryForFederation(viaMember))
        assertEquals(entry, listOf(entry).entryForFederation(sameIdOtherUrls))
        assertNull(listOf(entry).entryForFederation(unrelated))
    }

    @Test
    fun trackingAFederationFoldsMemberPlaceholdersIntoOneEntry() {
        val other = MintInfo(url = "https://mint.example", name = "Other")
        val placeholder = MintInfo(url = "https://cdk-2.federation.example", name = "Unknown Mint", units = listOf("usd"), balance = 7)
        val entry = federationMintEntry(federation, null)

        val (updated, replaced) = listOf(other, placeholder).withFederationEntry(federation, entry)

        assertEquals(listOf(placeholder), replaced)
        assertEquals(listOf("https://mint.example", "https://cdk-1.federation.example"), updated.map { it.url })
        val merged = updated.last()
        assertEquals(listOf("usd", "sat"), merged.units)
        assertEquals(7, merged.balance)
        assertNotNull(merged.federation)
    }

    @Test
    fun federationMetadataPersistsAndOlderRecordsStillDecode() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val entry = federationMintEntry(federation, null)
        val encoded = json.encodeToString(ListSerializer(MintInfo.serializer()), listOf(entry))
        val decoded = json.decodeFromString(ListSerializer(MintInfo.serializer()), encoded).single()

        assertEquals(entry.federation, decoded.federation)
        // Live-only flag; a saved entry is a federation through its metadata.
        assertFalse(decoded.advertisesFederation)
        assertFalse("advertisesFederation" in encoded)

        val legacy = json.decodeFromString(MintInfo.serializer(), """{"url":"https://mint.example","name":"Old"}""")
        assertNull(legacy.federation)
        assertFalse(legacy.isFederation)
    }

    @Test
    fun registeredFederationIsFoundByAnyMemberOrLocalUrl() {
        val registered = listOf(federation.copy(memberMintUrls = members.drop(1)))

        assertEquals("fed-1", registered.federationFor("https://cdk-1.federation.example")?.federationId)
        assertEquals("fed-1", registered.federationFor("https://cdk-5.federation.example/")?.federationId)
        assertNull(registered.federationFor("https://elsewhere.example"))
        assertNull(registered.federationFor(null))
    }

    @Test
    fun paymentRequestNamingAMemberAcceptsTheFederationEntry() {
        val entry = federationMintEntry(federation, null).copy(balance = 100)
        val plain = MintInfo(url = "https://mint.example", balance = 500)
        val request = CashuPaymentRequestSummary(
            encoded = "creqa-test",
            amount = null,
            unit = "sat",
            description = null,
            mints = listOf("https://cdk-4.federation.example"),
        )

        assertEquals(listOf(entry), compatibleMintsForCashuPaymentRequest(request, listOf(plain, entry)))
        assertEquals(
            entry,
            selectMintForCashuPaymentRequest(request, listOf(plain, entry), null, null, amountSats = 50),
        )
    }

    @Test
    fun fundingSourceExcludesTheTargetFederationByMemberUrl() {
        val entry = federationMintEntry(federation, null).copy(balance = 1_000)
        val plain = MintInfo(url = "https://mint.example", balance = 500)

        assertEquals(
            plain,
            selectCashuRequestFundingSource(listOf(entry, plain), "https://cdk-3.federation.example", 100),
        )
    }

    @Test
    fun nfcTokenFromAnotherMemberOfTheSettlementFederationIsDirect() {
        val entry = federationMintEntry(federation, null)
        val request = CashuRequest(id = "request", encoded = "creqA", amount = 21, unit = "sat", mints = emptyList())

        assertEquals(
            NfcSettlementRoute.Direct,
            validateNfcReceiveTerms(
                request = request,
                sourceMint = "https://cdk-2.federation.example",
                tokenUnit = "sat",
                grossAmount = 21,
                settlementMint = entry.url,
                trackedMints = listOf(entry),
            ),
        )
    }

    @Test
    fun nfcRequestListingOneMemberAcceptsTokensFromAnotherMember() {
        val entry = federationMintEntry(federation, null)
        val request = CashuRequest(
            id = "request",
            encoded = "creqA",
            amount = 21,
            unit = "sat",
            mints = listOf("https://cdk-1.federation.example"),
        )

        assertEquals(
            NfcSettlementRoute.Direct,
            validateNfcReceiveTerms(
                request = request,
                sourceMint = "https://cdk-4.federation.example",
                tokenUnit = "sat",
                grossAmount = 21,
                settlementMint = entry.url,
                trackedMints = listOf(entry),
            ),
        )
    }

    @Test
    fun federationInviteCodesAreAcceptedAsAddMintInput() {
        val invite = "cashufed1qqqsyqcyq5rqwzqfpg9scrgwpugpzysn"

        assertTrue(isFederationInviteCode(invite))
        assertTrue(isFederationInviteCode(" \"$invite\" "))
        assertFalse(isFederationInviteCode("cashufed"))
        assertFalse(isFederationInviteCode("cashuBtoken"))
        assertFalse(isFederationInviteCode("cashufed1 abc"))
        assertEquals(invite, normalizeUserMintInput("  $invite "))
        assertEquals("https://mint.example", normalizeUserMintInput("mint.example/"))
        assertNull(normalizeUserMintInput("not a url"))
        assertEquals(
            listOf(invite, "https://mint.example"),
            mintInputCandidates("$invite, mint.example\n$invite"),
        )
    }
}
