package com.arflix.tv.data.repository

import com.arflix.tv.data.model.PlaylistGroupKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URLDecoder
import java.net.URLEncoder

/** Pseudo playlist id for the Stalker/Ministra portal source. */
const val STALKER_PLAYLIST_ID = "stalker"

/** Maximum number of Stalker portals a user can configure. */
const val MAX_STALKER_PORTALS = 3

/**
 * Placeholder URL for a Stalker VOD source.
 *
 * A Stalker portal never hands out a playable URL, only a `cmd` token that has
 * to be exchanged for one via `create_link`. Doing that while building a source
 * list would fire one portal request per candidate, so a matched movie carries
 * this marker instead and `StreamRepository.resolveStreamInternal` exchanges it
 * exactly once, when the user actually starts playback.
 *
 * Shape: `stalker_vod://<portalId>/<urlencoded cmd>`. The portal id travels
 * inside the marker so a multi-portal setup always resolves against the portal
 * the entry came from.
 */
internal object StalkerVodLink {

    const val SCHEME = "stalker_vod://"

    fun isMarker(url: String): Boolean = url.trim().startsWith(SCHEME, ignoreCase = true)

    fun buildMarker(portalId: String, cmd: String): String? {
        val id = portalId.trim()
        val command = cmd.trim()
        if (id.isBlank() || command.isBlank()) return null
        return SCHEME + URLEncoder.encode(id, "UTF-8") + "/" + URLEncoder.encode(command, "UTF-8")
    }

    /** Returns `portalId to cmd`, or null when [url] is not a well-formed marker. */
    fun parseMarker(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        if (!isMarker(trimmed)) return null
        val body = trimmed.substring(SCHEME.length)
        // The cmd is url-encoded, so its own slashes cannot be confused with
        // the single separator between portal id and command.
        val separator = body.indexOf('/')
        if (separator <= 0 || separator == body.length - 1) return null
        val portalId = runCatching { URLDecoder.decode(body.substring(0, separator), "UTF-8") }
            .getOrNull()?.trim().orEmpty()
        val command = runCatching { URLDecoder.decode(body.substring(separator + 1), "UTF-8") }
            .getOrNull()?.trim().orEmpty()
        if (portalId.isBlank() || command.isBlank()) return null
        return portalId to command
    }
}

/**
 * Pure helpers for the Stalker multi-portal model. Kept dependency-free so they
 * can be unit-tested without an Android context.
 */
internal object StalkerPortalSupport {

    private val gson = Gson()

    /**
     * Channel ids use the `stalker:<portalId>:<origId>` shape. Returns the
     * portal id segment, or null when the id is not a Stalker channel or the
     * portal segment is missing.
     */
    fun portalIdFromChannelId(channelId: String): String? {
        if (!channelId.startsWith("stalker:")) return null
        val afterPrefix = channelId.removePrefix("stalker:")
        val portalId = afterPrefix.substringBefore(':', missingDelimiterValue = "")
        return portalId.takeIf { it.isNotBlank() }
    }

    /** Returns the owning source id for playlist/group and cache scoping. */
    fun playlistIdFromChannelId(channelId: String): String {
        return portalIdFromChannelId(channelId)
            ?: channelId.substringBefore(':').trim()
    }

    fun streamCacheKey(channelId: String, command: String): String {
        return "${playlistIdFromChannelId(channelId)}|${command.trim()}"
    }

    /** Upgrades the old `stalker:<channelId>` shape to Portal 1. */
    fun migrateLegacyChannelId(channelId: String): String {
        val normalized = channelId.trim()
        if (!normalized.startsWith("stalker:") || portalIdFromChannelId(normalized) != null) {
            return normalized
        }
        val originalId = normalized.removePrefix("stalker:")
        return if (originalId.isBlank()) normalized else "stalker:stalker1:$originalId"
    }

    /** Upgrades old single-portal group keys from `stalker|group` to Portal 1. */
    fun migrateLegacyPlaylistGroupKey(rawKey: String): String {
        val normalized = rawKey.trim()
        if ('|' !in normalized) return normalized
        val key = PlaylistGroupKey(normalized)
        return if (key.playlistId == STALKER_PLAYLIST_ID) {
            PlaylistGroupKey.build("stalker1", key.groupName)
        } else {
            normalized
        }
    }

    fun normalizePlaylistGroupKeys(
        keys: Collection<String>,
        validSourceIds: Set<String> = emptySet(),
    ): List<String> {
        return keys.asSequence()
            .map(::migrateLegacyPlaylistGroupKey)
            .filter { key ->
                key.isNotBlank() &&
                    (validSourceIds.isEmpty() || ('|' in key && PlaylistGroupKey(key).playlistId in validSourceIds))
            }
            .distinct()
            .toList()
    }

    fun nextAvailablePortalId(existingIds: Collection<String>, maxPortals: Int): String? {
        val used = existingIds.mapTo(HashSet()) { it.trim() }
        if (maxPortals <= 0 || used.size >= maxPortals) return null
        var suffix = used.asSequence()
            .mapNotNull { id -> id.removePrefix("stalker").toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        while ("stalker$suffix" in used) suffix += 1
        return "stalker$suffix"
    }

    fun normalizeStalkerPortalEntry(
        portal: StalkerPortalEntry,
        index: Int
    ): StalkerPortalEntry? {
        val portalUrl = runCatching { portal.portalUrl }.getOrNull().orEmpty().trim().trimEnd('/')
        val macAddress = runCatching { portal.macAddress }.getOrNull().orEmpty().trim().uppercase()
        if (portalUrl.isBlank() || macAddress.isBlank()) return null
        return StalkerPortalEntry(
            id = runCatching { portal.id }.getOrNull().orEmpty().trim().ifBlank { "stalker${index + 1}" },
            name = runCatching { portal.name }.getOrNull().orEmpty().trim().ifBlank { "Portal ${index + 1}" },
            portalUrl = portalUrl,
            macAddress = macAddress,
            enabled = runCatching { portal.enabled }.getOrDefault(true)
        )
    }

    fun decodeStalkerPortals(raw: String, maxPortals: Int): List<StalkerPortalEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = TypeToken.getParameterized(List::class.java, StalkerPortalEntry::class.java).type
            normalizeStalkerPortals(
                gson.fromJson<List<StalkerPortalEntry>>(raw, type).orEmpty(),
                maxPortals,
            )
        }.getOrDefault(emptyList())
    }

    fun normalizeStalkerPortals(
        portals: List<StalkerPortalEntry>,
        maxPortals: Int,
    ): List<StalkerPortalEntry> {
        if (maxPortals <= 0) return emptyList()
        val usedIds = LinkedHashSet<String>()
        return buildList {
            portals.forEachIndexed { index, rawPortal ->
                if (size >= maxPortals) return@forEachIndexed
                val portal = normalizeStalkerPortalEntry(rawPortal, index) ?: return@forEachIndexed
                val id = portal.id.takeIf { it !in usedIds }
                    ?: nextAvailablePortalId(usedIds, maxPortals)
                    ?: return@forEachIndexed
                usedIds += id
                add(portal.copy(id = id))
            }
        }
    }

    /**
     * Builds the migrated Portal 1 entry from legacy single-portal fields.
     */
    fun migratedPortalFromLegacy(portalUrl: String, macAddress: String): StalkerPortalEntry? {
        val url = portalUrl.trim().trimEnd('/')
        val mac = macAddress.trim().uppercase()
        if (url.isBlank() || mac.isBlank()) return null
        return StalkerPortalEntry(
            id = "stalker1",
            name = "Portal 1",
            portalUrl = url,
            macAddress = mac
        )
    }
}
