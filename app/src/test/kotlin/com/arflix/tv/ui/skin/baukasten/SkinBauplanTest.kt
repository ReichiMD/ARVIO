package com.arflix.tv.ui.skin.baukasten

import org.junit.Assert.assertEquals
import org.junit.Test

class SkinBauplanTest {

    private val reihen = listOf(
        SkinReihenKopf("continue_watching", "Weiterschauen"),
        SkinReihenKopf("trending_movies", "Im Trend – Filme"),
        SkinReihenKopf("trending_series", "Im Trend – Serien"),
        SkinReihenKopf("favorite_tv", "TV-Favoriten"),
    )

    @Test
    fun `default plan keeps Prodigy's order`() {
        assertEquals(
            listOf("continue_watching", "trending_movies", "trending_series", "favorite_tv"),
            planeReihen(reihen, SkinReihenWerte())
        )
    }

    @Test
    fun `named rows first, by id or title, rest after`() {
        val plan = planeReihen(
            reihen,
            SkinReihenWerte(reihenfolge = listOf("tv-favoriten", "rest", "weiterschauen"))
        )
        assertEquals(listOf("favorite_tv", "trending_movies", "trending_series", "continue_watching"), plan)
    }

    @Test
    fun `without rest only named rows show`() {
        val plan = planeReihen(reihen, SkinReihenWerte(reihenfolge = listOf("TRENDING_SERIES", "weiterschauen")))
        assertEquals(listOf("trending_series", "continue_watching"), plan)
    }

    @Test
    fun `hidden rows never show, unknown names are skipped`() {
        val plan = planeReihen(
            reihen,
            SkinReihenWerte(reihenfolge = listOf("gibt es nicht", "weiterschauen", "rest"), ausblenden = listOf("Im Trend – Filme"))
        )
        assertEquals(listOf("continue_watching", "trending_series", "favorite_tv"), plan)
    }

    @Test
    fun `a row is never listed twice, rest skips rows named elsewhere`() {
        val plan = planeReihen(reihen, SkinReihenWerte(reihenfolge = listOf("rest", "weiterschauen", "rest", "weiterschauen")))
        assertEquals(listOf("trending_movies", "trending_series", "favorite_tv", "continue_watching"), plan)
    }

    @Test
    fun `sign text shows type and focused row`() {
        val titel = SkinTitel(
            schluessel = "MOVIE_1", titel = "X", logo = null, bild = null, infos = emptyList(),
            bewertung = null, freigabe = null, beschreibung = "", art = "FILM", fortschritt = 0f,
            restzeit = null, kannTrailer = true, inMerkliste = null,
        )
        assertEquals("FILM · WEITERSCHAUEN", schildText(titel, "Weiterschauen"))
        assertEquals("FILM", schildText(titel, null))
        assertEquals(null, schildText(null, "Weiterschauen"))
    }
}
