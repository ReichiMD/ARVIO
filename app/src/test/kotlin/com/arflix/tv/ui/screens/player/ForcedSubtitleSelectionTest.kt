package com.arflix.tv.ui.screens.player

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.Subtitle
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForcedSubtitleSelectionTest {
    private lateinit var model: PlayerViewModel
    private val store = ViewModelStore()
    private val full = Subtitle(id = "full", url = "", lang = "en", label = "English", isEmbedded = true)
    private val forced = full.copy(id = "forced", label = "English forced", isForced = true, hasForcedFlag = true)

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        model = PlayerViewModel(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
            streamIntegrationRepository = mockk(relaxed = true)
        )
        store.put("player", model)
        state().value = PlayerUiState(useForcedSubtitles = true)
    }

    @After fun tearDown() {
        val job = model.viewModelScope.coroutineContext[Job]
        try {
            store.clear()
            runBlocking { withTimeout(5_000) { job?.join() } }
        } finally {
            unmockkStatic(Log::class)
            Dispatchers.resetMain()
        }
    }

    @Test fun automaticFullTrackCanChangeToForcedAndBackWithAudio() {
        selectForAudio("de")
        assertEquals(full, model.uiState.value.selectedSubtitle)
        assertFalse(manualSelection())
        selectForAudio("en")
        assertEquals(forced, model.uiState.value.selectedSubtitle)
        selectForAudio("de")
        assertEquals(full, model.uiState.value.selectedSubtitle)
        assertFalse(manualSelection())
        selectForAudio("en")
        assertEquals(forced, model.uiState.value.selectedSubtitle)
    }

    @Test fun matchingAudioWithoutForcedTrackClearsAutomaticFullTrack() {
        selectForAudio("de", listOf(full))
        assertEquals(full, model.uiState.value.selectedSubtitle)
        assertFalse(manualSelection())
        selectForAudio("en", listOf(full))
        assertNull(model.uiState.value.selectedSubtitle)
    }

    @Test fun manualOffSurvivesAudioChanges() {
        selectForAudio("de")
        model.disableSubtitles()
        selectForAudio("en")
        assertNull(model.uiState.value.selectedSubtitle)
        assertTrue(manualSelection())
    }

    @Test fun explicitTrackPickSurvivesAudioChanges() {
        state().value = state().value.copy(selectedSubtitle = full)
        field("hasManualSubtitleSelection").set(model, true)
        field("userPickedSubtitle").set(model, true)
        selectForAudio("en")
        assertEquals(full, model.uiState.value.selectedSubtitle)
    }

    @Test fun disabledSettingKeepsExistingAutomaticSelectionGuard() {
        state().value = state().value.copy(useForcedSubtitles = false)
        selectForAudio("en")
        assertEquals(full, model.uiState.value.selectedSubtitle)
        assertTrue(manualSelection())
    }

    // Exercise the production selector without network, DataStore, or a running player.
    private fun selectForAudio(language: String, subtitles: List<Subtitle> = listOf(full, forced)) {
        field("currentAudioLanguage").set(model, language)
        PlayerViewModel::class.java.getDeclaredMethod(
            "applyPreferredSubtitle", String::class.java, List::class.java, String::class.java
        ).apply { isAccessible = true }.invoke(model, "en", subtitles, null)
    }

    private fun field(name: String) = PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun manualSelection() = field("hasManualSubtitleSelection").getBoolean(model)

    @Suppress("UNCHECKED_CAST")
    private fun state() = field("_uiState").get(model) as MutableStateFlow<PlayerUiState>
}
