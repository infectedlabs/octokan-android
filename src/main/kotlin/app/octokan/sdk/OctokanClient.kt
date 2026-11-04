package app.octokan.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

internal data class WireMessage(val id: Long, val sender: String, val body: String, val clientId: String?)

internal data class WireConfig(val title: String?, val accent: String?, val welcome: String?)

internal data class InitResult(val token: String?, val config: WireConfig?, val messages: List<WireMessage>)

/** The server refused the request (too fast, too long, app not found...). Retrying won't help. */
internal class ServerRefused(message: String) : Exception(message)

/** Talks to the octokan backend over plain HTTPS. The visitor token travels in the Authorization header, never in a URL. */
internal class OctokanClient(private val appId: String, baseUrl: String, var token: String?) {
    private val base = baseUrl.trimEnd('/')

    private fun call(path: String, method: String, query: String = "", body: JSONObject? = null): JSONObject {
        val url = URL("$base/api/v1/$path?appId=${URLEncoder.encode(appId, "UTF-8")}$query")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            // Native apps send no Origin; this header tells the backend which SDK is calling.
            conn.setRequestProperty("X-Octokan-Platform", "android")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                body.put("appId", appId)
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            // 5xx is the server having a bad moment, not a refusal: the caller keeps the message queued and retries
            if (code >= 500) throw IOException("Server error ($code)")
            if (code !in 200..299) throw ServerRefused(json.optString("error").ifBlank { "Something went wrong ($code)." })
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(o: JSONObject) = WireMessage(
        id = o.getLong("id"),
        sender = o.getString("sender"),
        body = o.getString("body"),
        clientId = o.optString("clientId").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun parseList(json: JSONObject): List<WireMessage> {
        val arr = json.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    @Throws(IOException::class, ServerRefused::class)
    fun start(user: OctokanUser?): InitResult {
        val body = JSONObject().put("locale", Locale.getDefault().toLanguageTag()).put("page", "app")
        if (user != null) {
            val u = JSONObject()
            user.id?.let { u.put("id", it) }
            user.email?.let { u.put("email", it) }
            user.name?.let { u.put("name", it) }
            body.put("user", u)
        }
        val json = call("init", "POST", body = body)
        val c = json.optJSONObject("config")
        return InitResult(
            token = json.optString("token").takeIf { it.isNotBlank() && it != "null" },
            config = c?.let { WireConfig(it.optString("title").ifBlank { null }, it.optString("accent").ifBlank { null }, it.optString("welcome").ifBlank { null }) },
            messages = parseList(json),
        )
    }

    @Throws(IOException::class, ServerRefused::class)
    fun messages(after: Long): List<WireMessage> = parseList(call("messages", "GET", query = "&after=$after"))

    @Throws(IOException::class, ServerRefused::class)
    fun send(text: String, clientId: String): WireMessage? =
        call("messages", "POST", body = JSONObject().put("body", text).put("clientId", clientId)).optJSONObject("message")?.let(::parse)

    @Throws(IOException::class, ServerRefused::class)
    fun forget() {
        call("forget", "POST", body = JSONObject())
    }
}
