package com.arflix.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCatalogOrderTest {
    @Test
    fun personalRowsThenTrendingLeadTheCompleteCatalogue() {
        val ids = MediaRepository.buildPreinstalledDefaults().map { it.id }

        assertEquals(
            listOf(
                PersonalRecommendations.MOVIES_CATALOG_ID,
                PersonalRecommendations.SHOWS_CATALOG_ID,
                "trending_movies",
                "trending_tv",
                "trending_anime"
            ),
            ids.take(5)
        )
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.indexOf("favorite_tv") > ids.indexOf("trending_anime"))
    }
}
