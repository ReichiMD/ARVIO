package com.arflix.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * How long a downloaded Stalker channel list stays reusable inside the running
 * app. Long enough to cover the startup burst and normal navigation between
 * screens, short enough that the `play_token` carried in every channel URL of
 * that list stays young.
 */
internal const val STALKER_CHANNEL_LIST_FRESHNESS_MS = 5 * 60_000L

/**
 * Lets every entry point that needs the Stalker channel list share a single
 * download instead of starting its own.
 *
 * Three entry points ask for the list on a normal app start — the startup
 * prefetch, the live TV snapshot load and the guide backfill load. Each used to
 * open its own portal session and pull the full list; on a portal with ~21k
 * channels that is 29 MB per entry point.
 *
 * Two mechanisms, both memory only:
 *  - concurrent callers await the download that is already running;
 *  - a caller arriving within [freshnessWindowMs] of a usable download reuses
 *    its result instead of starting another one.
 *
 * A caller that wants genuinely fresh data passes `forceReload` — that skips
 * the remembered result but still joins a download that is already running.
 * A running download is never older than the remembered result (a new one only
 * starts when none is in flight), so joining it is what "fresh" means here.
 * Only [invalidate] discards a running download, and only the configuration it
 * was made for changing justifies that.
 *
 * The download runs in [scope] rather than in the calling coroutine, so a
 * caller that gives up (its own timeout, a screen the user left) does not
 * cancel the download the other callers are waiting for.
 *
 * Deliberately **not** persisted across app runs: the playable URLs in the
 * channel list carry a `play_token`, so a list restored from disk would hand
 * out expired tokens. A fresh app start always downloads once.
 *
 * @param isReusable decides whether a result may be remembered. A failed
 *   handshake yields an empty list, and remembering that would keep the portal
 *   dark for the whole window.
 */
internal class StalkerChannelListLoader<T : Any>(
    private val freshnessWindowMs: Long = STALKER_CHANNEL_LIST_FRESHNESS_MS,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val isReusable: (T) -> Boolean = { true }
) {

    private val lock = Any()

    private var cacheKey: String? = null
    private var inFlight: Deferred<T>? = null
    private var remembered: T? = null
    private var rememberedAtMs: Long = 0L

    /**
     * Counts the downloads that were started. A download only stores its result
     * while it is still the current one, so a download detached by
     * [invalidate] cannot overwrite the result of the download that replaced
     * it — whichever of the two finishes last.
     */
    private var generation: Long = 0L

    /**
     * Returns the channel list for [key], running [fetch] at most once per
     * freshness window. [key] identifies the configured portals — a different
     * portal set never reuses another one's list.
     *
     * @param forceReload ignore the remembered result. A download that is
     *   already running is still shared: forcing is about not serving an old
     *   list, not about opening a second portal session next to the first.
     */
    suspend fun load(key: String, forceReload: Boolean = false, fetch: suspend () -> T): T {
        val pending = synchronized(lock) {
            if (key != cacheKey) {
                forgetLocked()
                cacheKey = key
            }
            val previous = remembered
            if (previous != null) {
                if (!forceReload && nowMs() - rememberedAtMs < freshnessWindowMs) return previous
                remembered = null
                rememberedAtMs = 0L
            }
            inFlight?.takeIf { !it.isCompleted } ?: startLocked(fetch)
        }
        return pending.await()
    }

    /**
     * Drops the remembered list and detaches a running download, so the next
     * [load] starts over. Reserved for a changed configuration — the running
     * download was made for the old one. A plain refresh must use
     * `forceReload` instead: measured, two entry points reacting to one
     * configuration change invalidated each other's download and pulled the
     * full channel list twice within the same second.
     *
     * A running download is detached, not cancelled: callers may be waiting on
     * it, and cancelling would fail their load. Its result is discarded rather
     * than stored, even if it finishes after the download that replaced it.
     */
    fun invalidate() {
        synchronized(lock) { forgetLocked() }
    }

    private fun startLocked(fetch: suspend () -> T): Deferred<T> {
        val startedAs = ++generation
        val deferred = scope.async {
            val value = fetch()
            synchronized(lock) {
                if (generation == startedAs && isReusable(value)) {
                    remembered = value
                    rememberedAtMs = nowMs()
                }
            }
            value
        }
        inFlight = deferred
        return deferred
    }

    private fun forgetLocked() {
        generation++
        cacheKey = null
        inFlight = null
        remembered = null
        rememberedAtMs = 0L
    }
}
