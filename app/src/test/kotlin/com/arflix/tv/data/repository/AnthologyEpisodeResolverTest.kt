package com.arflix.tv.data.repository

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AnthologyEpisodeResolverTest {
    // Cinemeta's tt13207736 "Monster", episodes 1 and 2 of each season (real data, October 2026).
    private val monsterVideos = "[{\"name\": \"Episode One\", \"season\": 1, \"episode\": 1, \"released\": \"2022-09-21T11:00:00.000Z\", \"tvdb_id\": 7974291, \"id\": \"tt13207736:1:1\"}, {\"name\": \"Please Don't Go\", \"season\": 1, \"episode\": 2, \"released\": \"2022-09-21T11:00:00.000Z\", \"tvdb_id\": 9333705, \"id\": \"tt13207736:1:2\"}, {\"name\": \"Blame It on the Rain\", \"season\": 2, \"episode\": 1, \"released\": \"2024-09-19T11:00:00.000Z\", \"tvdb_id\": 10649169, \"id\": \"tt13207736:2:1\"}, {\"name\": \"Spree\", \"season\": 2, \"episode\": 2, \"released\": \"2024-09-19T11:00:00.000Z\", \"tvdb_id\": 10649170, \"id\": \"tt13207736:2:2\"}, {\"name\": \"Mother!\", \"season\": 3, \"episode\": 1, \"released\": \"2025-10-03T11:00:00.000Z\", \"tvdb_id\": 11306715, \"id\": \"tt13207736:3:1\"}, {\"name\": \"Sick as Your Secrets\", \"season\": 3, \"episode\": 2, \"released\": \"2025-10-03T11:00:00.000Z\", \"tvdb_id\": 11309535, \"id\": \"tt13207736:3:2\"}, {\"name\": \"Bloodbath\", \"season\": 4, \"episode\": 1, \"released\": \"2026-09-17T11:00:00.000Z\", \"tvdb_id\": 11934436, \"id\": \"tt13207736:4:1\"}, {\"name\": \"Strong Kitty\", \"season\": 4, \"episode\": 2, \"released\": \"2026-09-17T11:00:00.000Z\", \"tvdb_id\": 11963721, \"id\": \"tt13207736:4:2\"}]"

    private val monsterEpisodes get() = parseCinemetaEpisodes(JSONArray(monsterVideos))

    @Test
    fun `tvdb episode id finds the season inside the IMDb series`() {
        // TMDB 299939 "Monster: The Lizzie Borden Story" S1E1 = IMDb tt13207736 S4E1.
        val match = matchCinemetaEpisode(monsterEpisodes, EpisodeFingerprint("Bloodbath", "2026-09-17", 11934436))
        assertEquals(4 to 1, match?.let { it.season to it.episode })
    }

    @Test
    fun `title on the same day matches when TMDB has no tvdb id`() {
        val match = matchCinemetaEpisode(monsterEpisodes, EpisodeFingerprint("Blame it on the Rain!", "2024-09-20", null))
        assertEquals(2 to 1, match?.let { it.season to it.episode })
    }

    @Test
    fun `same title on another day or unknown episode is no match`() {
        assertNull(matchCinemetaEpisode(monsterEpisodes, EpisodeFingerprint("Blame It on the Rain", "2021-01-01", null)))
        assertNull(matchCinemetaEpisode(monsterEpisodes, EpisodeFingerprint("Pilot", "2024-09-19", 1)))
        // A season released at once shares one date: the date alone must never pick an episode.
        assertNull(matchCinemetaEpisode(monsterEpisodes, EpisodeFingerprint(null, "2024-09-19", null)))
    }

    @Test
    fun `search names drop the subtitle as a fallback`() {
        assertEquals(
            listOf("Monster: The Ed Gein Story", "Monster: Die Geschichte von Ed Gein", "Monster"),
            anthologySearchQueries(listOf("Monster: The Ed Gein Story", "Monster: The Ed Gein Story", "Monster: Die Geschichte von Ed Gein"))
        )
        assertEquals(
            listOf("DAHMER - Monster: The Jeffrey Dahmer Story", "DAHMER - Monster", "DAHMER"),
            anthologySearchQueries(listOf("DAHMER - Monster: The Jeffrey Dahmer Story", null))
        )
    }

    @Test
    fun `resolver maps a TMDB anthology episode to the IMDb series episode`() = runBlocking {
        val requested = mutableListOf<String>()
        val resolver = AnthologyEpisodeResolver(
            fetchJson = { url ->
                requested += url
                when {
                    url.contains("/tv/299939/season/1/episode/1") ->
                        """{"name":"Bloodbath","air_date":"2026-09-17","external_ids":{"imdb_id":"tt37538664","tvdb_id":11934436}}"""
                    url.contains("/tv/299939?") ->
                        """{"name":"Monster: The Lizzie Borden Story","original_name":"Monster: The Lizzie Borden Story"}"""
                    url.contains("/catalog/series/top/search=") ->
                        """{"metas":[{"id":"tt4145760","name":"The Lizzie Borden Chronicles"},{"id":"tt13207736","name":"Monster"}]}"""
                    url.endsWith("/meta/series/tt13207736.json") -> """{"meta":{"videos":$monsterVideos}}"""
                    url.endsWith("/meta/series/tt4145760.json") -> """{"meta":{"videos":[]}}"""
                    else -> null
                }
            },
            tmdbApiKey = { "key" }
        )

        val target = resolver.resolve(299939, 1, 1, "Monster: Die Geschichte von Lizzie Borden")
        assertEquals("tt13207736:4:1", target?.contentId)
        assertEquals(true, requested.any { it.contains("search=Monster%3A%20The%20Lizzie%20Borden%20Story") })

        // Cached: a second addon asking for the same episode costs no requests.
        val before = requested.size
        assertEquals("tt13207736:4:1", resolver.resolve(299939, 1, 1, "")?.contentId)
        assertEquals(before, requested.size)
    }

    @Test
    fun `resolver gives up without an exact match`() = runBlocking {
        val resolver = AnthologyEpisodeResolver(
            fetchJson = { url ->
                when {
                    url.contains("/season/") -> """{"name":"Episode 1","air_date":"2020-01-01","external_ids":{}}"""
                    url.contains("/tv/") -> """{"name":"Some Show"}"""
                    url.contains("search=") -> """{"metas":[{"id":"tt13207736"}]}"""
                    else -> """{"meta":{"videos":$monsterVideos}}"""
                }
            },
            tmdbApiKey = { "key" }
        )
        assertNull(resolver.resolve(1, 1, 1, "Some Show"))
    }

    @Test
    fun `anthology series id lets IMDb-only addons qualify`() {
        assertEquals(
            listOf("tmdb:299939", "tt13207736"),
            buildEpisodeAddonLookupIds(imdbId = "tmdb:299939", tmdbId = 299939, anthologySeriesId = "tt13207736")
        )
    }
}
