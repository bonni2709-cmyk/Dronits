package com.dronits.wotvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigInputTest {
    @Test
    fun acceptsASingleShareLinkForEachSupportedScheme() {
        listOf(
            "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls",
            "vmess://eyJ2IjoiMiIsImFkZCI6ImV4YW1wbGUuaW52YWxpZCJ9",
            "trojan://example-password@example.invalid:443#Example",
            "ss://YWVzLTEyOC1nY206ZXhhbXBsZQ@example.invalid:8388",
        ).forEach { link -> assertNull(ProxyConfigInput.validate(link)) }
    }

    @Test
    fun trimsOuterWhitespaceWithoutRewritingCredentialsOrParameters() {
        val link = "VLESS://example@example.invalid:443?path=%2Fa&security=tls#My%20server"

        assertEquals(link, ProxyConfigInput.normalize(" \n$link\t "))
        assertNull(ProxyConfigInput.validate(" \n$link\t "))
    }

    @Test
    fun rejectsEmptyInput() {
        listOf("", " ", "\n\t").forEach { input ->
            assertEquals(ConfigInputError.Empty, ProxyConfigInput.validate(input))
        }
    }

    @Test
    fun rejectsUnsupportedSchemesAndSubscriptionUrls() {
        listOf("https://example.invalid/subscription", "socks://example.invalid:1080", "{}", "vless:example")
            .forEach { input ->
                assertEquals(ConfigInputError.UnsupportedScheme, ProxyConfigInput.validate(input))
            }
    }

    @Test
    fun requiresContentAfterTheScheme() {
        listOf("vless://", "vmess:// ", "trojan://", "ss://").forEach { input ->
            assertEquals(ConfigInputError.MissingPayload, ProxyConfigInput.validate(input))
        }
    }

    @Test
    fun rejectsMultipleLinksAndEmbeddedControlCharacters() {
        listOf(
            "vless://first\ntrojan://second",
            "ss://first\r\nss://second",
            "vmess://first\tsecond",
            "vless://first\u0000second",
            "vless://first\u2028second",
            "vless://first\u2029second",
        ).forEach { input ->
            assertEquals(ConfigInputError.MultipleLinesOrControls, ProxyConfigInput.validate(input))
        }
    }

    @Test
    fun enforcesTheDocumentedLengthLimit() {
        val longest = "ss://" + "a".repeat(ProxyConfigInput.MAX_LENGTH - 5)

        assertNull(ProxyConfigInput.validate(longest))
        assertEquals(ConfigInputError.TooLong, ProxyConfigInput.validate(longest + "a"))
    }
}
