package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MintDiscoveryManagerTest {
    @Test
    fun parsesKind38172MintEvent() {
        val message = """
            ["EVENT","sub-1",{
              "kind":38172,
              "pubkey":"abcdef",
              "tags":[["u","https://mint.example.com/"]],
              "content":"{\"name\":\"Example Mint\",\"description\":\"Demo mint\",\"icon_url\":\"https://mint.example.com/icon.png\"}"
            }]
        """.trimIndent()

        val mint = NostrMintEventParser.parseRelayMessage(message)

        assertEquals("https://mint.example.com", mint?.url)
        assertEquals("Example Mint", mint?.name)
        assertEquals("Demo mint", mint?.description)
        assertEquals("https://mint.example.com/icon.png", mint?.iconUrl)
    }

    @Test
    fun rejectsNonMintEventKinds() {
        val message = """
            ["EVENT","sub-1",{
              "kind":1,
              "tags":[["u","https://mint.example.com"]],
              "content":"{}"
            }]
        """.trimIndent()

        assertNull(NostrMintEventParser.parseRelayMessage(message))
    }

    @Test
    fun rejectsMintEventsWithoutHttpUrl() {
        val message = """
            ["EVENT","sub-1",{
              "kind":38172,
              "tags":[["u","not-a-url"]],
              "content":"{}"
            }]
        """.trimIndent()

        assertNull(NostrMintEventParser.parseRelayMessage(message))
    }
}
