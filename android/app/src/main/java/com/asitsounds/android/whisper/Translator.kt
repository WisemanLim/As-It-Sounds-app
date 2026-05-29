package com.asitsounds.android.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface Translator {
    val name: String
    val requiresNetwork: Boolean
    suspend fun translate(text: String, src: String, dst: String): String
}

class DisabledTranslator : Translator {
    override val name = "Disabled"
    override val requiresNetwork = false
    override suspend fun translate(text: String, src: String, dst: String) = ""
}

// ──────────────────────────────────────────────────────────────────────────
// MyMemory — free, no API key. https://mymemory.translated.net/doc/spec.php
// ──────────────────────────────────────────────────────────────────────────

class MyMemoryTranslator : Translator {
    override val name = "MyMemory"
    override val requiresNetwork = true

    override suspend fun translate(text: String, src: String, dst: String): String = withContext(Dispatchers.IO) {
        val q = text.trim()
        if (q.isEmpty()) return@withContext ""
        val url = URL(
            "https://api.mymemory.translated.net/get?" +
                "q=${URLEncoder.encode(q, "UTF-8")}&langpair=${src}%7C${dst}"
        )
        val body = httpGet(url)
        val translated = JSONObject(body)
            .optJSONObject("responseData")
            ?.optString("translatedText")
            .orEmpty()
        translated
    }
}

// ──────────────────────────────────────────────────────────────────────────
// LibreTranslate — self-host or libretranslate.com (optional API key)
// ──────────────────────────────────────────────────────────────────────────

class LibreTranslateTranslator(
    private val endpoint: String = "https://libretranslate.com/translate",
    private val apiKey: String? = null,
) : Translator {
    override val name = "LibreTranslate"
    override val requiresNetwork = true

    override suspend fun translate(text: String, src: String, dst: String): String = withContext(Dispatchers.IO) {
        val q = text.trim()
        if (q.isEmpty()) return@withContext ""
        val payload = JSONObject().apply {
            put("q", q)
            put("source", src)
            put("target", dst)
            put("format", "text")
            if (!apiKey.isNullOrBlank()) put("api_key", apiKey)
        }.toString()
        val body = httpPost(URL(endpoint), payload, "application/json")
        JSONObject(body).optString("translatedText").orEmpty()
    }
}

// ──────────────────────────────────────────────────────────────────────────
// DeepL — API key required. Free or Pro endpoints.
// ──────────────────────────────────────────────────────────────────────────

class DeepLTranslator(
    private val apiKey: String,
    private val isFreeTier: Boolean = true,
) : Translator {
    override val name = "DeepL"
    override val requiresNetwork = true

    override suspend fun translate(text: String, src: String, dst: String): String = withContext(Dispatchers.IO) {
        val q = text.trim()
        if (q.isEmpty()) return@withContext ""
        val host = if (isFreeTier) "api-free.deepl.com" else "api.deepl.com"
        val url = URL("https://$host/v2/translate")
        val form = listOf(
            "text" to q,
            "source_lang" to src.uppercase(),
            "target_lang" to dst.uppercase(),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val body = httpPost(url, form, "application/x-www-form-urlencoded",
            extraHeaders = mapOf("Authorization" to "DeepL-Auth-Key $apiKey"))
        JSONObject(body)
            .optJSONArray("translations")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
    }
}

// ──────────────────────────────────────────────────────────────────────────

enum class TranslatorChoice(val id: String, val label: String) {
    DISABLED("disabled", "Off"),
    MYMEMORY("mymemory", "MyMemory"),
    LIBRETRANSLATE("libretranslate", "LibreTranslate"),
    DEEPL("deepl", "DeepL");

    fun make(): Translator = when (this) {
        DISABLED -> DisabledTranslator()
        MYMEMORY -> MyMemoryTranslator()
        LIBRETRANSLATE -> LibreTranslateTranslator()
        DEEPL -> DeepLTranslator(apiKey = "")
    }
}

// ──────────────────────────────────────────────────────────────────────────
// HTTP helpers (no external deps — uses platform HttpURLConnection).
// ──────────────────────────────────────────────────────────────────────────

private fun httpGet(url: URL, timeoutMs: Int = 15_000): String {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = true
    }
    return try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun httpPost(
    url: URL,
    body: String,
    contentType: String,
    extraHeaders: Map<String, String> = emptyMap(),
    timeoutMs: Int = 15_000,
): String {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        doOutput = true
        setRequestProperty("Content-Type", contentType)
        extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    return try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            error("HTTP ${conn.responseCode}: $err")
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
