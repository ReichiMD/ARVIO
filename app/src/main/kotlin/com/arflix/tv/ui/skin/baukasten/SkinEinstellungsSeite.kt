package com.arflix.tv.ui.skin.baukasten

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.arflix.tv.ui.components.TextInputModal
import com.arflix.tv.ui.skin.arvioFocusable
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/**
 * The skin's own settings page (docs/80 E12): opened from the single "Skin" row in Prodigy's
 * settings, everything else lives here. Every text is German; a broken instructions file is
 * explained value by value, because the user edits it on a phone.
 */
@Composable
fun SkinEinstellungsSeite(
    reihen: List<Pair<String, String>>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val einstellungen = rememberSkinEinstellungen() ?: SkinEinstellungen()
    val geladen = rememberSkinLadeErgebnis(einstellungen.adresse)
    val look = SkinLook(geladen?.anleitung ?: SkinBauanleitung(), einstellungen.effekte)
    var eingabeOffen by remember { mutableStateOf(false) }
    var laedt by remember { mutableStateOf(false) }
    val ersteZeile = remember { FocusRequester() }

    BackHandler(enabled = !eingabeOffen) { onBack() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { ersteZeile.requestFocus() }
    }

    fun aendern(block: (SkinEinstellungen) -> SkinEinstellungen) {
        scope.launch { context.skinEinstellungAendern(block) }
    }

    fun neuLaden(adresse: String) {
        laedt = true
        scope.launch {
            try {
                SkinAnleitungen.sicherstellen(context, adresse, erzwingen = true)
            } finally {
                laedt = false
            }
        }
    }

    CompositionLocalProvider(LocalSkinLook provides look) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1118))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "kopf") {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text("Skin", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Eigene Startseite im Glas-Look. Nur am Fernseher — am Handy bleibt Prodigys Startseite.",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                        )
                    }
                }
                item(key = "an") {
                    SkinZeile(
                        titel = "Skin benutzen",
                        text = "Aus = Prodigys normale Startseite.",
                        wert = if (einstellungen.an) "An" else "Aus",
                        onClick = { aendern { it.copy(an = !it.an) } },
                        modifier = Modifier.focusRequester(ersteZeile),
                    )
                }
                item(key = "effekte") {
                    SkinZeile(
                        titel = "Effekte",
                        text = "Sparsam = keine Unschärfe, kein Leuchten, keine Animationen — für schwache Geräte.",
                        wert = if (einstellungen.effekte == SkinEffekte.VOLL) "Voll" else "Sparsam",
                        onClick = {
                            aendern {
                                it.copy(effekte = if (it.effekte == SkinEffekte.VOLL) SkinEffekte.SPARSAM else SkinEffekte.VOLL)
                            }
                        },
                    )
                }
                item(key = "adresse") {
                    SkinZeile(
                        titel = "Adresse der Bauanleitung",
                        text = einstellungen.adresse.ifBlank { "leer — die eingebaute Standard-Anleitung wird benutzt" },
                        wert = "Ändern",
                        onClick = { eingabeOffen = true },
                    )
                }
                item(key = "neu") {
                    SkinZeile(
                        titel = "Anleitung neu laden",
                        text = "Holt die aktuelle Fassung aus deinem Repository (braucht Internet).",
                        wert = if (laedt) "lädt …" else "",
                        onClick = { if (!laedt) neuLaden(einstellungen.adresse) },
                    )
                }
                if (einstellungen.adresse.isNotBlank()) {
                    item(key = "loeschen") {
                        SkinZeile(
                            titel = "Adresse löschen",
                            text = "Zurück zur eingebauten Standard-Anleitung.",
                            wert = "",
                            onClick = { aendern { it.copy(adresse = "") } },
                        )
                    }
                }
                item(key = "stand") {
                    SkinAbschnitt("Welche Anleitung gerade gilt")
                }
                item(key = "quelle") {
                    val quelle = when (geladen?.quelle) {
                        SkinQuelle.NETZ -> "frisch aus deinem Repository geladen"
                        SkinQuelle.GESPEICHERT -> "die auf dem Gerät gespeicherte Fassung"
                        SkinQuelle.EINGEBAUT, null -> "die eingebaute Standard-Anleitung"
                    }
                    SkinInfo("„${look.anleitung.name}“ — $quelle", wichtig = false)
                }
                geladen?.meldung?.let { meldung ->
                    item(key = "meldung") { SkinInfo(meldung, wichtig = true) }
                }
                val hinweise = geladen?.hinweise.orEmpty()
                if (hinweise.isNotEmpty()) {
                    item(key = "hinweise_kopf") {
                        SkinAbschnitt("${hinweise.size} Hinweis(e) zur Bauanleitung — der Rest gilt")
                    }
                    items(hinweise.size, key = { "hinweis_$it" }) { index ->
                        SkinInfo(hinweise[index], wichtig = true)
                    }
                }
                if (reihen.isNotEmpty()) {
                    item(key = "reihen_kopf") {
                        SkinAbschnitt("Reihen, die es gerade gibt — für „reihenfolge“ und „ausblenden“")
                    }
                    items(reihen, key = { "reihe_${it.first}" }) { (id, titel) ->
                        SkinInfo("$titel   →   \"$id\"", wichtig = false)
                    }
                }
            }
        }

        TextInputModal(
            isVisible = eingabeOffen,
            title = "Adresse der Bauanleitung",
            hint = "https://github.com/<name>/<repo>/blob/main/skin.json",
            initialValue = einstellungen.adresse,
            onConfirm = { neu ->
                eingabeOffen = false
                aendern { it.copy(adresse = neu.trim()) }
                neuLaden(neu.trim())
            },
            onCancel = { eingabeOffen = false },
        )
    }
}

@Composable
private fun SkinZeile(
    titel: String,
    text: String,
    wert: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalSkinLook.current
    val shape = RoundedCornerShape(14.dp)
    var fokus by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .arvioFocusable(
                shape = shape,
                focusedScale = 1.01f,
                pressedScale = 0.99f,
                outlineWidth = 2.dp,
                glowWidth = 0.dp,
                glowAlpha = 0f,
                outlineColor = look.akzent,
                onClick = onClick,
                onFocusChanged = { fokus = it },
            )
            .background(if (fokus) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        if (wert.isNotBlank()) {
            Text(wert, color = look.akzent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SkinAbschnitt(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp),
    )
}

/** A readable line that can take focus, so the D-pad can scroll through long lists. */
@Composable
private fun SkinInfo(text: String, wichtig: Boolean) {
    var fokus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .widthIn(max = 860.dp)
            .fillMaxWidth()
            .onFocusChanged { fokus = it.isFocused }
            .focusable()
            .background(if (fokus) Color.White.copy(alpha = 0.10f) else Color.Transparent, shape)
            .border(1.dp, if (wichtig) Color(0x66FFB74D) else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (wichtig) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
    }
}
