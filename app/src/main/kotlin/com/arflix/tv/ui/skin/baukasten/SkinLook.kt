package com.arflix.tv.ui.skin.baukasten

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The instructions turned into Compose values, plus the effects switch. */
@Immutable
data class SkinLook(
    val anleitung: SkinBauanleitung,
    val effekte: SkinEffekte,
) {
    val voll: Boolean get() = effekte == SkinEffekte.VOLL
    val akzent = Color(anleitung.farben.akzent)
    val hintergrund = Color(anleitung.farben.hintergrund)
    val text = Color(anleitung.farben.text)
    val textLeise = Color(anleitung.farben.textLeise)
    val glas = Color(anleitung.farben.glas).copy(alpha = anleitung.farben.glasDeckkraft)
    val glasFokus = Color(anleitung.farben.glas).copy(alpha = (anleitung.farben.glasDeckkraft * 2.2f).coerceAtMost(0.45f))
    val glasRand = Color(anleitung.farben.glasRand)
    val leuchtrahmen = Color(anleitung.kacheln.leuchtrahmen)
}

val LocalSkinLook = staticCompositionLocalOf { SkinLook(SkinBauanleitung(), SkinEffekte.VOLL) }
