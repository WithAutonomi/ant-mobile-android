package com.autonomi.examples.antdemo.deeplink

import java.net.URLDecoder

/// `autonomi://` URI parsing — a Kotlin port of ant-webex's `parseAntUri` /
/// `buildFilename` / `sanitizeFilename` (src/content/scanner.ts,
/// src/background/index.ts), so the app resolves the download filename with the
/// exact same fallbacks the browser extension uses.
///
/// Shape: `autonomi://<64-hex-address>` optionally followed by query params,
/// introduced by `?` **or** `&` (ant-webex accepts either). Recognized params:
///   - `name`     — author-suggested base filename
///   - `filetype` — extension appended to `name` (deduped)
///   - `filename` — explicit full filename (our addition; takes precedence)
object AntUri {
    private const val SCHEME = "autonomi://"
    private val ADDRESS_RE = Regex("^[0-9a-fA-F]{64}$")

    data class Parsed(val address: String, val name: String?)

    /// Parse an autonomi:// URI into its address + suggested name.
    fun parse(uri: String): Parsed {
        val body = if (uri.startsWith(SCHEME)) uri.substring(SCHEME.length) else uri
        val sep = body.indexOfFirst { it == '?' || it == '&' }
        if (sep == -1) return Parsed(body, null)
        val address = body.substring(0, sep)
        val params = parseQuery(body.substring(sep + 1))
        // Explicit `filename` wins; else combine name + filetype like ant-webex.
        val explicit = params["filename"]?.trim().takeUnless { it.isNullOrEmpty() }
        val name = explicit
            ?: buildFilename(params["name"]?.trim(), params["filetype"]?.trim().takeUnless { it.isNullOrEmpty() })
        return Parsed(address, name)
    }

    fun isValidAddress(addr: String): Boolean = ADDRESS_RE.matches(addr)

    /// ant-webex `buildFilename`: both → `name.filetype`; only name → name;
    /// no name → null. Leading dots stripped; extension not doubled.
    fun buildFilename(name: String?, filetype: String?): String? {
        if (name.isNullOrEmpty()) return null
        val ext = filetype?.replace(Regex("^\\.+"), "")?.trim()
        if (ext.isNullOrEmpty()) return name
        if (name.lowercase().endsWith(".${ext.lowercase()}")) return name
        return "$name.$ext"
    }

    /// ant-webex `sanitizeFilename`: basename, strip control + `<>:"|?*`,
    /// strip leading dots, trim, cap at 200. May return "".
    fun sanitizeFilename(name: String): String {
        val base = name.split('/', '\\').last()
        return base
            .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "")
            .replace(Regex("^\\.+"), "")
            .trim()
            .take(200)
    }

    /// Final download filename with ant-webex's address-derived fallback.
    fun resolveFilename(parsed: Parsed): String {
        val sanitized = parsed.name?.let { sanitizeFilename(it) }
        return if (!sanitized.isNullOrEmpty()) sanitized else "autonomi-${parsed.address.take(12)}"
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i == -1) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
        }.toMap()

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
