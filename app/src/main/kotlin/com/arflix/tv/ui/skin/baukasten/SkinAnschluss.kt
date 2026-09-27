package com.arflix.tv.ui.skin.baukasten

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.Profile
import com.arflix.tv.navigation.Screen
import com.arflix.tv.navigation.navigateToProfileSelection
import com.arflix.tv.ui.components.SettingsRow
import com.arflix.tv.ui.screens.home.HomeViewModel
import com.arflix.tv.util.LocalDeviceType

/**
 * The connection points ("Anschlussstellen") the kit offers Prodigy's code — docs/80 E12.
 * AppNavigation calls [SkinWeiche] (page switch) and registers [SkinEinstellungenRoute];
 * SettingsScreen shows [SkinEinstellungsEintrag]. Nothing else of Prodigy's code is touched.
 */

const val SKIN_EINSTELLUNGEN_ROUTE = "arvio_skin_settings"

/**
 * Page switch for Home: the skin's start page when the skin is on (TV only), otherwise
 * Prodigy's [prodigy] Home unchanged. On a phone Prodigy's Home is shown at once, without
 * waiting for the skin setting to be read.
 */
@Composable
fun SkinWeiche(skin: @Composable () -> Unit, prodigy: @Composable () -> Unit) {
    if (LocalDeviceType.current.isTouchDevice()) {
        prodigy()
        return
    }
    val einstellungen = rememberSkinEinstellungen()
    when (einstellungen?.an) {
        null -> Box(Modifier.fillMaxSize())
        true -> skin()
        false -> prodigy()
    }
}

/** Skin start page with the same jump commands AppNavigation gives Prodigy's Home. */
@Composable
fun SkinHomeRoute(
    navController: NavHostController,
    navigateTopLevel: (String) -> Unit,
    currentProfile: Profile?,
    preloadedCategories: List<Category>,
    preloadedHeroItem: MediaItem?,
    preloadedHeroLogoUrl: String?,
    preloadedLogoCache: Map<String, String>,
    onSwitchProfile: () -> Unit,
    onExitApp: () -> Unit,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val navigation = remember(navController, navigateTopLevel, onSwitchProfile, onExitApp) {
        SkinNavigation(
            details = { type, id, season, episode ->
                navController.navigate(Screen.Details.createRoute(type, id, season, episode))
            },
            sammlung = { catalogId -> navController.navigate(Screen.CollectionDetails.createRoute(catalogId)) },
            suche = { navigateTopLevel(Screen.Search.route) },
            bibliothek = { navigateTopLevel(Screen.Watchlist.route) },
            tv = { channelId, streamUrl -> navigateTopLevel(Screen.Tv.createRoute(channelId, streamUrl)) },
            player = { type, id, streamUrl, addonId, sourceName ->
                navController.navigate(
                    Screen.Player.createRoute(
                        mediaType = type,
                        mediaId = id,
                        streamUrl = streamUrl,
                        preferredAddonId = addonId,
                        preferredSourceName = sourceName,
                        isLiveStream = true
                    )
                )
            },
            einstellungen = { navigateTopLevel(Screen.Settings.route) },
            profilWechsel = {
                onSwitchProfile()
                navController.navigateToProfileSelection()
            },
            beenden = onExitApp,
        )
    }
    val einstellungen = rememberSkinEinstellungen() ?: SkinEinstellungen(an = true)
    val geladen = rememberSkinLadeErgebnis(einstellungen.adresse)
    val look = remember(geladen?.anleitung, einstellungen.effekte) {
        SkinLook(geladen?.anleitung ?: SkinBauanleitung(), einstellungen.effekte)
    }
    val (daten, aktionen) = rememberSkinStart(
        viewModel = viewModel,
        currentProfile = currentProfile,
        navigation = navigation,
        preloadedCategories = preloadedCategories,
        preloadedHeroItem = preloadedHeroItem,
        preloadedHeroLogoUrl = preloadedHeroLogoUrl,
        preloadedLogoCache = preloadedLogoCache,
    )
    SkinStartseite(daten = daten, aktionen = aktionen, look = look)

    // Prodigy's short notes ("added to watchlist") as a plain system toast.
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.toastMessage) {
        val meldung = uiState.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, meldung, Toast.LENGTH_SHORT).show()
        viewModel.dismissToast()
    }
}

/** The skin settings page; reads Home's rows (if Home is on the back stack) to list them. */
@Composable
fun SkinEinstellungenRoute(navController: NavHostController) {
    val homeEntry = remember(navController) {
        runCatching { navController.getBackStackEntry(Screen.Home.route) }.getOrNull()
    }
    val reihen: List<Pair<String, String>> = if (homeEntry != null) {
        val viewModel: HomeViewModel = hiltViewModel(homeEntry)
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        uiState.categories.distinctBy { it.id }.map { it.id to it.title }
    } else {
        emptyList()
    }
    SkinEinstellungsSeite(
        reihen = reihen,
        onBack = { navController.popBackStack() },
    )
}

/** The one row inside Prodigy's settings (Appearance) that opens the skin settings page. */
@Composable
fun SkinEinstellungsEintrag(isFocused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val einstellungen = rememberSkinEinstellungen()
    SettingsRow(
        icon = Icons.Outlined.AutoAwesome,
        title = "Skin",
        subtitle = "Eigene Startseite (Glas-Look) — Bauanleitung, Effekte",
        value = when (einstellungen?.an) {
            true -> "An"
            false -> "Aus"
            null -> ""
        },
        isFocused = isFocused,
        onClick = onClick,
        modifier = modifier,
    )
}
