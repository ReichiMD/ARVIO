package com.arflix.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Measures the hero first so the second child (the scrolling rails) cannot cover it. */
@Composable
internal fun DetailsTvHeroLayout(
    restingRowsHeight: Dp,
    expandedRowsHeight: Dp,
    expansionProgress: Float,
    modifier: Modifier = Modifier,
    liftProgress: Float = 0f,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hero = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val bounds = resolveDetailsTvHeroBounds(
            containerHeightPx = constraints.maxHeight,
            heroHeightPx = hero.height,
            restingRowsHeightPx = restingRowsHeight.roundToPx(),
            expandedRowsHeightPx = expandedRowsHeight.roundToPx(),
            expansionProgress = expansionProgress,
            gapPx = 12.dp.toPx(),
            liftProgress = liftProgress,
        )
        val rows = measurables[1].measure(
            constraints.copy(minHeight = bounds.rowsHeightPx, maxHeight = bounds.rowsHeightPx)
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            hero.placeRelativeWithLayer(0, 0) {
                translationY = bounds.heroOffsetYPx.toFloat()
                // Dim the lifted hero a little so it recedes behind the top bar it slides under.
                if (bounds.liftPx > 0) alpha = 1f - 0.3f * liftProgress.coerceIn(0f, 1f)
            }
            rows.placeRelative(0, constraints.maxHeight - rows.height)
        }
    }
}
