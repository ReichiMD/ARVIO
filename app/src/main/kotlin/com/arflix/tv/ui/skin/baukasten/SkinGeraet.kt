package com.arflix.tv.ui.skin.baukasten

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arflix.tv.di.RepositoryAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Device side of the building kit: the per-profile skin settings and the loader wiring
 * (file storage + network). The skin keeps its own DataStore file on purpose — it never
 * touches Prodigy's settings keys, and cloud sync does not carry it (docs/80 E11).
 */
private val Context.skinDataStore: DataStore<Preferences> by preferencesDataStore(name = "arvio_skin")

enum class SkinEffekte { VOLL, SPARSAM }

data class SkinEinstellungen(
    val an: Boolean = false,
    val effekte: SkinEffekte = SkinEffekte.VOLL,
    val adresse: String = "",
)

private fun anKey(profileId: String) = booleanPreferencesKey("p_${profileId}_an")
private fun effekteKey(profileId: String) = stringPreferencesKey("p_${profileId}_effekte")
private fun adresseKey(profileId: String) = stringPreferencesKey("p_${profileId}_adresse")

private fun Context.aktivesProfil(): StateFlow<String> =
    EntryPointAccessors.fromApplication(applicationContext, RepositoryAccessEntryPoint::class.java)
        .profileManager()
        .currentProfileId

fun Context.skinEinstellungenFlow(): Flow<SkinEinstellungen> =
    combine(aktivesProfil(), skinDataStore.data) { profileId, prefs ->
        SkinEinstellungen(
            an = prefs[anKey(profileId)] ?: false,
            effekte = if (prefs[effekteKey(profileId)] == "sparsam") SkinEffekte.SPARSAM else SkinEffekte.VOLL,
            adresse = prefs[adresseKey(profileId)].orEmpty(),
        )
    }.distinctUntilChanged()

suspend fun Context.skinEinstellungAendern(block: (SkinEinstellungen) -> SkinEinstellungen) {
    val profileId = aktivesProfil().value
    skinDataStore.edit { prefs ->
        val alt = SkinEinstellungen(
            an = prefs[anKey(profileId)] ?: false,
            effekte = if (prefs[effekteKey(profileId)] == "sparsam") SkinEffekte.SPARSAM else SkinEffekte.VOLL,
            adresse = prefs[adresseKey(profileId)].orEmpty(),
        )
        val neu = block(alt)
        prefs[anKey(profileId)] = neu.an
        prefs[effekteKey(profileId)] = if (neu.effekte == SkinEffekte.SPARSAM) "sparsam" else "voll"
        prefs[adresseKey(profileId)] = neu.adresse.trim()
    }
}

/**
 * Last known settings of this app start. Returning to Home rebuilds the page switch; starting
 * from this value instead of "unknown" avoids an empty frame on every return — for the skin
 * and for Prodigy's Home when the skin is off.
 */
@Volatile private var letzteEinstellungen: SkinEinstellungen? = null

@Composable
fun rememberSkinEinstellungen(): SkinEinstellungen? {
    val context = LocalContext.current
    val flow = remember(context) { context.skinEinstellungenFlow() }
    val stand by flow.collectAsState(initial = letzteEinstellungen)
    stand?.let { letzteEinstellungen = it }
    return stand
}

/** Last good copy per address, as a small file in the app's private storage. */
private class SkinDateiSpeicher(context: Context) : SkinSpeicher {
    private val ordner = File(context.filesDir, "arvio_skin")

    private fun datei(adresse: String): File {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(adresse.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(ordner, "$hash.json")
    }

    override fun lesen(adresse: String): String? =
        runCatching { datei(adresse).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun schreiben(adresse: String, text: String) {
        runCatching {
            ordner.mkdirs()
            val ziel = datei(adresse)
            val tmp = File(ordner, ziel.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(ziel)) {
                ziel.writeText(text)
                tmp.delete()
            }
        }
    }
}

private class SkinNetzHoler : SkinHoler {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun holen(adresse: String): String = withContext(Dispatchers.IO) {
        val anfrage = Request.Builder().url(adresse).header("Cache-Control", "no-cache").build()
        client.newCall(anfrage).execute().use { antwort ->
            when {
                antwort.code == 404 -> throw SkinHolFehler(
                    "unter der Adresse liegt keine Datei (404) — Adresse und Dateiname prüfen, " +
                        "und ob das Repository öffentlich ist"
                )
                !antwort.isSuccessful -> throw SkinHolFehler("der Server meldet Fehler ${antwort.code}")
            }
            val body = antwort.body ?: throw SkinHolFehler("die Antwort war leer")
            if (body.contentLength() > 256 * 1024) throw SkinHolFehler("die Datei ist größer als 256 KB")
            body.string()
        }
    }
}

/**
 * Process-wide holder: one loaded result per address. The first use of an address shows the
 * saved copy at once and then fetches a fresh version once per app start (only with network).
 */
object SkinAnleitungen {
    private val stand = MutableStateFlow<Map<String, SkinLadeErgebnis>>(emptyMap())
    private val schonGeladen = mutableSetOf<String>()
    private val sperre = Mutex()
    @Volatile private var lader: SkinLader? = null

    private fun lader(context: Context): SkinLader =
        lader ?: synchronized(this) {
            lader ?: SkinLader(SkinDateiSpeicher(context.applicationContext), SkinNetzHoler()).also { lader = it }
        }

    val alle: StateFlow<Map<String, SkinLadeErgebnis>> get() = stand

    fun schluessel(adresse: String): String = normalisiereSkinAdresse(adresse).orEmpty()

    /** Loads once per app start; later calls are no-ops unless [erzwingen]. */
    suspend fun sicherstellen(context: Context, adresse: String, erzwingen: Boolean = false) {
        val key = schluessel(adresse)
        val lader = lader(context)
        sperre.withLock {
            if (key !in stand.value) {
                val sofort = withContext(Dispatchers.IO) { lader.sofort(adresse) }
                stand.value = stand.value + (key to sofort)
            }
            if (!erzwingen && key in schonGeladen) return
            schonGeladen += key
        }
        if (key.isEmpty()) return
        val frisch = lader.neuLaden(adresse)
        stand.value = stand.value + (key to frisch)
    }
}

/** The instructions for the current profile's address; null until the first read is done. */
@Composable
fun rememberSkinLadeErgebnis(adresse: String): SkinLadeErgebnis? {
    val context = LocalContext.current
    LaunchedEffect(adresse) { SkinAnleitungen.sicherstellen(context, adresse) }
    val alle by SkinAnleitungen.alle.collectAsState()
    return alle[SkinAnleitungen.schluessel(adresse)]
}
