package com.arflix.tv.ui.skin.baukasten

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arflix.tv.R
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.ProfileAvatarVisual
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.skin.arvioFocusable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The stones ("Steine") of the building kit. Each stone only knows neutral data from
 * [SkinUebersetzer] and the look from [LocalSkinLook]; none of them reads Prodigy's classes.
 * Focus drawing and D-pad grouping reuse Prodigy's general helpers ([arvioFocusable],
 * [arvioDpadFocusGroup]) as agreed in docs/80 E13.
 */


/** Focus frame for every skin stone: glow and animation only with full effects. */
@Composable
private fun Modifier.skinFokus(
    shape: Shape,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit = {},
    scale: Float = 1.04f,
    randfarbe: Color = LocalSkinLook.current.leuchtrahmen,
): Modifier {
    val look = LocalSkinLook.current
    return this.arvioFocusable(
        shape = shape,
        focusedScale = if (look.voll) scale else 1f,
        pressedScale = if (look.voll) 0.97f else 1f,
        outlineWidth = 2.dp,
        glowWidth = if (look.voll) 6.dp else 0.dp,
        glowAlpha = if (look.voll) 0.35f else 0f,
        outlineColor = randfarbe,
        animateFocus = look.voll,
        onClick = onClick,
        onFocusChanged = onFocus,
    )
}

/** Stone "Glasfläche": see-through tint with a thin edge. */
@Composable
fun SkinGlas(
    modifier: Modifier = Modifier,
    shape: Shape,
    hervorgehoben: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalSkinLook.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (hervorgehoben) look.glasFokus else look.glas, shape)
            .border(1.dp, look.glasRand, shape),
        content = content,
    )
}

/**
 * Stone "Hintergrund": the focused title's picture across the whole screen, blurred.
 * Real blur only from Android 12 (docs/80 T3); below that a tiny picture stretched up
 * gives the same soft look for almost nothing. "Sparsam" shows no picture at all.
 */
@Composable
fun SkinHintergrund(bild: String?) {
    val look = LocalSkinLook.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        look.hintergrund,
                        look.hintergrund.copy(
                            red = look.hintergrund.red * 0.6f,
                            green = look.hintergrund.green * 0.6f,
                            blue = look.hintergrund.blue * 0.6f,
                        ),
                    )
                )
            )
    ) {
        if (look.voll && !bild.isNullOrBlank()) {
            val echteUnschaerfe = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val context = LocalContext.current
            val anfrage = remember(bild, echteUnschaerfe) {
                ImageRequest.Builder(context)
                    .data(bild)
                    .size(if (echteUnschaerfe) 480 else 40, if (echteUnschaerfe) 270 else 22)
                    .crossfade(400)
                    .build()
            }
            AsyncImage(
                model = anfrage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (echteUnschaerfe) Modifier.blur(36.dp) else Modifier),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(look.hintergrund.copy(alpha = look.anleitung.farben.hintergrundAbdunklung))
            )
        }
    }
}

/** Stone "Knopf": one button of the title card. */
@Composable
fun SkinKnopf(
    text: String?,
    icon: ImageVector,
    primaer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fortschritt: Float = 0f,
) {
    val look = LocalSkinLook.current
    val shape = RoundedCornerShape(12.dp)
    var fokus by remember { mutableStateOf(false) }
    val hintergrund = when {
        primaer -> Color.White
        fokus -> look.glasFokus
        else -> look.glas
    }
    val vordergrund = if (primaer) Color(0xFF0B0B0F) else look.text
    Box(
        modifier = modifier
            .height(34.dp)
            .then(if (text == null) Modifier.width(38.dp) else Modifier)
            .skinFokus(shape, onClick, { fokus = it }, scale = 1.06f)
            .clip(shape)
            .background(hintergrund, shape)
            .border(1.dp, if (primaer) Color.Transparent else look.glasRand, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (text == null) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = text, tint = vordergrund, modifier = Modifier.size(18.dp))
            if (text != null) {
                Text(text, color = vordergrund, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (primaer && fortschritt > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fortschritt)
                    .height(3.dp)
                    .background(look.akzent)
            )
        }
    }
}

/** Stone "Schild": small label (top right of the title card, badges on tiles). */
@Composable
fun SkinSchild(text: String, modifier: Modifier = Modifier, gefuellt: Boolean = false) {
    val look = LocalSkinLook.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (gefuellt) look.akzent else Color.Black.copy(alpha = 0.45f), shape)
            .border(1.dp, if (gefuellt) Color.Transparent else look.akzent.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = if (gefuellt) Color(0xFF06231F) else look.akzent,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

/**
 * Stone "Titelkarte": the big card on top with picture, title, info line, text and buttons.
 * [ersterKnopf] lets the plan put the focus on the first button.
 */
@Composable
fun SkinTitelkarte(
    titel: SkinTitel?,
    schildText: String?,
    aktionen: SkinAktionen,
    ersterKnopf: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val look = LocalSkinLook.current
    val werte = look.anleitung.titelkarte
    val klein = werte.groesse == SkinGroesse.KLEIN
    val shape = RoundedCornerShape(werte.ecken.dp)
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (klein) 190.dp else 262.dp)
            .clip(shape)
            .background(look.hintergrund, shape)
            .then(if (werte.rahmen) Modifier.border(1.5.dp, Color(werte.randfarbe), shape) else Modifier)
    ) {
        if (titel?.bild != null) {
            val anfrage = remember(titel.bild, look.voll) {
                ImageRequest.Builder(context).data(titel.bild).crossfade(if (look.voll) 350 else 0).build()
            }
            AsyncImage(
                model = anfrage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = werte.abdunklung),
                        0.45f to Color.Black.copy(alpha = werte.abdunklung * 0.6f),
                        0.75f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = werte.abdunklung * 0.7f),
                    )
                )
        )

        if (werte.schild && schildText != null) {
            SkinSchild(schildText, Modifier.align(Alignment.TopEnd).padding(14.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.6f)
                .padding(start = 28.dp, top = if (klein) 16.dp else 24.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (titel != null) {
                    if (werte.logo && titel.logo != null) {
                        AsyncImage(
                            model = titel.logo,
                            contentDescription = titel.titel,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier
                                .height(if (klein) 42.dp else 64.dp)
                                .widthIn(max = 300.dp),
                        )
                    } else {
                        Text(
                            titel.titel,
                            color = look.text,
                            fontSize = if (klein) 24.sp else 34.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SkinInfoZeile(titel)
                    val zeilen = if (klein) werte.beschreibungZeilen.coerceAtMost(2) else werte.beschreibungZeilen
                    if (zeilen > 0 && titel.beschreibung.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            titel.beschreibung,
                            color = look.text.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = zeilen,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SkinKnopfLeiste(titel, werte.knoepfe, aktionen, ersterKnopf)
        }
    }
}

@Composable
private fun SkinInfoZeile(titel: SkinTitel) {
    val look = LocalSkinLook.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (titel.infos.isNotEmpty()) {
            Text(
                titel.infos.joinToString("  •  "),
                color = look.textLeise,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        titel.bewertung?.let {
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFF5C518))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text("IMDb $it", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
        titel.freigabe?.let {
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, look.textLeise, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text(it, color = look.text, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SkinKnopfLeiste(
    titel: SkinTitel?,
    knoepfe: List<SkinTitelKnopf>,
    aktionen: SkinAktionen,
    ersterKnopf: FocusRequester,
) {
    val sichtbar = knoepfe.filter { knopf ->
        when (knopf) {
            SkinTitelKnopf.TRAILER -> titel?.kannTrailer == true
            SkinTitelKnopf.MERKLISTE -> titel?.inMerkliste != null
            else -> true
        }
    }.ifEmpty { listOf(SkinTitelKnopf.DETAILS) }
    Row(
        modifier = Modifier.arvioDpadFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sichtbar.forEachIndexed { index, knopf ->
            val mod = if (index == 0) Modifier.focusRequester(ersterKnopf) else Modifier
            when (knopf) {
                SkinTitelKnopf.ABSPIELEN -> SkinKnopf(
                    text = if (titel?.restzeit != null || (titel?.fortschritt ?: 0f) > 0f) {
                        listOfNotNull("Weiterschauen", titel?.restzeit).joinToString(" · ")
                    } else {
                        "Abspielen"
                    },
                    icon = Icons.Filled.PlayArrow,
                    primaer = true,
                    fortschritt = titel?.fortschritt ?: 0f,
                    onClick = aktionen::titelAbspielen,
                    modifier = mod,
                )
                SkinTitelKnopf.DETAILS -> SkinKnopf(
                    text = "Details",
                    icon = Icons.Outlined.VideoLibrary,
                    primaer = sichtbar.first() == SkinTitelKnopf.DETAILS,
                    onClick = aktionen::titelDetails,
                    modifier = mod,
                )
                SkinTitelKnopf.TRAILER -> SkinKnopf(
                    text = null,
                    icon = Icons.Outlined.Slideshow,
                    primaer = false,
                    onClick = aktionen::titelTrailer,
                    modifier = mod,
                )
                SkinTitelKnopf.MERKLISTE -> SkinKnopf(
                    text = null,
                    icon = if (titel?.inMerkliste == true) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    primaer = false,
                    onClick = aktionen::titelMerkliste,
                    modifier = mod,
                )
            }
        }
    }
}

/** Stone "Kachel": one tile of a row. */
@Composable
fun SkinKachelStein(
    kachel: SkinKachel,
    onClick: () -> Unit,
    onFokus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalSkinLook.current
    val werte = look.anleitung.kacheln
    val glas = werte.stil == SkinKachelStil.GLAS
    val aussen = RoundedCornerShape(werte.ecken.dp)
    val innen = RoundedCornerShape((werte.ecken - 4).coerceAtLeast(0).dp)
    val context = LocalContext.current
    Box(
        modifier = modifier
            .width(werte.breite.dp)
            .aspectRatio(16f / 9f)
            .skinFokus(aussen, onClick, { if (it) onFokus() })
            .then(
                if (glas) {
                    Modifier
                        .clip(aussen)
                        .background(look.glas, aussen)
                        .border(1.dp, look.glasRand, aussen)
                        .padding(4.dp)
                } else {
                    Modifier
                }
            )
            .clip(if (glas) innen else aussen)
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        if (kachel.bild != null) {
            val anfrage = remember(kachel.bild, werte.breite) {
                ImageRequest.Builder(context)
                    .data(kachel.bild)
                    .size((werte.breite * 2.2f).toInt(), (werte.breite * 2.2f * 9f / 16f).toInt())
                    .crossfade(if (look.voll) 200 else 0)
                    .build()
            }
            AsyncImage(
                model = anfrage,
                contentDescription = kachel.titel,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (werte.titel) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = if (werte.fortschritt && kachel.fortschritt > 0f) 10.dp else 8.dp)
            ) {
                if (kachel.logo != null) {
                    AsyncImage(
                        model = kachel.logo,
                        contentDescription = kachel.titel,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.height(26.dp).widthIn(max = (werte.breite * 0.7f).dp),
                    )
                } else {
                    Text(
                        kachel.titel,
                        color = look.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (kachel.zeile.isNotBlank()) {
                    Text(kachel.zeile, color = look.textLeise, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (werte.fortschritt && kachel.fortschritt > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(Modifier.fillMaxWidth(kachel.fortschritt).fillMaxHeight().background(look.akzent))
            }
        }
        kachel.eckeLinks?.let { SkinSchild(it, Modifier.align(Alignment.TopStart).padding(6.dp), gefuellt = true) }
        kachel.eckeRechts?.let {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(it, color = look.text, fontSize = 8.sp, maxLines = 1) }
        }
    }
}

/** Stone "Kachelreihe": a headline and a lazily drawn row of tiles. */
@Composable
fun SkinKachelreihe(
    reihe: SkinReihe,
    anzahlZeigen: Boolean,
    randLinks: Dp,
    aktionen: SkinAktionen,
    modifier: Modifier = Modifier,
    fokusZiel: String? = null,
    zielRequester: FocusRequester? = null,
) {
    val look = LocalSkinLook.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = randLinks, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(reihe.titel, color = look.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (anzahlZeigen) {
                Text("${reihe.kacheln.size} Titel", color = look.textLeise, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        val zustand = rememberLazyListState()
        LazyRow(
            state = zustand,
            modifier = Modifier.fillMaxWidth().arvioDpadFocusGroup(),
            contentPadding = PaddingValues(start = randLinks, end = randLinks, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(reihe.kacheln, key = { _, k -> k.schluessel }) { index, kachel ->
                SkinKachelStein(
                    kachel = kachel,
                    onClick = { aktionen.kachelOeffnen(kachel.schluessel) },
                    onFokus = { aktionen.kachelFokussiert(reihe.id, index, kachel.schluessel) },
                    modifier = if (zielRequester != null && kachel.schluessel == fokusZiel) {
                        Modifier.focusRequester(zielRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * Stone "Menüleiste": greeting left, menu in the middle (glass pill or classic), date right.
 * [startKnopf] is the "Start" entry, where the back key and the first "up" land.
 */
@Composable
fun SkinMenueleiste(
    profil: Profile?,
    uhrFormat: String,
    hatUpdateHinweis: Boolean,
    aktionen: SkinAktionen,
    startKnopf: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val look = LocalSkinLook.current
    val werte = look.anleitung.menue
    val glasStil = werte.stil == SkinMenueStil.GLAS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glasStil) {
                    Modifier
                } else {
                    Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                }
            )
            .padding(horizontal = 32.dp, vertical = 14.dp)
            .arvioDpadFocusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (profil != null) {
                Box(
                    Modifier
                        .size(34.dp)
                        .skinFokus(RoundedCornerShape(10.dp), { aktionen.menue(SkinMenueZiel.PROFIL) }, scale = 1.08f)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { ProfileAvatarVisual(profile = profil, letterFontSize = 14.sp, iconPadding = 4.dp) }
                if (werte.begruessung) {
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${begruessung()},", color = look.textLeise, fontSize = 10.sp)
                        Text(profil.name, color = look.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }

        val eintraege = listOf(
            Triple(SkinMenueZiel.SUCHE, Icons.Outlined.Search, stringResource(R.string.search)),
            Triple(SkinMenueZiel.START, Icons.Outlined.Home, stringResource(R.string.home)),
            Triple(SkinMenueZiel.BIBLIOTHEK, Icons.Outlined.VideoLibrary, stringResource(R.string.nav_library)),
            Triple(SkinMenueZiel.TV, Icons.Outlined.LiveTv, stringResource(R.string.topbar_tv)),
        )
        val pillShape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .then(
                    if (glasStil) {
                        Modifier.clip(pillShape).background(look.glas, pillShape).border(1.dp, look.glasRand, pillShape)
                    } else {
                        Modifier
                    }
                )
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            eintraege.forEach { (ziel, icon, label) ->
                SkinMenueEintrag(
                    icon = icon,
                    text = label,
                    gewaehlt = ziel == SkinMenueZiel.START,
                    onClick = { aktionen.menue(ziel) },
                    modifier = if (ziel == SkinMenueZiel.START) Modifier.focusRequester(startKnopf) else Modifier,
                )
            }
            SkinMenueEintrag(
                icon = Icons.Outlined.Settings,
                text = null,
                gewaehlt = false,
                punkt = hatUpdateHinweis,
                onClick = { aktionen.menue(SkinMenueZiel.EINSTELLUNGEN) },
            )
        }

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            if (werte.datum) {
                val jetzt = rememberJetzt()
                Text(
                    SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()).format(jetzt),
                    color = look.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    SimpleDateFormat(if (uhrFormat == "12h") "h:mm a" else "HH:mm", Locale.getDefault()).format(jetzt) +
                        if (uhrFormat == "12h") "" else " Uhr",
                    color = look.textLeise,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun SkinMenueEintrag(
    icon: ImageVector,
    text: String?,
    gewaehlt: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    punkt: Boolean = false,
) {
    val look = LocalSkinLook.current
    val shape = RoundedCornerShape(12.dp)
    var fokus by remember { mutableStateOf(false) }
    val farbe = when {
        fokus -> look.text
        gewaehlt -> look.akzent
        else -> look.text.copy(alpha = 0.72f)
    }
    Box(
        modifier = modifier
            .heightIn(min = 30.dp)
            .skinFokus(shape, onClick, { fokus = it }, scale = 1.05f, randfarbe = look.text.copy(alpha = 0.8f))
            .clip(shape)
            .background(
                when {
                    fokus -> look.glasFokus
                    gewaehlt -> look.akzent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
                shape,
            )
            .padding(horizontal = if (text == null) 8.dp else 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = text, tint = farbe, modifier = Modifier.size(15.dp))
            if (text != null) Text(text, color = farbe, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (punkt) {
            Box(Modifier.align(Alignment.TopEnd).size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
        }
    }
}

private fun begruessung(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

@Composable
private fun rememberJetzt(): Date {
    var jetzt by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            val bisZurNaechstenMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(bisZurNaechstenMinute)
            jetzt = Date()
        }
    }
    return jetzt
}
