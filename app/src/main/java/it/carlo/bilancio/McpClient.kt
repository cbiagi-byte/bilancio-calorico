package it.carlo.bilancio

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for athletedata's hosted MCP server (Streamable HTTP).
 * Accepts either the full "Copy MCP URL" value (https://mcp.athletedata.health/mcp?apiKey=...)
 * or the bare key (sk_soma_...).
 */
class McpClient(credential: String) {

    private val endpoint: String
    private val apiKey: String?
    private var sessionId: String? = null
    private var nextId = 1
    private var initialized = false

    init {
        val c = credential.trim()
        if (c.startsWith("http")) {
            endpoint = c
            apiKey = Regex("[?&]apiKey=([^&]+)").find(c)?.groupValues?.get(1)
        } else {
            apiKey = c
            endpoint = BASE_URL + "?apiKey=" + URLEncoder.encode(c, "UTF-8")
        }
    }

    class McpException(message: String) : IOException(message)

    private fun post(body: JSONObject, expectResponse: Boolean): JSONObject? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code == 401 || code == 403) {
                throw McpException("Chiave athletedata non valida o abbonamento non attivo (HTTP $code)")
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw McpException("Errore athletedata HTTP $code ${err.take(200)}")
            }
            if (!expectResponse) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val contentType = conn.contentType ?: ""
            val wantedId = body.optInt("id", -1)
            return if (contentType.contains("event-stream") || text.trimStart().startsWith("event:") || text.trimStart().startsWith("data:")) {
                parseSse(text, wantedId)
            } else {
                JSONObject(text)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSse(text: String, wantedId: Int): JSONObject {
        var found: JSONObject? = null
        val data = StringBuilder()
        fun flush() {
            if (data.isEmpty()) return
            try {
                val obj = JSONObject(data.toString())
                if (obj.optInt("id", -2) == wantedId || (found == null && (obj.has("result") || obj.has("error")))) {
                    found = obj
                }
            } catch (_: Exception) {
            }
            data.setLength(0)
        }
        for (line in text.lines()) {
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                line.isBlank() -> flush()
            }
        }
        flush()
        return found ?: throw McpException("Risposta athletedata non leggibile")
    }

    private fun ensureInitialized() {
        if (initialized) return
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId++)
            .put("method", "initialize")
            .put(
                "params", JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "bilancio-calorico").put("version", "1.0"))
            )
        val res = post(req, true)
        if (res?.has("error") == true) {
            throw McpException(res.getJSONObject("error").optString("message", "Errore di inizializzazione"))
        }
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), false)
        initialized = true
    }

    /** Calls a tool and returns its text content parsed as JSON (object), or throws. */
    fun callTool(name: String, args: JSONObject): JSONObject {
        ensureInitialized()
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId++)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
        val res = post(req, true) ?: throw McpException("Nessuna risposta da $name")
        if (res.has("error")) {
            throw McpException(res.getJSONObject("error").optString("message", "Errore in $name"))
        }
        val result = res.getJSONObject("result")
        val content = result.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        val text = sb.toString().trim()
        if (result.optBoolean("isError", false)) throw McpException(text.take(300))
        result.optJSONObject("structuredContent")?.let { if (text.isEmpty()) return it }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            // Some tools answer with plain text (e.g. "no data in this window").
            JSONObject().put("_text", text)
        }
    }

    companion object {
        const val BASE_URL = "https://mcp.athletedata.health/mcp"
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
