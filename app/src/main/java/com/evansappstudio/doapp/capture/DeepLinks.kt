package com.evansappstudio.doapp.capture

import java.net.URLDecoder

// Spec 12 pure deep-link parsing. No Android imports: this file is in CorePurityTest's list.

/** What a do:// link asks for. */
sealed interface DeepLink {
    /** Open Add. [text] is the prefill after [ShareText.clean], or null for an empty field. */
    data class Add(val text: String?) : DeepLink

    /** Open the reminder list (the "Today" shortcut until the Today tab exists). */
    data object Today : DeepLink
}

object DeepLinks {
    const val SCHEME = "do"
    const val HOST_ADD = "add"
    const val HOST_TODAY = "today"
    const val PARAM_TEXT = "text"

    const val ADD = "$SCHEME://$HOST_ADD"
    const val TODAY = "$SCHEME://$HOST_TODAY"

    private val SHAPE = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?$""")

    /**
     * `do://add?text=<urlencoded>` and `do://today`. Scheme and host ignore case;
     * a trailing slash is allowed; anything else, or any other path, is null.
     * `+` and `%20` are both spaces. A bad escape keeps the raw text.
     */
    fun parse(uri: String?): DeepLink? {
        val match = SHAPE.matchEntire(uri?.trim() ?: return null) ?: return null
        val (scheme, host, path, query) = match.destructured
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        if (path.isNotEmpty() && path != "/") return null
        return when (host.lowercase()) {
            HOST_ADD -> DeepLink.Add(ShareText.clean(param(query, PARAM_TEXT)))
            HOST_TODAY -> DeepLink.Today
            else -> null
        }
    }

    /** The link for [text], as an automation app would build it. */
    fun addLink(text: String): String = "$ADD?$PARAM_TEXT=" + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun param(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val cut = pair.indexOf('=')
            val key = decode(if (cut < 0) pair else pair.substring(0, cut))
            if (key != name) continue
            return if (cut < 0) "" else decode(pair.substring(cut + 1))
        }
        return null
    }

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw, "UTF-8")
    } catch (error: IllegalArgumentException) {
        raw
    }
}
