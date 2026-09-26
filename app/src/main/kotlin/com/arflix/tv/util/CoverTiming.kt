package com.arflix.tv.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.arflix.tv.ui.screens.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * TEST BRANCH ONLY - never part of a pull request.
 *
 * Logcat lines for the "cover" before/after measurement (tag `CoverTiming`). Every line starts
 * with `t=<ms since process start>`. Log.w on purpose: the staging/release R8 rules strip
 * Log.v/d/i (proguard-rules.pro), so an info line would vanish from the measurement build.
 */
object CoverTiming {
    private const val TAG = "CoverTiming"
    private const val MAX_CARD_LINES = 40
    private const val MAX_HERO_LINES = 30

    private val onceEvents = Collections.synchronizedSet(mutableSetOf<String>())
    private val cardImages = AtomicInteger()
    private val tmdbBytes = AtomicLong()
    private val tmdbImages = AtomicInteger()

    private fun sinceStartMs(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        } else {
            -1L
        }

    private fun log(message: String) {
        Log.w(TAG, "t=${sinceStartMs()} $message")
    }

    fun once(event: String) {
        if (onceEvents.add(event)) log(event)
    }

    /** Every response from image.tmdb.org that really went over the network. */
    fun tmdbInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val startedAt = SystemClock.elapsedRealtime()
        val response = chain.proceed(request)
        if (request.url.host == "image.tmdb.org") {
            val size = request.url.pathSegments.getOrNull(2) ?: "?"
            val bytes = response.body?.contentLength() ?: -1L
            val total = tmdbBytes.addAndGet(bytes.coerceAtLeast(0L))
            val n = tmdbImages.incrementAndGet()
            log(
                "img $size $bytes n=$n total=$total code=${response.code} " +
                    "ttfb=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
        response
    }

    /**
     * Counts card images that were really drawn: a request with a target (AsyncImage, not a
     * preload) whose memory key ends in the Home card size, as MediaCard and PosterCard build it.
     */
    fun cardListener(context: Context): EventListener {
        val density = context.resources.displayMetrics.density
        fun cardSuffix(widthDp: Int, aspect: Float): String {
            val widthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
            val heightPx = (widthPx / aspect).toInt().coerceAtLeast(1)
            return "|${widthPx}x$heightPx"
        }
        // TV rows use 210 / 105 dp, phone rows 200 / 120 dp (HomeScreen itemWidth, rowMobileItemWidth).
        val landscape = listOf(cardSuffix(210, 16f / 9f), cardSuffix(200, 16f / 9f))
        val poster = listOf(cardSuffix(105, 2f / 3f), cardSuffix(120, 2f / 3f))
        return object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                if (request.target == null) return
                val key = request.memoryCacheKey?.key ?: return
                val kind = when {
                    landscape.any { key.endsWith(it) } -> "landscape"
                    poster.any { key.endsWith(it) } -> "poster"
                    else -> return
                }
                val n = cardImages.incrementAndGet()
                if (n <= MAX_CARD_LINES) {
                    val size = key.substringAfter("/t/p/", "").substringBefore('/').ifEmpty { "other" }
                    log("card-img n=$n src=${result.dataSource} $kind $size")
                }
            }
        }
    }

    /** First real row, and how long the focused item's logo takes to arrive. */
    fun watchHome(scope: CoroutineScope, state: StateFlow<HomeUiState>) {
        scope.launch {
            var heroKey: String? = null
            var heroSince = 0L
            var heroLogged = true
            var heroLines = 0
            state.collect { s ->
                if (s.categories.any { row ->
                        row.id != "continue_watching" && row.items.any { !it.isPlaceholder }
                    }
                ) {
                    once("rows-first")
                }
                val key = s.heroItem?.let { "${it.mediaType}_${it.id}" }
                if (key != heroKey) {
                    heroKey = key
                    heroSince = SystemClock.elapsedRealtime()
                    heroLogged = false
                }
                if (!heroLogged && key != null && s.heroLogoUrl != null && heroLines < MAX_HERO_LINES) {
                    heroLogged = true
                    heroLines++
                    log("hero-logo ${SystemClock.elapsedRealtime() - heroSince}ms")
                }
            }
        }
    }
}
