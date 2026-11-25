package com.oneclip.app.core

import java.net.URI

object Urls {

    private val httpPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Pulls the first http(s) link out of arbitrary clipboard text. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val raw = httpPattern.find(text)?.value ?: return null
        return raw.trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'', '>')
    }

    fun host(url: String): String = try {
        URI(url).host?.removePrefix("www.")?.lowercase() ?: ""
    } catch (t: Throwable) {
        ""
    }

    fun sourceLabel(url: String): String {
        val host = host(url)
        return when {
            host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
            host.endsWith("instagram.com") -> "Instagram"
            host == "fb.watch" || host.endsWith("facebook.com") || host.endsWith("fb.com") -> "Facebook"
            host.endsWith("twitch.tv") -> "Twitch"
            host == "x.com" || host.endsWith("twitter.com") -> "X"
            host.isBlank() -> "Link"
            else -> host
        }
    }
}
