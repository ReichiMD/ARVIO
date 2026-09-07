package com.arflix.tv.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arflix.tv.R

/**
 * Brand artwork for one MDBList rating source, as shown next to the score on
 * the details page.
 *
 * [model] is handed to Coil unchanged, so it may be a drawable resource id or
 * an `android_asset` uri — the SVG decoder is registered globally in
 * `ArflixApplication`, the same way `logo_imdb_rectangle.svg` is already drawn.
 *
 * [height] and [aspectRatio] are declared per logo instead of measured, because
 * the rating chips sit in a `FlowRow`: a wordmark and a square mark need very
 * different widths to carry the same visual weight, and a fixed box keeps the
 * row from reflowing while an image is still decoding.
 */
data class RatingSourceLogo(
    val model: Any,
    val height: Dp,
    val aspectRatio: Float
)

/**
 * The official TMDB wordmark that ships with the app for the required
 * attribution in the credits dialog. Referenced rather than copied so both
 * places keep using the exact same bytes.
 */
private const val TMDB_LOGO_ASSET = "file:///android_asset/tmdb-logo.svg"

/**
 * Logo for an MDBList rating source key, or `null` when none is bundled.
 *
 * A `null` is the normal case, not an error: the caller then falls back to the
 * written-out source name. Only logos that are already part of this repository
 * are mapped here — no third-party brand artwork is added by this function, and
 * a source without one keeps its label rather than borrowing a look-alike.
 *
 * The keys are the source names MDBList reports, lowercased in
 * `MdbListRepository.normalizeRating`.
 */
fun ratingSourceLogo(source: String): RatingSourceLogo? = when (source.lowercase()) {
    "trakt" -> RatingSourceLogo(
        model = R.drawable.ic_trakt,
        height = 12.dp,
        aspectRatio = 1f
    )
    "tmdb" -> RatingSourceLogo(
        model = TMDB_LOGO_ASSET,
        height = 8.dp,
        aspectRatio = 273.42f / 35.52f
    )
    else -> null
}
