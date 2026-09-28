package com.arflix.tv.ui.screens.details

import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.repository.toStreamSource
import com.arflix.tv.domain.model.LocalScraperResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementalStreamMergeTest {
    private fun addonStream(name: String) = StreamSource(
        source = name,
        addonName = "Addon",
        addonId = "com.example.addon",
        quality = "1080p",
        size = "",
        url = "https://example.invalid/$name.mkv"
    )

    private val pluginStream = LocalScraperResult(
        title = "Plugin source",
        url = "https://example.invalid/plugin.m3u8",
        provider = "Some Plugin"
    ).toStreamSource()

    @Test
    fun `plugin sources survive the next addon emission`() {
        val current = listOf(addonStream("first"), pluginStream)

        val merged = mergeAddonEmissionWithSupplementalStreams(
            addonStreams = listOf(addonStream("first"), addonStream("second")),
            currentStreams = current
        )

        assertEquals(listOf("first", "second", "Plugin source"), merged.map { it.source })
    }

    @Test
    fun `addon sources still come from the latest emission only`() {
        val merged = mergeAddonEmissionWithSupplementalStreams(
            addonStreams = listOf(addonStream("second")),
            currentStreams = listOf(addonStream("stale"))
        )

        assertEquals(listOf("second"), merged.map { it.source })
    }

    @Test
    fun `a plugin source is not duplicated when it is already listed`() {
        val merged = mergeAddonEmissionWithSupplementalStreams(
            addonStreams = listOf(pluginStream),
            currentStreams = listOf(pluginStream)
        )

        assertEquals(1, merged.size)
    }
}
