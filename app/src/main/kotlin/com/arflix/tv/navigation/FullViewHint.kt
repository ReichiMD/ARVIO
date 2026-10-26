package com.arflix.tv.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.R
import com.arflix.tv.ui.theme.BackgroundDark
import com.arflix.tv.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/** How long the "Full view" hint stays on screen. */
private const val FULL_VIEW_HINT_MILLIS = 4_000L

/**
 * Wraps Home while the full view from the appearance settings is open and shows a short hint that Back returns
 * to the settings. The Back itself is handled by Home, so its own rules (menus, trailers) still come first.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun FullViewHint(active: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        var showHint by remember { mutableStateOf(false) }
        LaunchedEffect(active) {
            showHint = active
            if (active) {
                delay(FULL_VIEW_HINT_MILLIS)
                showHint = false
            }
        }
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.full_view_hint),
                color = TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(BackgroundDark.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
