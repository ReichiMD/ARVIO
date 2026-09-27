package com.arflix.tv.ui.skin.baukasten

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class SkinLaderTest {

    private class Speicher : SkinSpeicher {
        val dateien = mutableMapOf<String, String>()
        override fun lesen(adresse: String): String? = dateien[adresse]
        override fun schreiben(adresse: String, text: String) {
            dateien[adresse] = text
        }
    }

    private val adresse = "https://raw.githubusercontent.com/u/r/main/skin.json"
    private val gut = """{ "name": "Mein Skin", "kacheln": { "stil": "flach" } }"""
    private val gutZwei = """{ "name": "Zweite Fassung" }"""

    @Test
    fun `no address means built-in instructions, network is never asked`() = runTest {
        var gefragt = false
        val lader = SkinLader(Speicher(), SkinHoler { gefragt = true; gut })
        val ergebnis = lader.neuLaden("  ")
        assertEquals(SkinQuelle.EINGEBAUT, ergebnis.quelle)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
        assertNull(ergebnis.meldung)
        assertEquals(false, gefragt)
    }

    @Test
    fun `good file is used and saved for offline use`() = runTest {
        val speicher = Speicher()
        val lader = SkinLader(speicher, SkinHoler { gut })
        val ergebnis = lader.neuLaden(adresse)
        assertEquals(SkinQuelle.NETZ, ergebnis.quelle)
        assertEquals("Mein Skin", ergebnis.anleitung.name)
        assertEquals(gut, speicher.dateien[adresse])

        // Later without network: the saved copy.
        val offline = SkinLader(speicher, SkinHoler { throw UnknownHostException("x") })
        assertEquals(SkinQuelle.GESPEICHERT, offline.sofort(adresse).quelle)
        val nachVersuch = offline.neuLaden(adresse)
        assertEquals(SkinQuelle.GESPEICHERT, nachVersuch.quelle)
        assertEquals("Mein Skin", nachVersuch.anleitung.name)
        assertTrue(nachVersuch.meldung!!, nachVersuch.meldung!!.contains("keine Internetverbindung"))
    }

    @Test
    fun `never loaded and no network falls back to built-in with a message`() = runTest {
        val lader = SkinLader(Speicher(), SkinHoler { throw UnknownHostException("x") })
        val sofort = lader.sofort(adresse)
        assertEquals(SkinQuelle.EINGEBAUT, sofort.quelle)
        assertTrue(sofort.meldung!!.contains("noch nie geladen"))
        val ergebnis = lader.neuLaden(adresse)
        assertEquals(SkinQuelle.EINGEBAUT, ergebnis.quelle)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
        assertTrue(ergebnis.meldung!!, ergebnis.meldung!!.contains("eingebaute Standard-Anleitung"))
    }

    @Test
    fun `broken new version keeps the last good copy and is not saved`() = runTest {
        val speicher = Speicher()
        SkinLader(speicher, SkinHoler { gut }).neuLaden(adresse)
        val ergebnis = SkinLader(speicher, SkinHoler { "{ kaputt" }).neuLaden(adresse)
        assertEquals(SkinQuelle.GESPEICHERT, ergebnis.quelle)
        assertEquals("Mein Skin", ergebnis.anleitung.name)
        assertTrue(ergebnis.meldung!!, ergebnis.meldung!!.contains("kaputt"))
        assertEquals(gut, speicher.dateien[adresse])
    }

    @Test
    fun `broken file with no saved copy uses built-in`() = runTest {
        val ergebnis = SkinLader(Speicher(), SkinHoler { "<html>Not Found</html>" }).neuLaden(adresse)
        assertEquals(SkinQuelle.EINGEBAUT, ergebnis.quelle)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
    }

    @Test
    fun `a new good version replaces the saved one`() = runTest {
        val speicher = Speicher()
        SkinLader(speicher, SkinHoler { gut }).neuLaden(adresse)
        val ergebnis = SkinLader(speicher, SkinHoler { gutZwei }).neuLaden(adresse)
        assertEquals("Zweite Fassung", ergebnis.anleitung.name)
        assertEquals(gutZwei, speicher.dateien[adresse])
    }

    @Test
    fun `saved copy that became unreadable is ignored`() {
        val speicher = Speicher().apply { dateien[adresse] = "{ kaputt" }
        val ergebnis = SkinLader(speicher, SkinHoler { gut }).sofort(adresse)
        assertEquals(SkinQuelle.EINGEBAUT, ergebnis.quelle)
    }

    @Test
    fun `notes about single values travel with the result`() = runTest {
        val ergebnis = SkinLader(Speicher(), SkinHoler { """{ "kacheln": { "stil": "gold" } }""" }).neuLaden(adresse)
        assertEquals(SkinQuelle.NETZ, ergebnis.quelle)
        assertEquals(1, ergebnis.hinweise.size)
    }

    @Test
    fun `github page links become raw file links`() {
        assertEquals(
            "https://raw.githubusercontent.com/ReichiMD/arvio-skin/main/skin.json",
            normalisiereSkinAdresse("https://github.com/ReichiMD/arvio-skin/blob/main/skin.json")
        )
        assertEquals(
            "https://raw.githubusercontent.com/u/r/main/a/b.json",
            normalisiereSkinAdresse(" github.com/u/r/blob/main/a/b.json ")
        )
        assertEquals(adresse, normalisiereSkinAdresse(adresse))
        assertNull(normalisiereSkinAdresse(""))
        assertNull(normalisiereSkinAdresse(null))
    }
}
