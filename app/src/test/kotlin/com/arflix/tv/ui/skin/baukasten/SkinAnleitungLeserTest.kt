package com.arflix.tv.ui.skin.baukasten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinAnleitungLeserTest {

    @Test
    fun `built-in instructions text matches the defaults exactly and has no notes`() {
        val ergebnis = SkinAnleitungLeser.lesen(SKIN_STANDARD_ANLEITUNG)
        assertFalse(ergebnis.kaputt)
        assertEquals(emptyList<String>(), ergebnis.hinweise)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
    }

    @Test
    fun `broken json is reported with line and falls back to defaults`() {
        val ergebnis = SkinAnleitungLeser.lesen("{\n  \"name\": \"Test\"\n  \"farben\": {}\n}")
        assertTrue(ergebnis.kaputt)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
        assertTrue(ergebnis.hinweise.single(), ergebnis.hinweise.single().contains("kein gültiges JSON"))
        assertTrue(ergebnis.hinweise.single(), ergebnis.hinweise.single().contains("Zeile 3"))
    }

    @Test
    fun `empty file, a list or plain text are broken, never a crash`() {
        assertTrue(SkinAnleitungLeser.lesen("").kaputt)
        assertTrue(SkinAnleitungLeser.lesen("   ").kaputt)
        assertTrue(SkinAnleitungLeser.lesen("[1, 2]").kaputt)
        assertTrue(SkinAnleitungLeser.lesen("<html>404</html>").kaputt)
        assertTrue(SkinAnleitungLeser.lesen("{").kaputt)
    }

    @Test
    fun `empty object uses every default without notes`() {
        val ergebnis = SkinAnleitungLeser.lesen("{}")
        assertFalse(ergebnis.kaputt)
        assertEquals(SkinBauanleitung(), ergebnis.anleitung)
        assertTrue(ergebnis.hinweise.isEmpty())
    }

    @Test
    fun `missing fields keep defaults while given fields apply`() {
        val ergebnis = SkinAnleitungLeser.lesen(
            """{ "titelkarte": { "groesse": "klein", "rahmen": false }, "kacheln": { "stil": "flach" } }"""
        )
        assertFalse(ergebnis.kaputt)
        val a = ergebnis.anleitung
        assertEquals(SkinGroesse.KLEIN, a.titelkarte.groesse)
        assertFalse(a.titelkarte.rahmen)
        assertEquals(SkinTitelkarteWerte().ecken, a.titelkarte.ecken)
        assertEquals(SkinKachelStil.FLACH, a.kacheln.stil)
        assertEquals(SkinFarben(), a.farben)
        assertTrue(ergebnis.hinweise.isEmpty())
    }

    @Test
    fun `unknown keys and unknown choices produce German notes and defaults`() {
        val ergebnis = SkinAnleitungLeser.lesen(
            """{ "farbn": {}, "kacheln": { "stil": "gold", "breit": 200 }, "menue": { "stil": "Klassisch" } }"""
        )
        assertFalse(ergebnis.kaputt)
        assertEquals(SkinKachelStil.GLAS, ergebnis.anleitung.kacheln.stil)
        // Case does not matter for choices.
        assertEquals(SkinMenueStil.KLASSISCH, ergebnis.anleitung.menue.stil)
        val alle = ergebnis.hinweise.joinToString("\n")
        assertTrue(alle, alle.contains("„farbn“ kennt diese App nicht"))
        assertTrue(alle, alle.contains("„kacheln.breit“ kennt diese App nicht"))
        assertTrue(alle, alle.contains("„kacheln.stil“: erlaubt sind \"glas\" oder \"flach\""))
        assertEquals(3, ergebnis.hinweise.size)
    }

    @Test
    fun `wrong types fall back per value`() {
        val ergebnis = SkinAnleitungLeser.lesen(
            """{
              "titelkarte": { "ecken": "20", "rahmen": "ja", "knoepfe": "details" },
              "farben": { "akzent": "gruen", "glasDeckkraft": 3, "text": "#FFF" },
              "reihen": { "reihenfolge": ["rest", 5] },
              "menue": 7
            }"""
        )
        assertFalse(ergebnis.kaputt)
        val a = ergebnis.anleitung
        assertEquals(SkinTitelkarteWerte().ecken, a.titelkarte.ecken)
        assertTrue(a.titelkarte.rahmen)
        assertEquals(SkinTitelkarteWerte().knoepfe, a.titelkarte.knoepfe)
        assertEquals(SkinFarben().akzent, a.farben.akzent)
        assertEquals(1f, a.farben.glasDeckkraft)
        assertEquals(0xFFFFFFFF, a.farben.text)
        assertEquals(listOf("rest"), a.reihen.reihenfolge)
        assertEquals(SkinMenueWerte(), a.menue)
        assertEquals(7, ergebnis.hinweise.size)
    }

    @Test
    fun `numbers outside the range are clamped with a note`() {
        val ergebnis = SkinAnleitungLeser.lesen("""{ "kacheln": { "breite": 9999, "ecken": -3 } }""")
        assertEquals(320, ergebnis.anleitung.kacheln.breite)
        assertEquals(0, ergebnis.anleitung.kacheln.ecken)
        assertEquals(2, ergebnis.hinweise.size)
    }

    @Test
    fun `button list keeps order, drops unknown and duplicate entries`() {
        val ergebnis = SkinAnleitungLeser.lesen(
            """{ "titelkarte": { "knoepfe": ["Merkliste", "abspielen", "kaffee", "abspielen"] } }"""
        )
        assertEquals(
            listOf(SkinTitelKnopf.MERKLISTE, SkinTitelKnopf.ABSPIELEN),
            ergebnis.anleitung.titelkarte.knoepfe
        )
        assertEquals(2, ergebnis.hinweise.size)
    }

    @Test
    fun `umlaut spellings are accepted for choices`() {
        val ergebnis = SkinAnleitungLeser.lesen("""{ "titelkarte": { "groesse": "Groß" } }""")
        assertEquals(SkinGroesse.GROSS, ergebnis.anleitung.titelkarte.groesse)
        assertTrue(ergebnis.hinweise.isEmpty())
    }

    @Test
    fun `newer version is noted but still read`() {
        val ergebnis = SkinAnleitungLeser.lesen("""{ "version": 2, "name": "Neu" }""")
        assertFalse(ergebnis.kaputt)
        assertEquals("Neu", ergebnis.anleitung.name)
        assertEquals(1, ergebnis.hinweise.size)
    }

    @Test
    fun `colour text forms`() {
        assertEquals(0xFF5EEAD4, SkinAnleitungLeser.farbeAusText("#5EEAD4"))
        assertEquals(0x805EEAD4, SkinAnleitungLeser.farbeAusText("#805eead4"))
        assertEquals(0xFFFFFFFF, SkinAnleitungLeser.farbeAusText("fff"))
        assertEquals(null, SkinAnleitungLeser.farbeAusText("#12345"))
        assertEquals(null, SkinAnleitungLeser.farbeAusText("#GGGGGG"))
        assertEquals(null, SkinAnleitungLeser.farbeAusText(""))
    }
}
