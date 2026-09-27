package com.arflix.tv.ui.skin.baukasten

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Result of reading building instructions.
 *
 * [kaputt] means the file could not be used at all (not JSON, not an object); [anleitung]
 * is then the built-in default. [hinweise] are German, user-facing notes about single values
 * that were ignored or corrected — the rest of the file still applies.
 */
data class SkinLeseErgebnis(
    val anleitung: SkinBauanleitung,
    val hinweise: List<String>,
    val kaputt: Boolean,
)

/**
 * Reads the JSON building instructions. Never throws: anything unknown, missing or of the
 * wrong type falls back to the default value and produces a German note that says WHAT
 * is wrong, because the user edits this file on a phone and needs to find the spot.
 */
object SkinAnleitungLeser {

    const val UNTERSTUETZTE_VERSION = 1
    private const val MAX_TEXT_LAENGE = 256 * 1024
    private val zeileSpalte = Regex("line (\\d+) column (\\d+)")

    fun lesen(text: String): SkinLeseErgebnis {
        if (text.isBlank()) {
            return kaputt("Die Bauanleitung ist leer.")
        }
        if (text.length > MAX_TEXT_LAENGE) {
            return kaputt("Die Bauanleitung ist zu groß (mehr als 256 KB). Ist das die richtige Datei?")
        }
        val wurzel: JsonElement = try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            return kaputt(syntaxFehler(e))
        } catch (e: IllegalStateException) {
            return kaputt(syntaxFehler(e))
        }
        if (!wurzel.isJsonObject) {
            return kaputt(
                "Die Bauanleitung muss mit { beginnen und mit } enden. " +
                    "Gefunden wurde etwas anderes — ist das die richtige Datei?"
            )
        }
        val hinweise = mutableListOf<String>()
        val leser = Leser(hinweise)
        val anleitung = leser.anleitung(wurzel.asJsonObject)
        return SkinLeseErgebnis(anleitung = anleitung, hinweise = hinweise, kaputt = false)
    }

    private fun kaputt(meldung: String) = SkinLeseErgebnis(
        anleitung = SkinBauanleitung(),
        hinweise = listOf(meldung),
        kaputt = true,
    )

    private fun syntaxFehler(e: Exception): String {
        val stelle = zeileSpalte.find(e.message.orEmpty())
            ?.let { " (Zeile ${it.groupValues[1]}, Zeichen ${it.groupValues[2]})" }
            .orEmpty()
        return "Die Bauanleitung ist kein gültiges JSON$stelle. Häufige Ursachen: ein fehlendes " +
            "Komma zwischen zwei Einträgen, ein Komma zu viel vor } oder ], oder ein " +
            "fehlendes Anführungszeichen."
    }

    /** Reads one object level; every getter notes problems and returns the default. */
    private class Leser(private val hinweise: MutableList<String>) {

        fun anleitung(o: JsonObject): SkinBauanleitung {
            val standard = SkinBauanleitung()
            bekannt(o, "", setOf("version", "name", "farben", "titelkarte", "reihen", "kacheln", "menue"))
            o.get("version")?.let { v ->
                val version = (v as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
                when {
                    version == null -> hinweise += "„version“: erwartet eine Zahl (1) — wird ignoriert."
                    version > UNTERSTUETZTE_VERSION -> hinweise +=
                        "„version“ ist $version, diese App kennt Version $UNTERSTUETZTE_VERSION. " +
                        "Neuere Einträge werden ignoriert."
                }
            }
            return SkinBauanleitung(
                name = text(o, "", "name", standard.name),
                farben = objekt(o, "", "farben")?.let { farben(it) } ?: standard.farben,
                titelkarte = objekt(o, "", "titelkarte")?.let { titelkarte(it) } ?: standard.titelkarte,
                reihen = objekt(o, "", "reihen")?.let { reihen(it) } ?: standard.reihen,
                kacheln = objekt(o, "", "kacheln")?.let { kacheln(it) } ?: standard.kacheln,
                menue = objekt(o, "", "menue")?.let { menue(it) } ?: standard.menue,
            )
        }

        private fun farben(o: JsonObject): SkinFarben {
            val s = SkinFarben()
            val p = "farben"
            bekannt(o, p, setOf("akzent", "hintergrund", "hintergrundAbdunklung", "text", "textLeise", "glas", "glasDeckkraft", "glasRand"))
            return SkinFarben(
                akzent = farbe(o, p, "akzent", s.akzent),
                hintergrund = farbe(o, p, "hintergrund", s.hintergrund),
                hintergrundAbdunklung = anteil(o, p, "hintergrundAbdunklung", s.hintergrundAbdunklung),
                text = farbe(o, p, "text", s.text),
                textLeise = farbe(o, p, "textLeise", s.textLeise),
                glas = farbe(o, p, "glas", s.glas),
                glasDeckkraft = anteil(o, p, "glasDeckkraft", s.glasDeckkraft),
                glasRand = farbe(o, p, "glasRand", s.glasRand),
            )
        }

        private fun titelkarte(o: JsonObject): SkinTitelkarteWerte {
            val s = SkinTitelkarteWerte()
            val p = "titelkarte"
            bekannt(o, p, setOf("groesse", "rahmen", "ecken", "randfarbe", "abdunklung", "knoepfe", "schild", "logo", "beschreibungZeilen"))
            return SkinTitelkarteWerte(
                groesse = auswahl(o, p, "groesse", s.groesse, mapOf("gross" to SkinGroesse.GROSS, "klein" to SkinGroesse.KLEIN)),
                rahmen = janein(o, p, "rahmen", s.rahmen),
                ecken = zahl(o, p, "ecken", s.ecken, 0, 60),
                randfarbe = farbe(o, p, "randfarbe", s.randfarbe),
                abdunklung = anteil(o, p, "abdunklung", s.abdunklung),
                knoepfe = auswahlListe(
                    o, p, "knoepfe", s.knoepfe,
                    mapOf(
                        "abspielen" to SkinTitelKnopf.ABSPIELEN,
                        "details" to SkinTitelKnopf.DETAILS,
                        "trailer" to SkinTitelKnopf.TRAILER,
                        "merkliste" to SkinTitelKnopf.MERKLISTE,
                    )
                ),
                schild = janein(o, p, "schild", s.schild),
                logo = janein(o, p, "logo", s.logo),
                beschreibungZeilen = zahl(o, p, "beschreibungZeilen", s.beschreibungZeilen, 0, 6),
            )
        }

        private fun reihen(o: JsonObject): SkinReihenWerte {
            val s = SkinReihenWerte()
            val p = "reihen"
            bekannt(o, p, setOf("reihenfolge", "ausblenden", "anzahlZeigen"))
            return SkinReihenWerte(
                reihenfolge = textListe(o, p, "reihenfolge", s.reihenfolge),
                ausblenden = textListe(o, p, "ausblenden", s.ausblenden),
                anzahlZeigen = janein(o, p, "anzahlZeigen", s.anzahlZeigen),
            )
        }

        private fun kacheln(o: JsonObject): SkinKachelWerte {
            val s = SkinKachelWerte()
            val p = "kacheln"
            bekannt(o, p, setOf("stil", "breite", "ecken", "leuchtrahmen", "fortschritt", "titel"))
            return SkinKachelWerte(
                stil = auswahl(o, p, "stil", s.stil, mapOf("glas" to SkinKachelStil.GLAS, "flach" to SkinKachelStil.FLACH)),
                breite = zahl(o, p, "breite", s.breite, 120, 320),
                ecken = zahl(o, p, "ecken", s.ecken, 0, 40),
                leuchtrahmen = farbe(o, p, "leuchtrahmen", s.leuchtrahmen),
                fortschritt = janein(o, p, "fortschritt", s.fortschritt),
                titel = janein(o, p, "titel", s.titel),
            )
        }

        private fun menue(o: JsonObject): SkinMenueWerte {
            val s = SkinMenueWerte()
            val p = "menue"
            bekannt(o, p, setOf("stil", "begruessung", "datum"))
            return SkinMenueWerte(
                stil = auswahl(o, p, "stil", s.stil, mapOf("glas" to SkinMenueStil.GLAS, "klassisch" to SkinMenueStil.KLASSISCH)),
                begruessung = janein(o, p, "begruessung", s.begruessung),
                datum = janein(o, p, "datum", s.datum),
            )
        }

        // ── single values ──

        private fun pfad(p: String, name: String) = if (p.isEmpty()) name else "$p.$name"

        private fun bekannt(o: JsonObject, p: String, namen: Set<String>) {
            o.keySet().filter { it !in namen }.forEach { unbekannt ->
                hinweise += "„${pfad(p, unbekannt)}“ kennt diese App nicht — wird ignoriert. " +
                    "Tippfehler? Erlaubt sind hier: ${namen.joinToString(", ")}."
            }
        }

        private fun objekt(o: JsonObject, p: String, name: String): JsonObject? {
            val v = o.get(name) ?: return null
            if (v.isJsonObject) return v.asJsonObject
            hinweise += "„${pfad(p, name)}“: erwartet einen Block in { } — Standardwerte werden benutzt."
            return null
        }

        private fun primitiv(o: JsonObject, name: String): JsonPrimitive? =
            o.get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

        private fun zeige(v: JsonElement?): String = v?.toString() ?: "nichts"

        fun text(o: JsonObject, p: String, name: String, standard: String): String {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            if (prim != null && prim.isString) return prim.asString
            hinweise += "„${pfad(p, name)}“: erwartet einen Text in Anführungszeichen, gefunden ${zeige(v)}."
            return standard
        }

        fun janein(o: JsonObject, p: String, name: String, standard: Boolean): Boolean {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            if (prim != null && prim.isBoolean) return prim.asBoolean
            hinweise += "„${pfad(p, name)}“: erwartet true (ja) oder false (nein), gefunden ${zeige(v)}. " +
                "Standard ${if (standard) "true" else "false"} wird benutzt."
            return standard
        }

        fun zahl(o: JsonObject, p: String, name: String, standard: Int, min: Int, max: Int): Int {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            val wert = prim?.takeIf { it.isNumber }?.asDouble
            if (wert == null) {
                hinweise += "„${pfad(p, name)}“: erwartet eine Zahl ohne Anführungszeichen, gefunden ${zeige(v)}. " +
                    "Standard $standard wird benutzt."
                return standard
            }
            val ganz = wert.toInt()
            if (ganz < min || ganz > max) {
                val korrigiert = ganz.coerceIn(min, max)
                hinweise += "„${pfad(p, name)}“: $ganz liegt außerhalb von $min bis $max — $korrigiert wird benutzt."
                return korrigiert
            }
            return ganz
        }

        fun anteil(o: JsonObject, p: String, name: String, standard: Float): Float {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            val wert = prim?.takeIf { it.isNumber }?.asDouble
            if (wert == null) {
                hinweise += "„${pfad(p, name)}“: erwartet eine Zahl von 0 bis 1 (z. B. 0.5), gefunden ${zeige(v)}. " +
                    "Standard $standard wird benutzt."
                return standard
            }
            if (wert < 0.0 || wert > 1.0) {
                val korrigiert = wert.coerceIn(0.0, 1.0).toFloat()
                hinweise += "„${pfad(p, name)}“: $wert liegt außerhalb von 0 bis 1 — $korrigiert wird benutzt."
                return korrigiert
            }
            return wert.toFloat()
        }

        fun farbe(o: JsonObject, p: String, name: String, standard: Long): Long {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            val roh = prim?.takeIf { it.isString }?.asString
            val gelesen = roh?.let { farbeAusText(it) }
            if (gelesen == null) {
                hinweise += "„${pfad(p, name)}“: erwartet eine Farbe wie \"#5EEAD4\" (oder \"#80FFFFFF\" " +
                    "mit Durchsichtigkeit), gefunden ${zeige(v)}. Standardfarbe wird benutzt."
                return standard
            }
            return gelesen
        }

        fun <T> auswahl(o: JsonObject, p: String, name: String, standard: T, erlaubt: Map<String, T>): T {
            val v = o.get(name) ?: return standard
            val prim = primitiv(o, name)
            val roh = prim?.takeIf { it.isString }?.asString?.trim()?.lowercase()
            val treffer = roh?.let { erlaubt[normalisiereWort(it)] }
            if (treffer == null) {
                val standardName = erlaubt.entries.firstOrNull { it.value == standard }?.key
                hinweise += "„${pfad(p, name)}“: erlaubt sind ${erlaubt.keys.joinToString(" oder ") { "\"$it\"" }}, " +
                    "gefunden ${zeige(v)}. Standard \"$standardName\" wird benutzt."
                return standard
            }
            return treffer
        }

        fun <T> auswahlListe(o: JsonObject, p: String, name: String, standard: List<T>, erlaubt: Map<String, T>): List<T> {
            val v = o.get(name) ?: return standard
            val liste = v as? JsonArray
            if (liste == null) {
                hinweise += "„${pfad(p, name)}“: erwartet eine Liste in [ ], z. B. [\"${erlaubt.keys.first()}\"]. " +
                    "Standard wird benutzt."
                return standard
            }
            val ergebnis = mutableListOf<T>()
            liste.forEachIndexed { index, eintrag ->
                val roh = (eintrag as? JsonPrimitive)?.takeIf { it.isString }?.asString?.trim()?.lowercase()
                val treffer = roh?.let { erlaubt[normalisiereWort(it)] }
                when {
                    treffer == null -> hinweise += "„${pfad(p, name)}“ Eintrag ${index + 1}: ${zeige(eintrag)} " +
                        "gibt es nicht — erlaubt sind ${erlaubt.keys.joinToString(", ")}. Wird ausgelassen."
                    treffer in ergebnis -> hinweise += "„${pfad(p, name)}“: ${zeige(eintrag)} steht doppelt — " +
                        "der zweite wird ausgelassen."
                    else -> ergebnis += treffer
                }
            }
            return ergebnis
        }

        fun textListe(o: JsonObject, p: String, name: String, standard: List<String>): List<String> {
            val v = o.get(name) ?: return standard
            val liste = v as? JsonArray
            if (liste == null) {
                hinweise += "„${pfad(p, name)}“: erwartet eine Liste in [ ], z. B. [\"weiterschauen\", \"rest\"]. " +
                    "Standard wird benutzt."
                return standard
            }
            return liste.mapIndexedNotNull { index, eintrag ->
                val text = (eintrag as? JsonPrimitive)?.takeIf { it.isString }?.asString?.trim()
                if (text.isNullOrEmpty()) {
                    hinweise += "„${pfad(p, name)}“ Eintrag ${index + 1}: erwartet einen Text in " +
                        "Anführungszeichen, gefunden ${zeige(eintrag)}. Wird ausgelassen."
                    null
                } else {
                    text
                }
            }
        }
    }

    /** Accepts umlauts and "ss" spellings so "Größe"-style values still match. */
    internal fun normalisiereWort(wort: String): String = wort
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

    /** "#RGB", "#RRGGBB" or "#AARRGGBB" → ARGB, or null. */
    internal fun farbeAusText(text: String): Long? {
        val hex = text.trim().removePrefix("#")
        if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) return null
        return when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2)
                val g = hex[1].toString().repeat(2)
                val b = hex[2].toString().repeat(2)
                ("FF$r$g$b").toLong(16)
            }
            6 -> ("FF$hex").toLong(16)
            8 -> hex.toLong(16)
            else -> null
        }
    }
}
