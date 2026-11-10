package com.dronits.wotvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64

class XrayConfigCompilerTest {
    private val endpoint = SocksEndpoint(10808, "fixture-user", "fixture-password")
    private val id = "00000000-0000-4000-8000-000000000001"

    private fun config(link: String): JSONObject = JSONObject(XrayConfigCompiler.compile(link, endpoint))

    @Test
    fun vlessUsesOneAuthenticatedUdpSocksInboundAndNoDirectFallback() {
        val json = config("vless://$id@127.0.0.1:443?encryption=none")
        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        val settings = inbound.getJSONObject("settings")
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("password", settings.getString("auth"))
        assertTrue(settings.getBoolean("udp"))
        assertEquals(endpoint.password, settings.getJSONArray("accounts").getJSONObject(0).getString("pass"))
        assertEquals(1, json.getJSONArray("outbounds").length())
        assertEquals("vless", json.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertEquals("tcp,udp", json.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("network"))
        assertFalse(json.toString().contains("freedom"))
    }

    @Test
    fun parsesPercentEncodedTrojanPasswordsWithoutChangingPlus() {
        val outbound = config("trojan://a%3Ab%2Bc%40d@localhost:443?sni=localhost")
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("a:b+c@d", outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("tls", stream.getString("security"))
        assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
    }

    @Test
    fun parsesIpv6AndShadowsocksSip002AndLegacyForms() {
        val auth = b64("aes-128-gcm:password:with:colon")
        val legacy = b64("aes-128-gcm:password:with:colon@[::1]:8388")
        listOf("ss://$auth@[::1]:8388#Example", "ss://$legacy").forEach { link ->
            val server = config(link).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
            assertEquals("::1", server.getString("address"))
            assertEquals("password:with:colon", server.getString("password"))
        }
    }

    @Test
    fun preservesWsPathGrpcAndRealitySettings() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        val reality = config("vless://$id@example.invalid:443?type=tcp&security=reality&pbk=$key&sid=abcd&sni=example.invalid&flow=xtls-rprx-vision")
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals(key, reality.getJSONObject("realitySettings").getString("publicKey"))
        val ws = config("vless://$id@example.invalid:443?type=ws&security=tls&path=%2Fgame%3Fed%3D2048&host=example.invalid")
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("/game?ed=2048", ws.getJSONObject("wsSettings").getString("path"))
        val grpc = config("vless://$id@example.invalid:443?type=grpc&security=tls&serviceName=game&mode=multi")
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertTrue(grpc.getJSONObject("grpcSettings").getBoolean("multiMode"))
    }

    @Test
    fun rejectsInvalidCredentialsPortsPluginsDuplicateParametersAndUnsafeTls() {
        listOf(
            "vless://bad-id@example.invalid:443", "vless://$id@example.invalid:0",
            "vless://$id@example.invalid:65536", "vless://$id@example.invalid:443?type=quic",
            "vless://$id@example.invalid:443?security=tls&allowInsecure=1",
            "vless://$id@example.invalid:443?security=tls&security=none",
            "ss://${b64("aes-128-gcm:pass")}@example.invalid:8388?plugin=v2ray-plugin",
            "trojan://secret-password@example.invalid:443?unsupported=parameter",
        ).forEach { link ->
            val error = assertThrows(ProxyConfigurationException::class.java) { config(link) }
            assertFalse(error.message.orEmpty().contains("secret-password"))
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun exportsProductionCompilerFixturesForCloudNativeIntegrationTests() {
        val vmess = JSONObject().put("v", "2").put("add", "127.0.0.1").put("port", "443")
            .put("id", id).put("aid", "0").put("net", "tcp").put("tls", "").put("scy", "auto")
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        val profiles = mapOf(
            "vless" to "vless://$id@127.0.0.1:443?encryption=none",
            "vmess" to "vmess://${b64(vmess.toString())}",
            "trojan" to "trojan://fixture-secret@localhost:443?sni=localhost&security=tls",
            "shadowsocks" to "ss://${b64("aes-128-gcm:fixture-secret")}@127.0.0.1:8388",
            "vless-ws" to "vless://$id@localhost:443?type=ws&security=tls&path=%2Fgame",
            "vless-grpc" to "vless://$id@localhost:443?type=grpc&security=tls&serviceName=game",
            "vless-httpupgrade" to "vless://$id@localhost:443?type=httpupgrade&security=tls&path=%2Fgame",
            "vless-xhttp" to "vless://$id@localhost:443?type=xhttp&security=tls&mode=auto&path=%2Fgame",
            "vless-reality" to "vless://$id@localhost:443?type=tcp&security=reality&pbk=$key&sid=abcd&sni=localhost&flow=xtls-rprx-vision",
            "ss2022" to "ss://${b64("2022-blake3-aes-128-gcm:${b64("0123456789abcdef")}")}@127.0.0.1:8388",
        )
        val directory = File(requireNotNull(System.getProperty("wot.fixture.dir")))
        directory.mkdirs()
        profiles.forEach { (name, link) ->
            File(directory, "$name.json").writeText(config(link).toString(2))
        }
        assertEquals(10, profiles.size)
    }

    @Test
    fun tun2socksUsesStandardUdpRelayWithMatchingDualStackMtu() {
        val yaml = ProxyManager.tunnelConfiguration(endpoint)
        assertTrue(yaml.contains("udp: 'udp'"))
        assertTrue(yaml.contains("mtu: 1400"))
        assertTrue(yaml.contains("fd42:776f:7400::1"))
        assertTrue(yaml.contains("password: '${endpoint.password}'"))
        assertFalse(yaml.contains("udp: 'tcp'"))
    }

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}
