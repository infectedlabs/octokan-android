package app.octokan.sdk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.IOException

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
        prefs?.edit()?.remove(tokenKey(id))?.remove(queueKey(id))?.apply()
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

    internal fun tokenKey(appId: String) = "token.$appId"
    internal fun queueKey(appId: String) = "queue.$appId"
    internal fun token(appId: String): String? = prefs?.getString(tokenKey(appId), null)
    internal fun saveToken(appId: String, token: String) { prefs?.edit()?.putString(tokenKey(appId), token)?.apply() }
    internal fun loadQueue(appId: String): String? = prefs?.getString(queueKey(appId), null)
    internal fun saveQueue(appId: String, json: String) { prefs?.edit()?.putString(queueKey(appId), json)?.apply() }
}
