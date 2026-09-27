package com.arflix.tv.ui.skin.baukasten

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import kotlinx.coroutines.android.awaitFrame

/**
 * The plan ("Bauplan"): reads the instructions and puts the stones together into the skin's
 * TV start page. Focus path (docs/80 E13, own path, not Prodigy's HomeFocusState):
 * menu bar ↔ title card buttons ↔ rows; back = to the menu, back again = leave the app.
 */

private const val WEITERSCHAUEN_ID = "continue_watching"

/**
 * Which rows are shown, in which order. An entry matches a row by id or by title (both
 * ignoring case), "weiterschauen" means Continue Watching, and "rest" inserts every row not
 * named anywhere, in Prodigy's order. Hidden rows ([SkinReihenWerte.ausblenden]) never show.
 */
fun planeReihen(reihen: List<SkinReihenKopf>, werte: SkinReihenWerte): List<String> {
    fun passt(eintrag: String, reihe: SkinReihenKopf): Boolean {
        val e = eintrag.trim().lowercase()
        if (e == SKIN_REIHE_WEITERSCHAUEN) return reihe.id == WEITERSCHAUEN_ID
        return reihe.id.lowercase() == e || reihe.titel.trim().lowercase() == e
    }
    val sichtbar = reihen.filter { reihe -> werte.ausblenden.none { passt(it, reihe) } }
    val benannt = sichtbar.filter { reihe ->
        werte.reihenfolge.any { it.trim().lowercase() != SKIN_REIHE_REST && passt(it, reihe) }
    }.map { it.id }.toSet()
    val ergebnis = LinkedHashSet<String>()
    werte.reihenfolge.forEach { eintrag ->
        if (eintrag.trim().lowercase() == SKIN_REIHE_REST) {
            sichtbar.filter { it.id !in benannt }.forEach { ergebnis += it.id }
        } else {
            sichtbar.filter { passt(eintrag, it) }.forEach { ergebnis += it.id }
        }
    }
    return ergebnis.toList()
}

/** Label for the small sign on the title card: "FILM · WEITERSCHAUEN". */
internal fun schildText(titel: SkinTitel?, reiheTitel: String?): String? {
    val art = titel?.art ?: return null
    return listOfNotNull(art, reiheTitel?.uppercase()).joinToString(" · ")
}

@Composable
fun SkinStartseite(
    daten: SkinStartDaten,
    aktionen: SkinAktionen,
    look: SkinLook,
) {
    CompositionLocalProvider(LocalSkinLook provides look) {
        val randLinks = 32.dp
        val reihenPlan = remember(daten.reihen, look.anleitung.reihen) {
            val ids = planeReihen(daten.reihen.map { SkinReihenKopf(it.id, it.titel) }, look.anleitung.reihen)
            val nachId = daten.reihen.associateBy { it.id }
            ids.mapNotNull { nachId[it] }.filter { it.kacheln.isNotEmpty() }
        }
        // Last focused tile survives a trip to Details and back (the page is rebuilt then).
        var fokusReihe by rememberSaveable { mutableStateOf<String?>(null) }
        var fokusKachel by rememberSaveable { mutableStateOf<String?>(null) }
        val reihenAktionen = remember(aktionen) {
            object : SkinAktionen by aktionen {
                override fun kachelFokussiert(reiheId: String, index: Int, schluessel: String) {
                    fokusReihe = reiheId
                    fokusKachel = schluessel
                    aktionen.kachelFokussiert(reiheId, index, schluessel)
                }
            }
        }
        val zielRequester = remember { FocusRequester() }
        var seiteHatFokus by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val schild = schildText(daten.titel, reihenPlan.firstOrNull { it.id == fokusReihe }?.titel)

        val startKnopf = remember { FocusRequester() }
        val ersterKnopf = remember { FocusRequester() }
        var menueHatFokus by remember { mutableStateOf(false) }

        // Entry: back on the tile the user left from, else the first title button.
        LaunchedEffect(daten.titel != null, reihenPlan.isNotEmpty()) {
            if (seiteHatFokus || daten.titel == null) return@LaunchedEffect
            awaitFrame()
            if (fokusKachel != null) {
                runCatching { zielRequester.requestFocus() }
                awaitFrame()
            }
            if (!seiteHatFokus) runCatching { ersterKnopf.requestFocus() }
        }

        BackHandler {
            if (menueHatFokus) {
                aktionen.beenden()
            } else {
                runCatching { startKnopf.requestFocus() }
            }
        }

        Box(Modifier.fillMaxSize().onFocusChanged { seiteHatFokus = it.hasFocus }) {
            SkinHintergrund(daten.titel?.bild)

            val listenZustand = rememberLazyListState()
            LazyColumn(
                state = listenZustand,
                modifier = Modifier.fillMaxSize().arvioDpadFocusGroup(enableFocusRestorer = false),
                contentPadding = PaddingValues(top = 72.dp, bottom = 40.dp),
            ) {
                item(key = "titelkarte") {
                    SkinTitelkarte(
                        titel = daten.titel,
                        schildText = schild,
                        aktionen = aktionen,
                        ersterKnopf = ersterKnopf,
                        modifier = Modifier
                            .padding(horizontal = randLinks)
                            .focusProperties { up = startKnopf }
                            .onFocusChanged { if (it.hasFocus) fokusKachel = null },
                    )
                    Spacer(Modifier.height(18.dp))
                }
                if (daten.laedt) {
                    item(key = "laedt") {
                        Text(
                            "Lädt …",
                            color = look.textLeise,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = randLinks, top = 12.dp),
                        )
                    }
                }
                daten.fehler?.let { fehler ->
                    item(key = "fehler") { SkinFehler(fehler, aktionen, randLinks) }
                }
                items(reihenPlan, key = { "reihe_${it.id}" }) { reihe ->
                    SkinKachelreihe(
                        reihe = reihe,
                        anzahlZeigen = look.anleitung.reihen.anzahlZeigen,
                        randLinks = randLinks,
                        aktionen = reihenAktionen,
                        modifier = Modifier.padding(bottom = 14.dp),
                        fokusZiel = fokusKachel.takeIf { fokusReihe == reihe.id },
                        zielRequester = zielRequester,
                    )
                }
            }

            SkinMenueleiste(
                profil = daten.profil,
                uhrFormat = daten.uhrFormat,
                hatUpdateHinweis = daten.hatUpdateHinweis,
                aktionen = aktionen,
                startKnopf = startKnopf,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onFocusChanged { menueHatFokus = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        // Down from the menu always goes to the first title button. The title
                        // card may be scrolled away (lazy), so bring it back before focusing.
                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
                            return@onPreviewKeyEvent false
                        }
                        scope.launch {
                            listenZustand.scrollToItem(0)
                            awaitFrame()
                            runCatching { ersterKnopf.requestFocus() }
                        }
                        true
                    },
            )
        }
    }
}

@Composable
private fun SkinFehler(fehler: String, aktionen: SkinAktionen, randLinks: androidx.compose.ui.unit.Dp) {
    val look = LocalSkinLook.current
    Column(Modifier.padding(start = randLinks, top = 12.dp)) {
        Text("Die Startseite konnte nicht geladen werden.", color = look.text, fontSize = 15.sp)
        Text(fehler, color = look.textLeise, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        SkinGlas(shape = RoundedCornerShape(12.dp)) {
            SkinKnopf(
                text = "Nochmal versuchen",
                icon = Icons.Outlined.Refresh,
                primaer = true,
                onClick = aktionen::nochmalVersuchen,
            )
        }
    }
}
