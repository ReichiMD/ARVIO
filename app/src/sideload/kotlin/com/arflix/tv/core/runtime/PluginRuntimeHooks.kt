package com.arflix.tv.core.runtime

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.ignoreAllSSLErrors

import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

object PluginRuntimeHooks {
    @Volatile private var application: Application? = null

    fun onApplicationCreate(application: Application) {
        // Defer heavy Conscrypt + baseClient init until a cloudstream extension is
        // actually invoked (player launch / source/plugin screens). On cold start
        // for users who never open those screens, this saves ~50-200ms of native
        // crypto provider setup on the main thread.
        this.application = application
        AcraApplication.context = application
    }

    @Volatile
    private var isCloudstreamInitialized = false

    /**
     * Lazily initialize Conscrypt + the cloudstream baseClient. Safe to call
     * repeatedly; only the first call performs work. Must be invoked before any
     * cloudstream extension code runs (loadExtension / downloadExtension /
     * extension test runners / CloudflareKiller).
     */
    fun ensureCloudstreamInitialized() {
        if (isCloudstreamInitialized) return

        synchronized(this) {
            if (isCloudstreamInitialized) return
            val currentApp = application ?: return

            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (e: Exception) {
                Log.w("NuvioApplication", "Failed to install Conscrypt: ${e.message}")
            }

            try {
                app.baseClient = OkHttpClient.Builder()
                    // Session cookies, not NO_COOKIES. Scraper sites routinely hand out a
                    // session cookie on the first request (or on a redirect) and expect it
                    // back on the next one; with NO_COOKIES every request looks like a
                    // brand-new visitor, which is what bot protection is built to reject.
                    // ARVIO already reached this conclusion for playback (PlaybackCookieJar
                    // in PlayerScreen.kt) — the plugin client just never got the same
                    // treatment. In-memory only: nothing is persisted across app restarts.
                    .cookieJar(PluginCookieJar)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .ignoreAllSSLErrors()
                    .cache(Cache(
                        directory = File(currentApp.cacheDir, "http_cache"),
                        maxSize = 50L * 1024L * 1024L
                    ))
                    .build()
            } catch (e: Throwable) {
                Log.w("NuvioApplication", "Failed to initialize NiceHttp client (API ${Build.VERSION.SDK_INT}): ${e.message}")
            }

            isCloudstreamInitialized = true
        }
    }

    fun onActivityCreate(activity: Activity) {
        AcraApplication.setActivity(activity)
    }

    fun onActivityDestroy() {
        AcraApplication.setActivity(null)
    }
}

/**
 * In-memory cookie jar for the cloudstream plugin HTTP client, mirroring
 * PlaybackCookieJar in PlayerScreen.kt: per-host storage, expiry honoured, no
 * persistence. Shared by every plugin, which is intentional — cloudstream's own
 * client is a single shared instance too, and extractors frequently follow a link
 * from one host to another expecting the session to survive.
 */
private object PluginCookieJar : CookieJar {
    private val cookiesByHost = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        val host = url.host
        val current = cookiesByHost[host].orEmpty().toMutableList()

        cookies.forEach { cookie ->
            if (cookie.expiresAt <= now) return@forEach
            current.removeAll { existing ->
                existing.name == cookie.name &&
                    existing.domain == cookie.domain &&
                    existing.path == cookie.path
            }
            current.add(cookie)
        }

        if (current.isEmpty()) cookiesByHost.remove(host) else cookiesByHost[host] = current
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val stored = cookiesByHost[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = stored.filter { it.expiresAt > now }
        if (valid.size != stored.size) {
            if (valid.isEmpty()) cookiesByHost.remove(url.host) else cookiesByHost[url.host] = valid
        }
        return valid.filter { it.matches(url) }
    }
}
