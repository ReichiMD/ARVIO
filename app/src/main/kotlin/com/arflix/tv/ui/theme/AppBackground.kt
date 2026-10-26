package com.arflix.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import com.arflix.tv.ui.skin.resolveAccentColor
import kotlin.random.Random

/** Page background behind the screens. Unknown or missing values fall back to [STANDARD]. */
enum class BackgroundStyle(val key: String) {
    STANDARD("standard"),
    ANTHRACITE("anthracite"),
    /** Faint accent-colour glow from the top start corner over fine static grain. */
    ACCENT_GLOW("glow");

    fun next(): BackgroundStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): BackgroundStyle = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/** Matte dark grey for the "Anthracite" choice. */
val AnthraciteBackground = Color(0xFF16171A)

val LocalBackgroundStyle = staticCompositionLocalOf { BackgroundStyle.STANDARD }

private const val GRAIN_SIZE = 128

/** One small tile of fine grain, built once and repeated; it also hides banding in the glow. */
private val grainTile: ImageBitmap by lazy {
    val random = Random(7)
    val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
        val v = random.nextInt(256)
        (14 shl 24) or (v shl 16) or (v shl 8) or v
    }
    android.graphics.Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/**
 * Fills the page with the chosen background. OLED black always wins.
 * [standardColor] keeps the page's own colour for the Standard choice, so Standard looks exactly as before.
 * Everything is drawn statically: nothing animates, and the brushes are rebuilt only when the size or colour changes.
 */
@Composable
fun Modifier.appBackground(standardColor: Color = BackgroundDark): Modifier {
    val style = LocalBackgroundStyle.current
    if (LocalOledBlackBackground.current) return this.background(Color.Black)
    return when (style) {
        BackgroundStyle.STANDARD -> this.background(standardColor)
        BackgroundStyle.ANTHRACITE -> this.background(AnthraciteBackground)
        BackgroundStyle.ACCENT_GLOW -> {
            val accent = resolveAccentColor(fallback = Color.White)
            // White would turn into a grey haze, so it stays fainter than the colours.
            val strength = if (accent.luminance() > 0.8f) 0.16f else 0.26f
            this.drawWithCache {
                val glow = Brush.radialGradient(
                    0f to accent.copy(alpha = strength),
                    0.35f to accent.copy(alpha = strength * 0.27f),
                    0.62f to Color.Transparent,
                    center = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width else 0f, 0f),
                    radius = size.maxDimension * 1.1f
                )
                val grain = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
                onDrawBehind {
                    drawRect(BackgroundDark)
                    drawRect(glow)
                    drawRect(grain)
                }
            }
        }
    }
}
