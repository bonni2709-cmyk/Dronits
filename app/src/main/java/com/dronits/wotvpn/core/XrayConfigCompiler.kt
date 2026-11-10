package com.dronits.wotvpn.core

import com.dronits.wotvpn.config.ProxyConfigInput
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal class ProxyConfigurationException : IllegalArgumentException("Invalid or unsupported proxy configuration")

internal class SocksEndpoint(val port: Int, val username: String, val password: String)

/** Converts share links to a single proxy outbound. No user-supplied routing or direct fallback. */
internal object XrayConfigCompiler {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val queryKeys = setOf(
        "type", "security", "encryption", "flow", "seed", "sni", "serverName", "peer", "fp", "alpn",
        "pbk", "sid", "spx", "pqv", "path", "host", "serviceName", "mode", "authority", "headerType",
        "ed", "extra", "allowInsecure", "insecure", "scy", "aid",
    )
    private val ssMethods = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "chacha20-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )
    private class Profile(
        val protocol: String, val host: String, val port: Int,
        val settings: JSONObject, val parameters: Map<String, String>,
    )

    fun compile(input: String, socks: SocksEndpoint): String {
        try {
            checkConfig(ProxyConfigInput.validate(input) == null)
            checkConfig(socks.port in 1..65535 && socks.username.isNotEmpty() && socks.password.isNotEmpty())
            val link = ProxyConfigInput.normalize(input)
            val scheme = link.substringBefore("://").lowercase()
            val profile = when (scheme) {
                "ss" -> shadowsocks(link)
                "vmess" -> if ('@' in link.substringAfter("://")) uriProfile(link, scheme) else vmess(link)
                "vless", "trojan" -> uriProfile(link, scheme)
                else -> throw ProxyConfigurationException()
            }
            checkConfig(profile.parameters.keys.all { it in queryKeys })
            val outbound = obj(
                "tag" to "proxy", "protocol" to profile.protocol,
                "settings" to profile.settings, "streamSettings" to stream(profile),
                "mux" to obj("enabled" to false),
            )
            return obj(
                "log" to obj("access" to "none", "error" to "none", "loglevel" to "none"),
                "inbounds" to arr(obj(
                    "tag" to "game-socks", "listen" to "127.0.0.1", "port" to socks.port,
                    "protocol" to "socks",
                    "settings" to obj(
                        "auth" to "password", "accounts" to arr(obj("user" to socks.username, "pass" to socks.password)),
                        "udp" to true, "ip" to "127.0.0.1", "userLevel" to 0,
                    ),
                )),
                "outbounds" to arr(outbound),
                "routing" to obj("domainStrategy" to "AsIs", "rules" to arr(
                    obj("type" to "field", "network" to "tcp,udp", "outboundTag" to "proxy"),
                )),
                "policy" to obj("levels" to obj("0" to obj(
                    "handshake" to 10, "connIdle" to 900, "uplinkOnly" to 5, "downlinkOnly" to 5,
                ))),
            ).toString()
        } catch (exception: ProxyConfigurationException) {
            throw exception
        } catch (_: Exception) {
            // URI/JSON exceptions may contain passwords. Never propagate their message or cause.
            throw ProxyConfigurationException()
        }
    }

    private fun uriProfile(link: String, scheme: String): Profile {
        val uri = URI(link)
        checkConfig(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        val authority = uri.rawAuthority ?: throw ProxyConfigurationException()
        val separator = authority.lastIndexOf('@')
        checkConfig(separator > 0)
        val credential = decode(authority.substring(0, separator))
        val (host, port) = endpoint(authority.substring(separator + 1))
        val params = query(uri.rawQuery)
        val settings = when (scheme) {
            "vless" -> {
                checkConfig(uuid.matches(credential))
                val flow = params["flow"].orEmpty()
                checkConfig(flow in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443"))
                obj("vnext" to arr(obj("address" to host, "port" to port, "users" to arr(obj(
                    "id" to credential, "encryption" to (params["encryption"] ?: "none"), "flow" to flow,
                    "seed" to params["seed"].orEmpty(),
                )))))
            }
            "vmess" -> {
                checkConfig(uuid.matches(credential) && params["aid"].orEmpty() in setOf("", "0"))
                obj("vnext" to arr(obj("address" to host, "port" to port, "users" to arr(obj(
                    "id" to credential, "security" to (params["scy"] ?: "auto"),
                )))))
            }
            "trojan" -> {
                checkConfig(credential.isNotEmpty() && params["flow"].isNullOrEmpty())
                obj("servers" to arr(obj("address" to host, "port" to port, "password" to credential)))
            }
            else -> throw ProxyConfigurationException()
        }
        return Profile(scheme, host, port, settings, params)
    }

    private fun vmess(link: String): Profile {
        val content = link.substringAfter("://").substringBefore('#')
        val json = JSONObject(base64(content))
        checkConfig(json.optString("v", "2") in setOf("", "2"))
        val host = hostname(json.getString("add"))
        val port = port(json.get("port").toString())
        val id = json.getString("id")
        checkConfig(uuid.matches(id) && json.optString("aid", "0") in setOf("", "0"))
        val params = mutableMapOf(
            "type" to json.optString("net", "tcp").ifEmpty { "tcp" },
            "security" to json.optString("tls", "none").ifEmpty { "none" },
            "headerType" to json.optString("type", "none").ifEmpty { "none" },
            "path" to json.optString("path", "/"), "host" to json.optString("host"),
        )
        listOf("sni", "fp", "alpn", "pbk", "sid", "spx", "allowInsecure").forEach { key ->
            if (json.has(key)) params[key] = json.get(key).toString()
        }
        if (params["type"] == "grpc") {
            params["serviceName"] = json.optString("path")
            params["mode"] = if (json.optString("type") == "multi") "multi" else "gun"
            params["headerType"] = "none"
        }
        return Profile("vmess", host, port, obj("vnext" to arr(obj(
            "address" to host, "port" to port,
            "users" to arr(obj("id" to id, "security" to json.optString("scy", "auto").ifEmpty { "auto" })),
        ))), params)
    }

    private fun shadowsocks(link: String): Profile {
        val content = link.substringAfter("://").substringBefore('#')
        val raw = content.substringBefore('?')
        checkConfig(query(content.substringAfter('?', "")).isEmpty()) // External SIP003 plugins are not silently discarded.
        val decoded = if ('@' in raw) raw else base64(raw)
        val separator = decoded.lastIndexOf('@')
        checkConfig(separator > 0)
        val encodedAuth = decoded.substring(0, separator)
        val auth = decode(encodedAuth).let { if (':' in it) it else base64(it) }
        val colon = auth.indexOf(':')
        checkConfig(colon > 0 && colon < auth.lastIndex)
        val method = auth.substring(0, colon)
        checkConfig(method in ssMethods)
        val password = auth.substring(colon + 1)
        val (host, port) = endpoint(decoded.substring(separator + 1).removeSuffix("/"))
        return Profile("shadowsocks", host, port, obj("servers" to arr(obj(
            "address" to host, "port" to port, "method" to method, "password" to password,
        ))), emptyMap())
    }

    private fun stream(profile: Profile): JSONObject {
        val p = profile.parameters
        val transport = when (p["type"].orEmpty().ifEmpty { "tcp" }) {
            "tcp", "raw" -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            else -> throw ProxyConfigurationException()
        }
        val security = p["security"] ?: if (profile.protocol == "trojan") "tls" else "none"
        checkConfig(security in setOf("none", "tls", "reality", ""))
        checkConfig(p["allowInsecure"].orEmpty().lowercase() !in setOf("1", "true"))
        checkConfig(p["insecure"].orEmpty().lowercase() !in setOf("1", "true"))
        if (!p["flow"].isNullOrEmpty()) checkConfig(transport == "tcp" && security in setOf("tls", "reality"))
        val config = obj("network" to transport, "security" to security.ifEmpty { "none" })
        val sni = p["sni"] ?: p["serverName"] ?: p["peer"] ?: profile.host
        val fingerprint = p["fp"].orEmpty().ifEmpty { "chrome" }
        if (security == "tls") {
            val tls = obj("serverName" to sni, "fingerprint" to fingerprint, "allowInsecure" to false)
            p["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
            config.put("tlsSettings", tls)
        }
        if (security == "reality") {
            checkConfig(transport in setOf("tcp", "grpc", "xhttp"))
            val key = p["pbk"] ?: throw ProxyConfigurationException()
            checkConfig(Base64.getUrlDecoder().decode(padded(key)).size == 32)
            val sid = p["sid"].orEmpty()
            checkConfig(sid.length <= 16 && sid.length % 2 == 0 && sid.all { it in "0123456789abcdefABCDEF" })
            config.put("realitySettings", obj(
                "serverName" to sni, "fingerprint" to fingerprint, "publicKey" to key,
                "shortId" to sid, "spiderX" to (p["spx"] ?: "/"), "mldsa65Verify" to p["pqv"].orEmpty(),
            ))
        }
        val host = p["host"].orEmpty()
        var path = p["path"].orEmpty().ifEmpty { "/" }
        p["ed"]?.let {
            checkConfig(it.toInt() in 0..65535)
            path += (if ('?' in path) "&" else "?") + "ed=$it"
        }
        when (transport) {
            "tcp" -> {
                val header = p["headerType"].orEmpty().ifEmpty { "none" }
                checkConfig(header in setOf("none", "http"))
                if (header == "http") {
                    config.put("tcpSettings", obj("header" to obj("type" to "http", "request" to obj(
                        "path" to arr(path), "headers" to obj("Host" to JSONArray(host.split(',').filter { it.isNotBlank() })),
                    ))))
                }
            }
            "ws" -> config.put("wsSettings", obj("path" to path, "host" to host))
            "httpupgrade" -> config.put("httpupgradeSettings", obj("path" to path, "host" to host))
            "grpc" -> config.put("grpcSettings", obj(
                "serviceName" to p["serviceName"].orEmpty(), "authority" to (p["authority"] ?: host),
                "multiMode" to (p["mode"] == "multi"),
            ))
            "xhttp" -> {
                val mode = p["mode"].orEmpty().ifEmpty { "auto" }
                checkConfig(mode in setOf("auto", "packet-up", "stream-up", "stream-one"))
                val xhttp = obj("path" to path, "host" to host, "mode" to mode)
                p["extra"]?.let { xhttp.put("extra", JSONObject(it)) }
                config.put("xhttpSettings", xhttp)
            }
        }
        return config
    }

    private fun endpoint(authority: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            checkConfig(end > 1 && authority.getOrNull(end + 1) == ':')
            host = authority.substring(1, end)
            portText = authority.substring(end + 2)
        } else {
            checkConfig(authority.count { it == ':' } == 1)
            host = authority.substringBefore(':')
            portText = authority.substringAfter(':')
        }
        return hostname(host) to port(portText)
    }

    private fun hostname(value: String): String {
        checkConfig(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() })
        val host = value.removePrefix("[").removeSuffix("]")
        if (':' in host) {
            checkConfig('%' !in host && InetAddress.getByName(host) is Inet6Address)
            return host
        }
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).also { checkConfig(it.isNotEmpty() && it.length <= 253) }
    }

    private fun port(value: String): Int = value.toInt().also { checkConfig(it in 1..65535) }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        raw.split('&').forEach { part ->
            val key = decode(part.substringBefore('='))
            checkConfig(key.isNotEmpty() && key !in result)
            result[key] = decode(part.substringAfter('=', ""))
        }
        return result
    }

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun padded(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
    private fun base64(value: String): String {
        val bytes = try { Base64.getDecoder().decode(padded(value)) } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(padded(value))
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun checkConfig(condition: Boolean) { if (!condition) throw ProxyConfigurationException() }
    private fun obj(vararg values: Pair<String, Any>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }
    private fun arr(vararg values: Any): JSONArray = JSONArray().apply { values.forEach { put(it) } }
}
