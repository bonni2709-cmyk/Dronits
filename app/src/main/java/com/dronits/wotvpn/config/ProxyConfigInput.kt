package com.dronits.wotvpn.config

internal enum class ConfigInputError {
    Empty,
    TooLong,
    MultipleLinesOrControls,
    UnsupportedScheme,
    MissingPayload,
}

/** Checks a single share link, not the protocol settings or server capabilities. */
internal object ProxyConfigInput {
    const val MAX_LENGTH = 32_768
    private val supportedSchemes = setOf("vless", "vmess", "trojan", "ss")

    fun normalize(input: String): String = input.trim()

    fun validate(input: String): ConfigInputError? {
        if (input.length > MAX_LENGTH) return ConfigInputError.TooLong
        val link = normalize(input)
        if (link.isEmpty()) return ConfigInputError.Empty
        if (link.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            return ConfigInputError.MultipleLinesOrControls
        }
        val delimiter = link.indexOf("://")
        if (delimiter < 0 || link.substring(0, delimiter).lowercase() !in supportedSchemes) {
            return ConfigInputError.UnsupportedScheme
        }
        if (link.substring(delimiter + 3).isBlank()) return ConfigInputError.MissingPayload
        return null
    }
}
