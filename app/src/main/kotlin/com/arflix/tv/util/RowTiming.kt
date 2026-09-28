package com.arflix.tv.util

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.arflix.tv.ui.screens.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TEST BRANCH ONLY - never part of a pull request.
 *
 * Logcat lines for the "addonreihen" before/after measurement (tag `RowTiming`). Every line
 * starts with `t=<ms since process start>`. Log.w on purpose: the staging/release R8 rules
 * strip Log.v/d/i (proguard-rules.pro), so an info line would vanish from the measurement build.
 */
object RowTiming {
    private const val TAG = "RowTiming"
    private const val MAX_REQUEST_LINES = 600

    private val onceEvents = Collections.synchronizedSet(mutableSetOf<String>())
    private val shownRows = Collections.synchronizedSet(mutableSetOf<String>())
    private val requestLines = AtomicInteger()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun sinceStartMs(): Long =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            } else {
                -1L
            }
        } catch (_: RuntimeException) {
            -1L
        }

    // Plain JVM unit tests have no android.util.Log / SystemClock ("Method ... not mocked"):
    // the measurement must never break a test that happens to load a row.
    private fun log(message: String) {
        try {
            Log.w(TAG, "t=${sinceStartMs()} $message")
        } catch (_: RuntimeException) {
        }
    }

    fun once(event: String) {
        if (onceEvents.add(event)) log(event)
    }

    /** Monotonic, JVM-safe start mark for [rowLoaded]. */
    fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** A first page of an addon / Trakt / MDBList / collection row came back from the repository. */
    fun rowLoaded(id: String, source: String, items: Int, startedAtMs: Long) {
        log("row $id src=$source items=$items took=${nowMs() - startedAtMs}ms")
    }

    private fun kindOf(host: String, segments: List<String>): String = when {
        host.contains("cinemeta") -> "cinemeta"
        host == "api.themoviedb.org" -> when {
            segments.getOrNull(1) == "find" -> "find"
            segments.lastOrNull() == "external_ids" -> "external_ids"
            (segments.getOrNull(1) == "movie" || segments.getOrNull(1) == "tv") &&
                segments.size == 3 && segments[2].toIntOrNull() != null -> "details"
            else -> "other"
        }
        else -> "other"
    }

    /**
     * Application interceptor, first in the chain: it sees the URL the app asked for, before
     * ApiProxyInterceptor may rewrite TMDB calls to a proxy host. `net=0` = served from the
     * HTTP disk cache.
     */
    fun requestInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val response = chain.proceed(request)
        if (host == "api.themoviedb.org" || host.contains("cinemeta")) {
            val kind = kindOf(host, request.url.pathSegments)
            val n = counts.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
            val total = counts.getOrPut("all") { AtomicInteger() }.incrementAndGet()
            if (requestLines.incrementAndGet() <= MAX_REQUEST_LINES) {
                val net = if (response.networkResponse != null) 1 else 0
                log("req $kind n=$n all=$total net=$net code=${response.code}")
            }
        }
        response
    }

    private fun summary(label: String) {
        val parts = listOf("all", "details", "external_ids", "find", "cinemeta", "other")
            .joinToString(" ") { "$it=${counts[it]?.get() ?: 0}" }
        log("req-sum $label $parts")
    }

    /** First real row, every row's first appearance on Home, and request sums at 10/20/30/60 s. */
    fun watchHome(scope: CoroutineScope, state: StateFlow<HomeUiState>) {
        scope.launch {
            for (mark in listOf(10_000L, 20_000L, 30_000L, 60_000L)) {
                val wait = mark - sinceStartMs()
                if (wait > 0) delay(wait)
                summary("${mark / 1000}s")
            }
        }
        scope.launch {
            state.collect { s ->
                s.categories.forEach { row ->
                    if (row.id == "continue_watching") return@forEach
                    val real = row.items.count { !it.isPlaceholder }
                    if (real > 0) {
                        once("rows-first")
                        if (shownRows.add(row.id)) log("shown ${row.id} items=$real")
                    }
                }
            }
        }
    }
}
