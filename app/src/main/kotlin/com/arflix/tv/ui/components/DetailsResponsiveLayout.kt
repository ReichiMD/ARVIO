package com.arflix.tv.ui.components

import kotlin.math.ceil

internal fun resolveDetailsBackdropHeightDp(
    availableWidthDp: Float,
    availableHeightDp: Float,
): Float {
    val width = availableWidthDp.coerceAtLeast(1f)
    val height = availableHeightDp.coerceAtLeast(1f)
    return if (width > height) {
        val overlayFloor = minOf(190f, height * 0.68f)
        (height * 0.55f).coerceIn(overlayFloor, 360f)
    } else {
        val portraitFloor = minOf(400f, height * 0.65f)
        (height * 0.53f).coerceAtLeast(portraitFloor)
    }
}

internal data class DetailsTvHeroBounds(
    val rowsHeightPx: Int,
    val heroOffsetYPx: Int,
    /** How far [liftProgress] pushed the hero up to give the rows their resting height. */
    val liftPx: Int = 0,
)

internal fun resolveDetailsTvHeroBounds(
    containerHeightPx: Int,
    heroHeightPx: Int,
    restingRowsHeightPx: Int,
    expandedRowsHeightPx: Int,
    expansionProgress: Float,
    gapPx: Float,
    liftProgress: Float = 0f,
    liftRowsHeightPx: Int = restingRowsHeightPx,
    maxLiftPx: Int = Int.MAX_VALUE,
): DetailsTvHeroBounds {
    val reservedHeight = ceil(heroHeightPx + gapPx).toInt()
        .coerceIn(0, containerHeightPx)
    val fittingHeight = restingRowsHeightPx.coerceIn(0, containerHeightPx - reservedHeight)
    // A tall hero squeezes the rows below what they need; lifting gives back only what is missing.
    val missingPx = (liftRowsHeightPx.coerceIn(0, containerHeightPx) - fittingHeight)
        .coerceIn(0, maxLiftPx.coerceAtLeast(0))
    val liftPx = (missingPx * liftProgress.coerceIn(0f, 1f)).toInt()
    val restingHeight = fittingHeight + liftPx
    val expandedHeight = expandedRowsHeightPx.coerceIn(restingHeight, containerHeightPx)
    val rowsHeight = (restingHeight +
        (expandedHeight - restingHeight) * expansionProgress.coerceIn(0f, 1f)).toInt()

    // Expand the rails without sacrificing poster height or changing the gap to the hero.
    return DetailsTvHeroBounds(
        rowsHeightPx = rowsHeight,
        heroOffsetYPx = fittingHeight - rowsHeight,
        liftPx = liftPx,
    )
}
