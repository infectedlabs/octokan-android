package app.octokan.sdk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class OctokanMessage(val id: String, val serverId: Long?, val fromVisitor: Boolean, val body: String, val pending: Boolean)

private data class Queued(val clientId: String, val body: String)

/** Drives the chat screen: starts the conversation, sends with an offline queue and polls for replies. */
class OctokanChatViewModel : ViewModel() {
    var messages by mutableStateOf(listOf<OctokanMessage>()); private set
    var title by mutableStateOf("Support"); private set
    var accent by mutableStateOf(Color(0xFF151518)); private set
    var welcome by mutableStateOf("Hey, something not working? Tell us."); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private var client: OctokanClient? = null
    private var polling: Job? = null
    private var queue = mutableListOf<Queued>()
    private var lastServerId = 0L

    fun start() {
        if (client != null) return
        val appId = Octokan.appId
        val baseUrl = Octokan.baseUrl
        if (appId == null || baseUrl == null) {
            error = "Call Octokan.configure() first."
            ready = true
            return
        }
        val c = OctokanClient(appId, baseUrl, Octokan.token(appId))
        client = c
        queue = loadQueue(appId)

        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { c.start(Octokan.user) }
                res.token?.let { c.token = it; Octokan.saveToken(appId, it) }
                apply(res.config)
                merge(res.messages)
                queue.filter { q -> messages.none { it.id == q.clientId } }
                    .forEach { messages = messages + OctokanMessage(it.clientId, null, true, it.body, true) }
                error = null
                ready = true
                flush()
                beginPolling()
            } catch (e: ServerRefused) {
                error = e.message
                ready = true
            } catch (e: IOException) {
                error = "No connection. Your message will send when you're back online."
                ready = true
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
        client = null
    }

    fun send(raw: String) {
        val text = raw.trim().take(2000)
        if (text.isEmpty() || client == null) return
        val clientId = UUID.randomUUID().toString()
        messages = messages + OctokanMessage(clientId, null, true, text, true)
        queue.add(Queued(clientId, text))
        saveQueue()
        viewModelScope.launch { flush() }
    }

    /** Sends queued messages in order. A network failure leaves them queued for the next poll. */
    private suspend fun flush() {
        val c = client ?: return
        while (queue.isNotEmpty()) {
            val next = queue.first()
            try {
                val sent = withContext(Dispatchers.IO) { c.send(next.body, next.clientId) }
                queue.removeAt(0)
                saveQueue()
                sent?.let { merge(listOf(it)) }
                error = null
            } catch (e: ServerRefused) {
                // the server refused it (too fast, too long...): drop it from the queue and tell the person
                queue.removeAt(0)
                saveQueue()
                messages = messages.filterNot { it.id == next.clientId }
                error = e.message
            } catch (e: IOException) {
                return // offline: try again later
            }
        }
    }

    private fun beginPolling() {
        polling?.cancel()
        polling = viewModelScope.launch {
            while (isActive) {
                delay(4_000)
                poll()
            }
        }
    }

    private suspend fun poll() {
        val c = client ?: return
        if (queue.isNotEmpty()) flush()
        try {
            merge(withContext(Dispatchers.IO) { c.messages(lastServerId) })
        } catch (e: Exception) {
            // keep polling; the next round will catch up
        }
    }

    private fun merge(incoming: List<WireMessage>) {
        var list = messages
        for (m in incoming) {
            lastServerId = maxOf(lastServerId, m.id)
            val item = OctokanMessage(m.clientId ?: "s${m.id}", m.id, m.sender == "visitor", m.body, false)
            val i = list.indexOfFirst { it.id == item.id || (it.serverId != null && it.serverId == m.id) }
            list = if (i >= 0) list.toMutableList().also { it[i] = item } else list + item
        }
        messages = list
    }

    private fun apply(config: WireConfig?) {
        config ?: return
        config.title?.let { title = it }
        config.welcome?.let { welcome = it }
        config.accent?.let { parseColor(it)?.let { c -> accent = c } }
    }

    private fun loadQueue(appId: String): MutableList<Queued> {
        val raw = Octokan.loadQueue(appId) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Queued(arr.getJSONObject(it).getString("clientId"), arr.getJSONObject(it).getString("body")) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveQueue() {
        val appId = Octokan.appId ?: return
        val arr = JSONArray()
        queue.forEach { arr.put(JSONObject().put("clientId", it.clientId).put("body", it.body)) }
        Octokan.saveQueue(appId, arr.toString())
    }

    override fun onCleared() {
        stop()
    }
}

internal fun parseColor(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6) return null
    val v = s.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}
