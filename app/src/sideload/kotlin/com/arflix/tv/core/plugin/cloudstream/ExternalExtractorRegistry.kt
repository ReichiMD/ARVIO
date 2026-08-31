package com.arflix.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtractorRegistry"

/**
 * Registry of loaded extractors from external extensions.
 * Bridges NuvioTV's extractor management with the CloudStream library's
 * global [extractorApis] list and [loadExtractor] function.
 */
@Singleton
class ExternalExtractorRegistry @Inject constructor() {

    private val missingExtractorDomains = mutableSetOf<String>()
    private val builtInExtractors = mutableSetOf<ExtractorApi>()
    private var installed = false

    // extractorApis is a plain, non-thread-safe MutableList owned by the CloudStream
    // library. ARVIO loads/unloads multiple plugins concurrently (see PluginManager's
    // MAX_CONCURRENT_SCRAPERS), so two plugins registering their extractors at the same
    // time raced on this shared list — one thread's .add() invalidated another thread's
    // in-flight .any{} iterator, crashing with ConcurrentModificationException (confirmed
    // via device repro, 31.08.2026). All our own reads/writes are serialized through this
    // lock; plain synchronized (not a coroutines Mutex) because these bodies are quick,
    // non-suspending, in-memory list operations.
    private val registryLock = Any()

    fun registerExtractor(extractor: ExtractorApi): Unit = synchronized(registryLock) {
        // Avoid duplicates by mainUrl
        if (extractorApis.any { it.mainUrl == extractor.mainUrl }) return
        extractorApis.add(extractor)
        Log.d(TAG, "Registered extractor: ${extractor.name} (${extractor.mainUrl})")
    }

    fun registerAll(extractorList: List<ExtractorApi>): Unit = synchronized(registryLock) {
        extractorList.forEach { registerExtractor(it) }
    }

    fun unregisterExtractors(extractors: List<ExtractorApi>): Unit = synchronized(registryLock) {
        val targets = extractors.filter { it !in builtInExtractors }
        if (targets.isNotEmpty()) {
            extractorApis.removeAll(targets.toSet())
            Log.d(TAG, "Unregistered ${targets.size} extension extractors")
        }
    }

    fun clear(): Unit = synchronized(registryLock) {
        missingExtractorDomains.clear()
        val toRemove = extractorApis.filter { it !in builtInExtractors }
        if (toRemove.isNotEmpty()) {
            extractorApis.removeAll(toRemove.toSet())
            Log.d(TAG, "Cleared ${toRemove.size} extension extractors from global registry")
        }
    }

    /**
     * Try to resolve a URL using the library's loadExtractor.
     * The library's loadExtractor iterates through the global extractorApis list
     * which includes both built-in library extractors and extension-provided ones.
     */
    suspend fun resolveExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val result = loadExtractor(url, referer, subtitleCallback, callback)
            if (!result) {
                val domain = try {
                    java.net.URI(url).host ?: url
                } catch (_: Exception) {
                    url
                }
                if (missingExtractorDomains.add(domain)) {
                    Log.w(TAG, "No extractor registered for domain: $domain (url: $url)")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor error for ${url.take(80)}: ${e.message}", e)
            false
        } catch (e: Error) {
            Log.e(TAG, "loadExtractor linkage error for ${url.take(80)}: ${e.message}", e)
            false
        }
    }

    /**
     * Install this registry. The library's loadExtractor function uses the global
     * extractorApis list directly, so no delegate setup is needed.
     * This method ensures the library's built-in extractors are available.
     */
    fun installGlobal(): Unit = synchronized(registryLock) {
        if (installed) return
        installed = true
        builtInExtractors.addAll(extractorApis)
        Log.d(TAG, "installGlobal: library extractorApis has ${extractorApis.size} built-in extractors")
    }

    fun getMissingExtractorDomains(): Set<String> = missingExtractorDomains.toSet()
}
