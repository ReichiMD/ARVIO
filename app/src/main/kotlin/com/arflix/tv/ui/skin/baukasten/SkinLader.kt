package com.arflix.tv.ui.skin.baukasten

import kotlinx.coroutines.CancellationException

/** Where the instructions in use came from. */
enum class SkinQuelle {
    /** Built into the app (no address set, or nothing usable ever loaded). */
    EINGEBAUT,
    /** The copy saved on this device from an earlier load. */
    GESPEICHERT,
    /** Freshly fetched from the address. */
    NETZ,
}

/**
 * What the loader delivers: the instructions to use, where they came from, and German notes
 * for the skin settings page ([meldung] = one headline, [hinweise] = details per value).
 */
data class SkinLadeErgebnis(
    val anleitung: SkinBauanleitung,
    val quelle: SkinQuelle,
    val meldung: String?,
    val hinweise: List<String>,
)

/** Device storage for the last good copy, one per address. */
interface SkinSpeicher {
    fun lesen(adresse: String): String?
    fun schreiben(adresse: String, text: String)
}

/** Fetches the text behind an address; throws when there is no network or the file is missing. */
fun interface SkinHoler {
    suspend fun holen(adresse: String): String
}

/**
 * Loader rules (docs/80 E9/E11):
 * - no address → built-in default instructions;
 * - the last good copy is kept on the device, so the skin works without network;
 * - a new version is only fetched when there is network, and only saved when it is usable;
 * - broken or never loaded → built-in default, with a German message saying why.
 */
class SkinLader(
    private val speicher: SkinSpeicher,
    private val holer: SkinHoler,
) {

    /** Fast path without network: what can be shown right now. */
    fun sofort(adresseRoh: String?): SkinLadeErgebnis {
        val adresse = normalisiereSkinAdresse(adresseRoh) ?: return eingebaut(null)
        val gespeichert = speicher.lesen(adresse) ?: return eingebaut(
            "Die Bauanleitung wurde noch nie geladen — bis dahin gilt die eingebaute."
        )
        val gelesen = SkinAnleitungLeser.lesen(gespeichert)
        if (gelesen.kaputt) return eingebaut(gelesen.hinweise.firstOrNull())
        return SkinLadeErgebnis(gelesen.anleitung, SkinQuelle.GESPEICHERT, null, gelesen.hinweise)
    }

    /** Fetch a fresh version; falls back to the saved copy, then to the built-in one. */
    suspend fun neuLaden(adresseRoh: String?): SkinLadeErgebnis {
        val adresse = normalisiereSkinAdresse(adresseRoh) ?: return eingebaut(null)
        val text = try {
            holer.holen(adresse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mitGespeicherter(
                adresse,
                "Die Bauanleitung konnte nicht geladen werden (${fehlerText(e)})."
            )
        }
        val gelesen = SkinAnleitungLeser.lesen(text)
        if (gelesen.kaputt) {
            return mitGespeicherter(
                adresse,
                "Die neue Fassung ist kaputt: ${gelesen.hinweise.firstOrNull().orEmpty()}"
            )
        }
        speicher.schreiben(adresse, text)
        return SkinLadeErgebnis(gelesen.anleitung, SkinQuelle.NETZ, null, gelesen.hinweise)
    }

    private fun mitGespeicherter(adresse: String, grund: String): SkinLadeErgebnis {
        val gespeichert = speicher.lesen(adresse)?.let { SkinAnleitungLeser.lesen(it) }
        return if (gespeichert != null && !gespeichert.kaputt) {
            SkinLadeErgebnis(
                gespeichert.anleitung,
                SkinQuelle.GESPEICHERT,
                "$grund Die zuletzt geladene Fassung wird weiter benutzt.",
                gespeichert.hinweise,
            )
        } else {
            eingebaut("$grund Die eingebaute Standard-Anleitung wird benutzt.")
        }
    }

    private fun eingebaut(meldung: String?) = SkinLadeErgebnis(
        anleitung = SkinBauanleitung(),
        quelle = SkinQuelle.EINGEBAUT,
        meldung = meldung,
        hinweise = emptyList(),
    )

    private fun fehlerText(e: Exception): String = when (e) {
        is SkinHolFehler -> e.message.orEmpty()
        is java.net.UnknownHostException -> "keine Internetverbindung oder Adresse falsch"
        is java.net.SocketTimeoutException -> "der Server antwortet nicht"
        is java.io.IOException -> "Netzwerkfehler"
        else -> "unbekannter Fehler"
    }
}

/** A fetch failure whose message is already German and user-facing. */
class SkinHolFehler(meldung: String) : java.io.IOException(meldung)

/**
 * Cleans up an address typed or pasted on a phone. A GitHub page link
 * (github.com/…/blob/…) is turned into the raw file link, because the page link returns
 * the web page, not the file. Blank → null (= use built-in instructions).
 */
fun normalisiereSkinAdresse(roh: String?): String? {
    val adresse = roh?.trim().orEmpty()
    if (adresse.isEmpty()) return null
    val mitSchema = if (adresse.startsWith("http://") || adresse.startsWith("https://")) {
        adresse
    } else {
        "https://$adresse"
    }
    val github = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(mitSchema)
    if (github != null) {
        val (besitzer, repo, rest) = github.destructured
        return "https://raw.githubusercontent.com/$besitzer/$repo/$rest"
    }
    return mitSchema
}
