package com.arflix.tv.ui.skin.baukasten

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.di.RepositoryAccessEntryPoint
import com.arflix.tv.ui.components.movieGenreNameRes
import com.arflix.tv.ui.components.tvGenreNameRes
import com.arflix.tv.ui.screens.home.HomeViewModel
import com.arflix.tv.ui.screens.home.localizedCategoryTitle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translator ("Übersetzer") between Prodigy's Home data and the building kit.
 *
 * This is the ONLY file of the kit that knows [HomeViewModel], [MediaItem] and [Category].
 * It hands the stones plain, neutral data ([SkinStartDaten]) and a set of actions
 * ([SkinAktionen]). When Prodigy changes his Home data, only this file has to follow.
 */

@Immutable
data class SkinKachel(
    /** Unique within its row; also the key the actions use. */
    val schluessel: String,
    val titel: String,
    val zeile: String,
    val bild: String?,
    val logo: String?,
    /** 0..1; 0 = no progress bar. */
    val fortschritt: Float,
    val eckeRechts: String?,
    val eckeLinks: String?,
    val gesehen: Boolean,
)

/** Row head for planning — only id and title, so the planning rules stay unit-testable. */
data class SkinReihenKopf(val id: String, val titel: String)

@Immutable
data class SkinReihe(
    val id: String,
    val titel: String,
    val kacheln: List<SkinKachel>,
)

@Immutable
data class SkinTitel(
    val schluessel: String,
    val titel: String,
    val logo: String?,
    val bild: String?,
    /** Genres, year, running time — already formatted. */
    val infos: List<String>,
    val bewertung: String?,
    val freigabe: String?,
    val beschreibung: String,
    /** "FILM", "SERIE", "TV", "SAMMLUNG". */
    val art: String,
    val fortschritt: Float,
    val restzeit: String?,
    val kannTrailer: Boolean,
    val inMerkliste: Boolean?,
)

@Immutable
data class SkinStartDaten(
    val titel: SkinTitel?,
    /** Every row Prodigy's Home currently has, in his order (the plan picks from these). */
    val reihen: List<SkinReihe>,
    val laedt: Boolean,
    val fehler: String?,
    val profil: Profile?,
    val uhrFormat: String,
    val hatUpdateHinweis: Boolean,
)

enum class SkinMenueZiel { SUCHE, START, BIBLIOTHEK, TV, EINSTELLUNGEN, PROFIL }

/** Everything a stone can ask for. Keys are [SkinKachel.schluessel]. */
interface SkinAktionen {
    fun kachelFokussiert(reiheId: String, index: Int, schluessel: String)
    fun kachelOeffnen(schluessel: String)
    fun titelAbspielen()
    fun titelDetails()
    fun titelTrailer()
    fun titelMerkliste()
    fun menue(ziel: SkinMenueZiel)
    fun beenden()
    fun nochmalVersuchen()
}

/** Jump commands handed in from the page switch in AppNavigation (Prodigy's routes). */
class SkinNavigation(
    val details: (MediaType, Int, Int?, Int?) -> Unit,
    val sammlung: (String) -> Unit,
    val suche: () -> Unit,
    val bibliothek: () -> Unit,
    val tv: (String?, String?) -> Unit,
    val player: (MediaType, Int, String, String?, String?) -> Unit,
    val einstellungen: () -> Unit,
    val profilWechsel: () -> Unit,
    val beenden: () -> Unit,
)

private fun itemSchluessel(item: MediaItem) = "${item.mediaType}_${item.id}"

@Composable
internal fun rememberSkinStart(
    viewModel: HomeViewModel,
    currentProfile: Profile?,
    navigation: SkinNavigation,
    preloadedCategories: List<Category>,
    preloadedHeroItem: MediaItem?,
    preloadedHeroLogoUrl: String?,
    preloadedLogoCache: Map<String, String>,
): Pair<SkinStartDaten, SkinAktionen> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Same start-up and resume work Prodigy's Home does, so data stays identical.
    LaunchedEffect(preloadedCategories, preloadedHeroItem, preloadedHeroLogoUrl, preloadedLogoCache) {
        if (preloadedCategories.isNotEmpty()) {
            viewModel.setPreloadedData(
                categories = preloadedCategories,
                heroItem = preloadedHeroItem,
                heroLogoUrl = preloadedHeroLogoUrl,
                logoCache = preloadedLogoCache
            )
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContinueWatchingOnly(force = false)
                viewModel.refreshWatchedBadgesOnResume()
                viewModel.refreshHomeDataIfStale()
                viewModel.pullCloudStateOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sportsRows by viewModel.sportsHomeRows.collectAsStateWithLifecycle()
    val basis = if (uiState.categories.isNotEmpty()) uiState.categories else preloadedCategories
    val kategorien = remember(basis, sportsRows) {
        viewModel.withSportsHomeRows(basis, sportsRows).distinctBy { it.id }
    }

    // Map every translated key back to Prodigy's item, so actions can find it.
    val itemsBySchluessel = remember(kategorien) {
        buildMap {
            kategorien.forEach { category -> category.items.forEach { put(itemSchluessel(it), it) } }
        }
    }
    val heroItem: MediaItem? = uiState.heroItem ?: preloadedHeroItem
        ?: kategorien.firstOrNull()?.items?.firstOrNull { !it.isPlaceholder }
    val heroLogo = uiState.heroLogoUrl ?: preloadedHeroLogoUrl

    var inMerkliste by remember { mutableStateOf<Boolean?>(null) }
    var merklisteNeuLesen by remember { mutableStateOf(0) }
    LaunchedEffect(heroItem?.id, heroItem?.mediaType, merklisteNeuLesen) {
        inMerkliste = null
        val item = heroItem ?: return@LaunchedEffect
        if (viewModel.isIptvItem(item) || viewModel.isCollectionItem(item) || viewModel.isSportsHomeItem(item)) return@LaunchedEffect
        if (merklisteNeuLesen > 0) delay(700)
        inMerkliste = runCatching { viewModel.isInWatchlist(item) }.getOrNull()
    }

    // Rows are rebuilt only when Prodigy's rows or the logo set change — not on every hero
    // change. Reading the map size here subscribes to logos arriving later.
    val reihenTitel = kategorien.map { localizedCategoryTitle(it) }
    val logoStand = viewModel.cardLogoUrls.size
    val reihen = remember(kategorien, reihenTitel, logoStand) {
        kategorien.mapIndexed { index, category ->
            SkinReihe(
                id = category.id,
                titel = reihenTitel[index],
                kacheln = kachelnFuer(context, category, viewModel.cardLogoUrls),
            )
        }
    }
    val titel = heroItem?.let { item -> titelFuer(context, viewModel, item, heroLogo, uiState.heroOverviewOverride, inMerkliste) }
    val daten = SkinStartDaten(
        titel = titel,
        reihen = reihen,
        laedt = kategorien.isEmpty() && uiState.error == null,
        fehler = uiState.error?.takeIf { kategorien.isEmpty() },
        profil = currentProfile,
        uhrFormat = uiState.clockFormat,
        hatUpdateHinweis = uiState.hasUpdateBadge,
    )

    val aktuelleItems by rememberUpdatedState(itemsBySchluessel)
    val aktuellerHero by rememberUpdatedState(heroItem)
    val aktuelleKategorien by rememberUpdatedState(kategorien)
    val aktionen = remember(viewModel, navigation) {
        object : SkinAktionen {
            private var heroJob: kotlinx.coroutines.Job? = null
            private var letzteFokusZeit = 0L

            private fun oeffnen(item: MediaItem, mitFolge: Boolean) {
                when {
                    viewModel.isSportsHomeItem(item) -> viewModel.openSportsHomeItem(
                        item = item,
                        onNavigateToSettings = navigation.einstellungen,
                        onNavigateToPlayer = navigation.player
                    )
                    viewModel.isIptvItem(item) -> navigation.tv(viewModel.getIptvChannelId(item), null)
                    viewModel.isCollectionItem(item) ->
                        navigation.sammlung(item.status?.removePrefix("collection:").orEmpty())
                    else -> {
                        viewModel.cacheItem(item)
                        viewModel.cardLogoUrls[itemSchluessel(item)]?.let { viewModel.cacheLogoUrl(item.mediaType, item.id, it) }
                        navigation.details(
                            item.mediaType,
                            item.id,
                            if (mitFolge) item.nextEpisode?.seasonNumber else null,
                            if (mitFolge) item.nextEpisode?.episodeNumber else null
                        )
                    }
                }
            }

            override fun kachelFokussiert(reiheId: String, index: Int, schluessel: String) {
                val item = aktuelleItems[schluessel] ?: return
                val reihenIndex = aktuelleKategorien.indexOfFirst { it.id == reiheId }
                viewModel.maybeLoadNextPageForCategory(reiheId, index)
                val jetzt = SystemClock.elapsedRealtime()
                val schnell = jetzt - letzteFokusZeit < 400L
                letzteFokusZeit = jetzt
                heroJob?.cancel()
                heroJob = scope.launch {
                    // While the user scrubs through a row, wait until the selector rests.
                    delay(if (schnell) 360L else 120L)
                    if (reihenIndex >= 0) viewModel.onFocusChanged(reihenIndex, index, shouldPrefetch = true)
                    viewModel.updateHeroItem(item)
                }
            }

            override fun kachelOeffnen(schluessel: String) {
                aktuelleItems[schluessel]?.let { oeffnen(it, mitFolge = true) }
            }

            override fun titelAbspielen() {
                aktuellerHero?.let { oeffnen(it, mitFolge = true) }
            }

            override fun titelDetails() {
                aktuellerHero?.let { oeffnen(it, mitFolge = false) }
            }

            override fun titelTrailer() {
                val item = aktuellerHero ?: return
                scope.launch {
                    val key = runCatching {
                        EntryPointAccessors.fromApplication(context.applicationContext, RepositoryAccessEntryPoint::class.java)
                            .mediaRepository()
                            .getTrailerKey(item.mediaType, item.id)
                    }.getOrNull()
                    trailerOeffnen(context, key)
                }
            }

            override fun titelMerkliste() {
                val item = aktuellerHero ?: return
                viewModel.toggleWatchlist(item)
                merklisteNeuLesen++
            }

            override fun menue(ziel: SkinMenueZiel) {
                when (ziel) {
                    SkinMenueZiel.SUCHE -> navigation.suche()
                    SkinMenueZiel.START -> Unit
                    SkinMenueZiel.BIBLIOTHEK -> navigation.bibliothek()
                    SkinMenueZiel.TV -> navigation.tv(null, null)
                    SkinMenueZiel.EINSTELLUNGEN -> navigation.einstellungen()
                    SkinMenueZiel.PROFIL -> navigation.profilWechsel()
                }
            }

            override fun beenden() = navigation.beenden()

            override fun nochmalVersuchen() = viewModel.refresh()
        }
    }
    return daten to aktionen
}

private fun kachelnFuer(
    context: Context,
    category: Category,
    logos: Map<String, String>,
): List<SkinKachel> {
    val gesehen = HashSet<String>()
    return category.items.filter { !it.isPlaceholder && it.title.isNotBlank() }.mapNotNull { item ->
        val key = itemSchluessel(item)
        if (!gesehen.add(key)) return@mapNotNull null
        val art = artText(context, item)
        val zeile = when {
            item.subtitle.isNotBlank() -> item.subtitle
            item.year.isNotBlank() -> "$art · ${item.year}"
            else -> art
        }
        val fortschritt = if (item.showPlaybackProgress && item.progress in 1..99) item.progress / 100f else 0f
        SkinKachel(
            schluessel = key,
            titel = item.title,
            zeile = zeile,
            bild = (item.episodeStill ?: item.backdrop ?: item.image).takeIf { it.isNotBlank() },
            logo = logos[key]?.takeIf { it.isNotBlank() },
            fortschritt = fortschritt,
            eckeRechts = item.timeRemainingLabel?.takeIf { it.isNotBlank() },
            eckeLinks = item.badge?.takeIf { it.isNotBlank() },
            gesehen = item.isWatched,
        )
    }
}

private fun artText(context: Context, item: MediaItem): String = when (item.mediaType) {
    MediaType.TV -> context.getString(com.arflix.tv.R.string.series)
    else -> context.getString(com.arflix.tv.R.string.movie)
}

private fun titelFuer(
    context: Context,
    viewModel: HomeViewModel,
    item: MediaItem,
    logo: String?,
    beschreibungErsatz: String?,
    inMerkliste: Boolean?,
): SkinTitel {
    val key = itemSchluessel(item)
    val istSammlung = viewModel.isCollectionItem(item)
    val istTv = viewModel.isIptvItem(item)
    val genres = item.genreIds.take(2).mapNotNull { id ->
        val res = if (item.mediaType == MediaType.TV) tvGenreNameRes(id) else movieGenreNameRes(id)
        res?.let { context.getString(it) }
    }
    val infos = buildList {
        if (genres.isNotEmpty()) add(genres.joinToString(" · "))
        if (item.year.isNotBlank()) add(item.year)
        if (item.duration.isNotBlank()) add(item.duration)
    }
    val bewertung = item.imdbRating.ifBlank { viewModel.cardImdbRatings[key].orEmpty() }
        .takeIf { it.isNotBlank() && it != "0.0" }
    val bild = if (istSammlung) {
        viewModel.getCollectionHeroImageUrl(item) ?: item.image
    } else {
        item.backdrop ?: item.image
    }
    val art = when {
        istSammlung -> "SAMMLUNG"
        istTv -> "TV"
        item.mediaType == MediaType.TV -> "SERIE"
        else -> "FILM"
    }
    return SkinTitel(
        schluessel = key,
        titel = item.title,
        logo = logo?.takeIf { it.isNotBlank() },
        bild = bild.takeIf { it.isNotBlank() },
        infos = infos,
        bewertung = bewertung,
        freigabe = item.contentRating?.takeIf { it.isNotBlank() },
        beschreibung = (beschreibungErsatz ?: item.overview).trim(),
        art = art,
        fortschritt = if (item.showPlaybackProgress && item.progress in 1..99) item.progress / 100f else 0f,
        restzeit = item.timeRemainingLabel?.takeIf { it.isNotBlank() },
        kannTrailer = !istSammlung && !istTv && !viewModel.isSportsHomeItem(item),
        inMerkliste = inMerkliste,
    )
}

private fun trailerOeffnen(context: Context, key: String?) {
    val url = key?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{11}$")) }
        ?.let { "https://www.youtube.com/watch?v=$it" }
    if (url == null) {
        Toast.makeText(context, "Für diesen Titel gibt es keinen Trailer.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Trailer konnte nicht geöffnet werden.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Trailer konnte nicht geöffnet werden.", Toast.LENGTH_SHORT).show()
    }
}
