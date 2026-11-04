package app.octokan.sdk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OctokanUser(val id: String? = null, val email: String? = null, val name: String? = null)

/**
 * The one-stop entry point.
 *
 *     Octokan.configure(this, appId = "app_xxxxxxxxxxxx", baseUrl = "https://YOUR-BACKEND")
 *     Octokan.identify(id = user.id, email = user.email, name = user.name)   // optional
 *     Octokan.open(context)                                                  // or embed OctokanChat()
 */
object Octokan {
    internal var appId: String? = null
    internal var baseUrl: String? = null
    internal var user: OctokanUser? = null
    private var prefs: SharedPreferences? = null

    /** Call once at startup (Application.onCreate). Nothing is sent or stored until the person opens the chat. */
    @JvmStatic
    fun configure(context: Context, appId: String, baseUrl: String) {
        this.appId = appId
        this.baseUrl = baseUrl
        this.prefs = context.applicationContext.getSharedPreferences("octokan_sdk", Context.MODE_PRIVATE)
        this.user = null
    }

    /** Tell octokan who is signed in, so you see their name and email in the inbox. Optional. */
    @JvmStatic
    @JvmOverloads
    fun identify(id: String? = null, email: String? = null, name: String? = null) {
        user = OctokanUser(id, email, name)
    }

    /** Call on sign-out so the next person on this device starts a fresh conversation. */
    @JvmStatic
    fun reset() {
        user = null
        val id = appId ?: return
        prefs?.edit()?.remove(tokenKey(id))?.remove(queueKey(id))?.remove(readKey(id))?.apply()
        lastRead = 0L
        unreadCount.value = 0
    }

    /** Lets a person erase their own chat from octokan's servers and from this device. Call off the main thread. */
    @JvmStatic
    @Throws(IOException::class)
    fun deleteMyData() {
        val id = appId ?: error("Call Octokan.configure() first.")
        val url = baseUrl ?: error("Call Octokan.configure() first.")
        OctokanClient(id, url, token(id)).forget()
        reset()
    }

    /** Opens the chat as a full screen. */
    @JvmStatic
    fun open(context: Context) {
        check(appId != null && baseUrl != null) { "Call Octokan.configure() first." }
        context.startActivity(Intent(context, OctokanActivity::class.java).addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val unreadCount = MutableStateFlow(0)

    /**
     * How many replies from support the person hasn't read. Show it on your own chat icon, for example with
     * `val n by Octokan.unread.collectAsState()`, or drop in `OctokanUnreadBadge()`.
     */
    @JvmStatic
    val unread: StateFlow<Int> get() = unreadCount

    private var chatsOpen = 0
    private var badgeJob: Job? = null
    private var lastRead = 0L

    internal fun chatOpened() { chatsOpen++; unreadCount.value = 0 }
    internal fun chatClosed() { if (chatsOpen > 0) chatsOpen-- }

    internal fun markRead(serverId: Long) {
        val id = appId ?: return
        if (serverId > lastRead) {
            lastRead = serverId
            prefs?.edit()?.putLong(readKey(id), serverId)?.apply()
        }
        if (unreadCount.value != 0) unreadCount.value = 0
    }

    /**
     * Keeps [unread] fresh while the chat is closed, about every 20 seconds. Does nothing for someone who has never
     * written. Call once, for example from your main screen; call [stopWatchingUnread] when it goes away.
     */
    @JvmStatic
    fun watchUnread() {
        val id = appId ?: return
        val url = baseUrl ?: return
        if (badgeJob?.isActive == true || token(id) == null) return
        lastRead = prefs?.getLong(readKey(id), 0L) ?: 0L
        badgeJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (chatsOpen == 0) {
                    runCatching {
                        val r = OctokanClient(id, url, token(id)).messages(lastRead, peek = true)
                        unreadCount.value = r.messages.count { it.sender == "owner" && it.id > lastRead }
                    }
                }
                delay(20_000)
            }
        }
    }

    @JvmStatic
    fun stopWatchingUnread() {
        badgeJob?.cancel()
        badgeJob = null
    }

    internal fun readKey(appId: String) = "read.$appId"
    internal fun tokenKey(appId: String) = "token.$appId"
    internal fun queueKey(appId: String) = "queue.$appId"
    internal fun token(appId: String): String? = prefs?.getString(tokenKey(appId), null)
    internal fun saveToken(appId: String, token: String) { prefs?.edit()?.putString(tokenKey(appId), token)?.apply() }
    internal fun loadQueue(appId: String): String? = prefs?.getString(queueKey(appId), null)
    internal fun saveQueue(appId: String, json: String) { prefs?.edit()?.putString(queueKey(appId), json)?.apply() }
}
