# ARVIO vs. Nuvio — structural performance analysis

Static source analysis, no device measurements. Every claim below is anchored to a file and line in
the commits listed under *Scope*. Statements that go beyond what the code shows are marked
**[Assumption]** with a confidence level.

## Executive summary

1. **Home/catalog loading is where ARVIO is structurally slowest, on TV and phone.** Every non-TMDB catalog item (add-on, Trakt, MDBList) is turned into up to four extra requests (TMDB `/find`, TMDB details, TMDB `/external_ids`, full Cinemeta `/meta` for the IMDb rating), and a row is only published once all of them have finished. Nuvio renders the add-on's `metas` directly: one request per row. Confidence: high (code); size of the effect: medium to high (depends on the network, not measured).
2. **All TMDB API traffic and all TMDB image traffic run through OkHttp's default `Dispatcher` (5 concurrent requests per host).** The hydration from point 1, logos, hero details and the Details screen all queue FIFO on the same 5 slots per host. Nuvio's image client allows 16 per host. Confidence: high.
3. **ARVIO deliberately holds the home network refresh back:** 800 ms before the first request, and at least about 4.7 s when a disk snapshot is shown. On TV, add-on rows then load 2 at a time. Nuvio loads 4 eager rows 3 at a time, with no fixed delay. Confidence: high.
4. **Artwork is oversized:** the default landscape cards and the hero load TMDB `original` backdrops, and posters use `w780`. Nuvio uses `w1280` and `w500`. Confidence: high (code); byte sizes are an assumption.
5. **TV D-pad navigation does more work per key press:** the hero (state, `original` backdrop, details request) follows focus after 0–80 ms and recomposes the Home root, and trailer autoplay is on by default. Nuvio waits 450 ms for focus to settle, freezes the backdrop during fast navigation, and leaves trailer autoplay off by default. Confidence: high (code); frame-time effect not measured.
6. **Not slower, and partly faster:** fetching streams (parallel, progressive, prefetched on Details, tight timeouts) and starting playback (350–550 ms start buffer against Nuvio's 3 s). If "Nuvio is faster" is about these two steps, the code does not support it.
7. **The toolchain is older:** Compose BOM 2024.06 with tv-foundation `1.0.0-alpha11` and Coil 2.5, against BOM 2026.05, tv-foundation `rc01` and Coil 3.3. Upgrading is the largest structural lever, but it is also the most expensive one.

## 1. Scope and methodology

### Repositories and commits

| App | Repository | Commit | Stack |
|---|---|---|---|
| ARVIO (TV + phone/tablet, one APK) | `ProdigyV21/ARVIO` (= fork `ReichiMD/ARVIO` `main`) | `87cc7187f4583b0f8fabdab73d62acfe5c32b8b6` (2026‑09‑23) | Kotlin, Jetpack Compose (BOM 2024.06.00) + `androidx.tv` alpha11, Hilt, Retrofit+Gson, OkHttp (4 on Play, 5 on sideload), Coil 2.5, Media3 1.9 |
| Nuvio TV | `tapframe/NuvioTV` (README points to `NuvioMedia/NuvioTV`) | `336896345125c38ed119410f9f85938e4e8f943a` (2026‑09‑23) | Kotlin, Jetpack Compose (BOM 2026.05.01) + `androidx.tv` rc01, Hilt, Retrofit+Moshi codegen, OkHttp 4.12, Coil 3.3, Media3 1.8 (vendored engine) + optional libmpv |
| Nuvio Mobile | `tapframe/NuvioStreaming` (README: `NuvioMedia/NuvioMobile`, branch `cmp-rewrite`) | `b88fef2e655609443a9949e578c5267110be5fac` (2026‑09‑23) | Kotlin Multiplatform + Compose Multiplatform 1.12 (Android + iOS), Ktor 3.4, kotlinx.serialization, Coil 3.6 |

### What is comparable

- **TV:** ARVIO's TV mode is compared with Nuvio TV. Both are native Kotlin/Compose on Android TV, with the same class of libraries, so the comparison is 1:1.
- **Mobile:** ARVIO's phone mode (the same APK, `DeviceType.PHONE`, `MainActivity.kt:228`, `HomeViewModel.kt:2558`) is compared with Nuvio Mobile (CMP). The data pipelines are comparable; toolkit-level rendering is not (Compose Multiplatform vs Jetpack Compose on Android share the runtime, but the versions differ widely). iOS is out of scope, because ARVIO has no iOS app.
- **[Assumption, medium]** Community comparisons may refer to the earlier React-Native Nuvio Mobile. The README names a `cmp-rewrite` branch (`NuvioStreaming/README.md:19`), so the current mobile code base is the KMP rewrite. Nothing below applies to the RN version.

### Method

Targeted reading of the start, home, catalog, image, stream, player and cache paths. No build, no device, no network capture. Any latency figure in this document is an estimate derived from request counts and is labelled as such.

### Not investigated

Search, Library/Watchlist, Settings, IPTV/Live-TV guide (ARVIO has dedicated docs on it: `docs/iptv-scroll-performance-2026-09-08.md`), sync/Trakt background work beyond its start-up effect, the local plugin/.cs3 runtime, torrent/debrid paths, iOS, memory/leak behaviour, APK size, R8 rules.

## 2. Findings by area

### 2.1 Cold start to first usable UI

**ARVIO (TV + phone)**

- `ArflixApplication` field-injects 8 singletons (`ArflixApplication.kt:60‑75`). Hilt performs that injection inside `super.onCreate()` (the comment at `ArflixApplication.kt:78‑86` confirms this) on the main thread. `CloudSyncRepository` alone depends on 16 repositories, among them `IptvRepository`, `StreamRepository`, `TraktRepository` and `HomeServerRepository` (`CloudSyncRepository.kt` constructor). Most of the data layer is therefore class-loaded and constructed before the first Activity frame, including `StreamRepository`'s runtime objects (`StreamRepository.kt:330‑402`).
- `MainActivity` correctly declares its repositories as `dagger.Lazy`, but then calls `.get()` on 8 of them during the first composition (`MainActivity.kt:383‑391`), which cancels the laziness. By then most of them already exist because of the Application injection.
- `StartupViewModel` has no remaining function: it sets `isReady` immediately (`StartupViewModel.kt:63‑90`), and its `preloaded*` outputs are always empty. It is harmless but misleading.
- Positive: DNS/UA preferences are read off the main thread (`ArflixApplication.kt:98‑115`). Sync starts after 2.5–5.5 s (`ArflixApplication.kt:154‑162`). IPTV warm-up and the Trakt worker run after the first frame (`MainActivity.kt:418‑440`). A baseline profile exists and is generated from a macrobenchmark journey (`app/src/main/baseline-prof.txt`, 33 k lines; `benchmark/.../BaselineProfileGenerator.kt`).

**Nuvio TV:** `NuvioApplication` injects 5 services (`NuvioApplication.kt:43‑47`) and `MainActivity` injects about 20 fields (`MainActivity.kt:257‑311`). That is just as eager. Nuvio additionally generates a baseline profile (99 k-line generated file plus a 37 k-line manual file) and filters it to its own package (`app/build.gradle.kts:360‑367`).

**Assessment:** There is no structural cold-start advantage for Nuvio that can be read from code. ARVIO's eager graph is still a cost of its own that can be removed (§4, R9). **[Assumption, medium]** In a release build with the profile installed, the cost is roughly tens of ms to about 150 ms on low-end TV SoCs. A trace is needed before anyone acts on it.

### 2.2 Loading Home and catalogs

#### 2.2.1 Per-item TMDB hydration (TV + phone) — the main finding

`MediaRepository.loadCustomCatalogPage` (`MediaRepository.kt:1877‑1946`) handles add-on, Trakt and MDBList rows:

1. **Add-on rows:** the add-on's `metas` are fetched, and the add-on's artwork and metadata are then thrown away. Only IDs are kept (`parseAddonPageRefs`, `MediaRepository.kt:2691‑2765`). Each `tt…` ID is resolved through TMDB `/find` (`resolveImdbToTmdbRef` → `tmdbApi.findByExternalId`, `MediaRepository.kt:2804`), with 4 in parallel per row. IDs without an IMDb or TMDB reference trigger an add-on `/meta` call each (2 in parallel) or a TMDB title search (2 in parallel).
2. Each resolved ID then calls `getMovieDetails`/`getTvDetails` (semaphore 6 per row, `MediaRepository.kt:1921‑1933`).
3. `getMovieDetails` itself makes 3 more calls: TMDB details, TMDB `/external_ids`, and `getImdbRating` (`MediaRepository.kt:3025‑3052`). The last one downloads the **full Cinemeta `/meta/{type}/{tt}.json`** just to read the IMDb rating (`MediaRepository.kt:426‑441`, `469‑485`). For series, that response contains every episode. **[Assumption, medium: from ~100 KB up to about 1 MB for long-running shows.]** This IMDb ID is the same `tt` the add-on delivered in step 1: `tt → /find → tmdbId → /external_ids → tt` is a round trip.
4. The row is published only after `jobs.mapNotNull { it.await() }` (`MediaRepository.kt:1934`), so the slowest item decides when the whole row appears.

All these caches are process-memory only with a 5-minute TTL (`MediaRepository.kt:137`, `157‑174`). `detailsCache` is an unbounded `mutableMapOf` (`MediaRepository.kt:158`). After process death, everything is fetched again, unless OkHttp's HTTP cache happens to hit (see 2.6).

**Request budget per add-on row of 10 items** (TV landscape default `initialCategoryItemCap = 10`, `HomeViewModel.kt:1330`): about 10 + 10 + 10 TMDB requests and 10 Cinemeta requests. With 8 add-on rows, that is about 240 TMDB calls on a cold process. **[Assumption, medium: at 5 concurrent per host (2.2.2) and ~150 ms RTT, the TMDB part alone takes ≥ 7 s of wall time.]**

**Nuvio TV:** `CatalogRepositoryImpl.getCatalog` makes exactly one request and maps `metas` straight to domain items (`CatalogRepositoryImpl.kt:32‑75`, request at `:52`, mapping at `:59`). TMDB enrichment is focus-driven only: it runs after a 220 ms debounce and is held in a 64-entry cache (`HomeViewModel.kt:98‑100`, `173‑174`).
**Nuvio Mobile:** `HomeRepository.toSection` likewise makes one request (up to 18 items) and uses the result directly (`HomeRepository.kt:232‑280`, `446‑447`).

This is the single largest reason why a "Home fills faster in Nuvio" impression would be well-founded.

#### 2.2.2 Shared dispatcher with 5 requests per host (TV + phone)

- `OkHttpProvider.buildAppClient` sets no `Dispatcher` (`OkHttpProvider.kt:255‑299`), so OkHttp's defaults apply: `maxRequests = 64`, `maxRequestsPerHost = 5`. `provideTmdbApi` uses `okHttpClient.newBuilder()` (`AppModule.kt:39‑73`), which shares the same dispatcher. Retrofit `suspend` calls go through `enqueue`, so every call to `api.themoviedb.org` — catalog hydration, logos (`getLogoUrl`, `MediaRepository.kt:3304‑3315`), hero details, and the Details screen the user just opened — waits FIFO in `readyAsyncCalls` behind the calls already queued.
- `coilClient` also has no dispatcher (`OkHttpProvider.kt:462‑480`). **[Assumption, high: Coil 2.x `HttpUriFetcher` uses `Call.await()`, which is enqueue-based, off the main thread.]** So at most 5 image downloads run in parallel from `image.tmdb.org`, and each of them may be an `original` backdrop (2.3.1).
- **Nuvio TV** image client: `maxRequests = 32`, `maxRequestsPerHost = 16`, connect 4 s, read 5 s, call 12 s, and one retry with shorter timeouts (`NuvioApplication.kt:92‑115`). Its API client also uses `IPv4FirstDns` (`core/di/NetworkModule.kt:105`, `core/network/IPv4FirstDns.kt:12`). **[Assumption, low–medium: IPv4-first avoids broken-IPv6 connect stalls on OkHttp 4. ARVIO's sideload flavour ships OkHttp 5 (`sideload/.../NetworkPlatform.kt:7`), which does fast fallback by default, so the gap would only affect the Play flavour.]**

#### 2.2.3 Scheduling and concurrency of the home refresh (mainly TV)

| Step | ARVIO | Nuvio TV |
|---|---|---|
| First network load after the VM is created | fixed `delay(800 ms)` (low-RAM 1 s) → `loadHomeData()` (`HomeViewModel.kt:1398‑1411`) plus a further `delay(50)` (`:2464‑2466`) | immediate |
| When a disk snapshot is shown | waits until VM age ≥ `startupSettleMs` (4 s, low-RAM 5 s) + 700 ms (`HomeViewModel.kt:1290‑1291`, `1403‑1409`) | n/a (no snapshot, relies on the HTTP cache) |
| Serial work before any row request | `removeCustomAddonsByUrl` → `installedAddons.first()` → `syncAddonCatalogs` → `syncHomeServerCatalogs(getCatalogCandidates())` → `ensurePreinstalledDefaults` → `getCatalogs()` (`HomeViewModel.kt:2514‑2540`) | addon list read, then placeholders (`HomeViewModelCatalogPipeline.kt:290‑345`) |
| Add-on/custom rows | Semaphore **2** on TV (low-RAM 1) (`HomeViewModel.kt:2600`), each row internally N+1 (2.2.1) | Semaphore **3**, 4 eager rows, the rest lazy with placeholders (`HomeViewModel.kt:94`, `307`; `HomeViewModelCatalogPipeline.kt:298‑345`) |
| MDBList rows | 1 eager (`HomeViewModel.kt:1332`), the rest in chunks of 3, sequentially (`:2742‑2770`) | n/a |
| Built-in TMDB rows | 3 rows × 2 pages, page 2 always fetched because a page holds 20 < 40 items (`MediaRepository.kt:1712‑1717`); retries after 1.5 s and 3 s if all come back empty (`:1693‑1701`) | n/a |
| Early publication | TMDB rows are published first (`HomeViewModel.kt:2664‑2694`), add-on rows as each one completes | first row immediately, then debounced; 800 ms safety flush (`HomeViewModelCatalogPipeline.kt:348‑356`) |
| Phone | Semaphore 6 (low-RAM 3) (`HomeViewModel.kt:3393`) | batches of 4, published every 2 batches (`HomeRepository.kt:92‑121`, `446‑448`); batch-sequential, so the slowest catalog blocks the next batch |

ARVIO's disk snapshot of Home rows (`HomeViewModel.kt:1887‑1938`, 16 items per row, `:1347`) is a real advantage for perceived start-up time. Nuvio has nothing equivalent. The price is the deliberately late refresh.

#### 2.2.4 Main-thread disk writes on phone

`loadMobileHomeDataProgressive` calls `persistCategoriesCache(_uiState.value.categories)` inside `withContext(Dispatchers.Main.immediate)` once **per arriving row** (`HomeViewModel.kt:3381`, `3401‑3407`). `persistCategoriesCache` serializes every row with Gson and writes the file synchronously (`HomeViewModel.kt:1140‑1163`; the size cap of 12 MB at `:1350` shows how large the file may get). The TV path does the same on `Dispatchers.IO` (`HomeViewModel.kt:3095‑3097`). Confidence: high. **[Assumption, medium: this causes visible jank on phones exactly while Home is filling.]**

### 2.3 Scrolling, navigation, images, focus

#### 2.3.1 Image sizes and the image pipeline (TV + phone)

- `Constants.kt:63‑70`: `IMAGE_BASE = w780`, `BACKDROP_BASE_LARGE = original`. `TmdbMediaItem.toMediaItem` sets `backdrop = original` (`MediaRepository.kt:4086`), and so do Trakt, Watchlist and Player (see `grep BACKDROP_BASE_LARGE`: 14 call sites).
- The default card layout is `LANDSCAPE` (`CardLayoutMode.kt:97‑103`), and landscape cards use `item.backdrop` (`MediaCard.kt:130‑136`). So every default TV/phone card downloads an `original` backdrop. The hero does too (`HomeScreen.kt:589‑686`, request size = screen size, `:824‑828`).
- Decoding is downsampled (`.size(widthPx, heightPx)`, `MediaCard.kt:162‑171`), so the bitmap memory stays bounded. The **download and the disk-cache footprint** stay at original size, however (TV disk cache 128 MB, `ArflixApplication.kt:242‑247`). **[Assumption, medium: `original` backdrops are typically 0.5–3 MB, against ~60–150 KB for `w780`. That puts ~50–150 original backdrops in the TV disk cache, so it churns within a few sessions.]**
- Memory cache: fixed 48 MB on TV, 32 MB on low-RAM TV, 64 MB elsewhere (`ArflixApplication.kt:233‑239`). The comment gives a sound reason (native/GPU pressure on 2 GB TVs). `respectCacheHeaders(false)`, RGB_565, hardware bitmaps.
- **Nuvio TV:** TMDB backdrops at `w1280`, posters and logos at `w500` (`TmdbMetadataService.kt:293‑303`, `TmdbService.kt:303‑304`). Coil 3 with a 15–25 % memory cache depending on total RAM, a 200 MB disk cache, `StaleWhileRevalidateCacheStrategy`, `bitmapFactoryMaxParallelism(4)` and `allowHardware(false)` (`NuvioApplication.kt:119‑170`). Nuvio Mobile uses Coil 3 defaults with a Cache-Control strategy and crossfade (`androidMain/.../NuvioApplication.kt:24‑44`).

#### 2.3.2 Hero coupling on TV D-pad navigation

- A focus change goes to `HomeViewModel` → `performHeroUpdate` (`HomeViewModel.kt:4540‑4638`). The debounce is 0 ms when the logo is cached, 80 ms otherwise, and only longer during fast scroll sequences (`HERO_DEBOUNCE_MS = 80`, `:1289`). Each update copies `HomeUiState` (`:4629‑4637`). `HomeScreen` collects the whole `uiState` at its root (`HomeScreen.kt:730`), so the Home root scope is invalidated on every hero change. `HomeUiState` also carries the frequently changing `cardLogoUrls` map (`HomeViewModel.kt:101`), whose publication had to be throttled for exactly this reason (`HomeViewModel.kt:1378‑1380`).
- Each settled hero triggers a new `original` backdrop request with a 420 ms crossfade (`HomeScreen.kt:631‑643`), `scheduleHeroDetailsFetch` (TMDB), and — with trailer autoplay **on by default** (`SettingsViewModel.kt:644`) — a separate hero `ExoPlayer` with its own `OkHttpClient` and connection pool (`HomeScreen.kt:523‑557`, created at `:1081`).
- `TvHomeRowsLayer` reads `focusState.currentRowIndex` and `currentItemIndex` directly in composition (`HomeScreen.kt:3268`, `3380`, `3423‑3426`). Every vertical move recomposes the item lambdas of all rendered rows; every horizontal move recomposes the focused row and its changed cards.
- **Nuvio TV:** the hero waits for a 450 ms focus settle (`ModernHomeModels.kt:30`, `ModernHomeContent.kt:552‑575`). The backdrop is frozen during fast navigation and scrolling (`ModernHomeHero.kt:140‑156`). The hero state lives in the composable, is read through lambdas and `derivedStateOf`, and does not go through a ViewModel state copy. Focused-card trailers are **off** by default (`LayoutPreferenceDataStore.kt:290`). Home UI state classes are declared stable explicitly (`compose_stability_config.conf`: `HomeUiState`, `ModernRow`, …).
- **Confidence:** code paths high. Frame-time effect not measured. **[Assumption, medium: this per-key work is the main cause of a "Nuvio feels snappier on TV" impression when both apps are warm.]**

#### 2.3.3 Toolkit versions

| | ARVIO | Nuvio TV |
|---|---|---|
| Compose BOM | 2024.06.00 (`app/build.gradle.kts:322`) | 2026.05.01 (`gradle/libs.versions.toml:7`) |
| tv-foundation / tv-material | 1.0.0-alpha11 / 1.0.0 (`app/build.gradle.kts:333‑334`) | 1.0.0-rc01 / 1.1.0-rc01 (`libs.versions.toml:8‑9`) |
| TV lazy lists | `TvLazyRow`/`TvLazyColumn` still used on Details, StreamSelector, PersonModal and skeletons (e.g. `DetailsScreen.kt:2553`, `StreamSelector.kt:894`); Home uses `LazyColumn`/`LazyRow` with manual index-driven scrolling (`HomeScreen.kt:3316‑3350`) | foundation `LazyRow`/`LazyColumn` with custom `BringIntoViewSpec` (83 uses) and `focusRestorer` (76 uses) |
| Image loader | Coil 2.5.0 (`app/build.gradle.kts:394`) | Coil 3.3.0 |
| JSON | Gson reflection + `org.json` (`app/build.gradle.kts:295`, `376`) | Moshi with KSP codegen (230 `@JsonClass(generateAdapter = true)`) |
| Compose compiler | stability config (`app/build.gradle.kts:261‑264`, `app/compose_stability_config.conf`), no metrics/reports | stability config + metrics/reports enabled (`app/build.gradle.kts:343‑348`) |

**[Assumption, medium]** Compose releases after 1.6 contain substantial focus, `Modifier.Node` and lazy-list prefetch work that ARVIO does not get. The pin comes from tv-foundation alpha11 (comment at `app/build.gradle.kts:320‑322`). This is a structural item (§5).

### 2.4 Stream/link resolution

| | ARVIO | Nuvio TV | Nuvio Mobile |
|---|---|---|---|
| Fan-out | parallel `launch` per add-on inside `callbackFlow` (`StreamRepository.kt:2445‑2478`); an optional sequential mode with a 3.5 s timeout per add-on (`:2384‑2443`) | parallel `launch` per add-on + `Channel.UNLIMITED` (`StreamRepositoryImpl.kt:184‑245`) | parallel `launch` per add-on and per plugin scraper (`StreamsRepository.kt:417‑460`) |
| Progressive UI | yes, one emission per completed add-on (`StreamRepository.kt:2346‑2381`; consumer `DetailsViewModel.kt:2020‑2060`) | yes, one emission per result (`StreamRepositoryImpl.kt:285‑292`) | yes |
| Per-add-on timeout | 6 s standard, 20 s aggregators, 30 s "extended" (`StreamRepository.kt:1610‑1625`, `1774‑1778`) | none at app level; OkHttp read timeout 60 s (`NetworkModule.kt:108‑109`) | autoplay window 1–30 s, a 60 s fallback (`StreamsRepository.kt:386‑410`) |
| When it starts | **prefetch when Details opens** (`DetailsViewModel.kt:737`, `1663‑1740`) + connection prewarm of the top 3 (`:1760‑1778`) | only when the stream screen or player opens (`StreamScreenViewModel.kt:656`) | stream screen; plus the last-link cache (`StreamLinkCacheRepository.kt`) |
| Result cache | memory + disk, TTL 15 s (empty) / 30 s (tokenized HTTP) / 90 s (HTTP), stale grace (`StreamRepository.kt:1636‑1680`, `2262‑2302`) | session cache (`StreamSearchSessionCache.kt:36‑40`) | link cache |

ARVIO is **equal or better** in this area. Two ARVIO-specific issues remain:

- **Stream prefetch starts late.** `externalIdsDeferred` is launched at t0 (`DetailsViewModel.kt:508`), but it is only consumed after `itemDeferred` (`:555`), the anime structure (`:571`), the episodes (`:611`) and `traktRepository.initializeWatchedCache()` (`:660‑663`, called a second time for TV at `:529‑531`). Stream prefetch therefore starts at the latest of all these points instead of at `externalIds`. For add-on-sourced items, the IMDb ID was already known before Details opened (2.2.1).
- **Resolution is detached from its collector.** `resolve*StreamsProgressive` runs its work in `repositoryScope.launch` (`StreamRepository.kt:2254`, `2854`), with an empty `awaitClose { }` (`:2490`, `:3116`). Cancelling the collector (the user leaves Details) does not cancel the add-on requests, so they keep taking sockets and dispatcher slots. Confidence: high. Effect: low to medium.

### 2.5 Playback start

- **ARVIO:** `bufferForPlaybackMs` is 350 ms on phone and 450–550 ms on TV. Min buffer 20–40 s, max 70–170 s, target 80–384 MB, all derived from the heap class (`PlayerScreen.kt:6616‑6665`, applied at `:1138‑1148`). Async MediaCodec queueing and renderer prewarming are disabled for firmware-hang reasons (`PlayerScreen.kt:1165‑1170`). FFmpeg extension renderers `ON`/`PREFER`. The player is built in composition when the screen opens (`remember`, `PlayerScreen.kt:1137`).
- **Nuvio TV:** the performance helper defaults to `bufferForPlaybackMs = 3000` and `…AfterRebuffer = 3000` (`NuvioExoPlayerPerformanceHelper.kt:78‑81`, `109‑110`). With the helper disabled, stock values apply (2.5 s) (`:325‑333`). A custom allocator and byte-bounded buffer (`:286‑322`), an aggressive initial bitrate estimate (`:340‑346`), and an optional multi-connection `ParallelRangeDataSource` for progressive files (`ParallelRangeDataSource.kt:31‑51`), **off by default** (`PlayerSettingsDataStore.kt:289`).
- **Assessment:** by configuration, ARVIO starts playback sooner. There is no structural Nuvio advantage for time-to-first-frame. **[Assumption, low: Nuvio's parallel ranges help 4K remux throughput and rebuffering on fast links, but only when a user enables them.]**

### 2.6 Caching strategy

| Layer | ARVIO | Nuvio TV |
|---|---|---|
| HTTP disk | 50 MB, shared by API and add-ons; errors are never cached (`OkHttpProvider.kt:49`, `289‑295`, `343`) | 50 MB for first-party + a separate 50 MB add-on cache; errors → `no-store` (`NetworkModule.kt:107`, `119‑127`, `159`) |
| Home rows | disk snapshot, 16 items per row, shown at once, refreshed after about 4.7 s (`HomeViewModel.kt:1140‑1163`, `1887‑1938`); memory `cachedHomeCategories` 2 min (`MediaRepository.kt:143`) | none; the add-on HTTP cache covers catalog responses; per-row refresh TTL 15 min when returning to Home (`HomeViewModel.kt:97`) |
| Metadata | in-memory maps, TTL 5 min, `detailsCache` unbounded, lost on process death (`MediaRepository.kt:137`, `157‑174`) | enrichment/prefetch/CW caches bounded to 64 entries (`HomeViewModel.kt:100‑102`) |
| Images | memory 32/48/64 MB fixed; disk 96/128 MB; `respectCacheHeaders(false)` (`ArflixApplication.kt:233‑249`) | memory 15–25 % of RAM class; disk 200 MB; stale-while-revalidate (`NuvioApplication.kt:131‑164`) |
| Streams | memory + disk with TTLs (2.4) | session cache |

**[Assumption, low]** Whether TMDB v3 JSON responses carry cacheable `Cache-Control` headers, and therefore whether ARVIO's 2.2.1 hydration hits the OkHttp disk cache after a process restart, was not verified. It decides how bad cold Home is in practice and has to be measured (§6, M4).

## 3. Mobile vs TV at a glance

| Area | TV | Phone |
|---|---|---|
| Per-item TMDB hydration (2.2.1) | affected | affected |
| 5-per-host dispatcher (2.2.2) | affected | affected |
| Deliberate refresh delay (2.2.3) | affected (800 ms, +settle when cached) | affected (same `loadHomeData` entry, `HomeViewModel.kt:2464`) |
| Add-on row concurrency | 2 | 6 |
| Main-thread cache writes (2.2.4) | no | **yes** |
| `original` backdrops (2.3.1) | affected (landscape default + hero) | affected (landscape default) |
| Hero per D-pad key + trailer default (2.3.2) | **yes** | n/a (pager hero) |
| Stream resolution / playback start | at least on par with Nuvio | at least on par with Nuvio |

## 4. Prioritized recommendations (by user impact)

| # | Problem | Proposed change | Effort | Risk | Files |
|---|---|---|---|---|---|
| R1 | Add-on/Trakt/MDBList rows need 3–4 requests per item before the row appears (2.2.1) | Publish rows from the source payload immediately: map add-on `metas` (`poster`, `background`, `name`, `imdbRating`, `releaseInfo`) to `MediaItem`, and keep the IMDb ID. Resolve the TMDB ID lazily (on focus/Details) or in the background at low priority, and merge it in. At minimum: drop `getImdbRating` from `getMovieDetails`/`getTvDetails` on the catalog path (use `meta.imdbRating`, or TMDB `vote_average` as a placeholder), use `append_to_response=external_ids` instead of a separate `/external_ids` call, and publish partial rows. | M (minimum) / L (full) | Medium: many consumers expect a positive TMDB `id` (Details navigation, watched badges, CW matching) | `MediaRepository.kt:1877‑1946`, `2637‑2800`, `3025‑3090`; `HomeViewModel.kt:2598‑2622`, `3393‑3415`; `data/model/MediaItem` |
| R2 | 5 concurrent requests per host for TMDB API and images (2.2.2) | Give `OkHttpProvider` an explicit `Dispatcher` (e.g. `maxRequests = 64`, `maxRequestsPerHost = 12–16` for the app client and 16 for `coilClient`). Better still, give TMDB its own dispatcher with a priority lane, so the Details screen does not queue behind Home hydration. | S | Low–medium: watch TMDB rate limits (~40–50 rps) and low-RAM TVs; also applies to the IPTV hosts that already have their own guards (`IptvProviderRequestGuard.kt`) | `network/OkHttpProvider.kt:255‑299`, `462‑480`; `di/AppModule.kt:39‑73` |
| R3 | `original` backdrops for cards and hero (2.3.1) | Cards: `w780` backdrops (card width ≤ ~420 px). Hero: `w1280`, and `original` only for 4K panels with enough RAM. Keep `BACKDROP_BASE_LARGE` for Details/Player only. Change it at the mapping sites, not in the UI. | S | Low: slightly softer hero on 4K panels | `util/Constants.kt:63‑70`; `MediaRepository.kt:4086`, `4119`, `4156`; `WatchlistRepository.kt:57`, `602`, `621`; `TraktRepository.kt:2400‑3774` |
| R4 | Deliberately delayed Home refresh (2.2.3) | Start `loadHomeData()` immediately and keep the "settle" gate for **image warm-ups only**. Raise TV custom-row concurrency to 3–4 once R1/R2 have landed (keep 1–2 on low-RAM TVs). Run `syncHomeServerCatalogs(getCatalogCandidates())` in parallel with, not before, the first row requests. | S | Medium: the delays were introduced for first-frame smoothness on low-end TVs, so verify with the jank benchmark (§6) | `HomeViewModel.kt:1398‑1411`, `2457‑2540`, `2600` |
| R5 | TV hero follows every key press (2.3.2) | Gate hero updates on focus settle (~300–450 ms without a nav event) instead of 0–80 ms. Move the hero state out of `HomeUiState` into a separate `StateFlow` that only the hero composable collects. Move `cardLogoUrls` out of `HomeUiState` too. Keep the backdrop request frozen during fast navigation. | M | Low–medium: behaviour changes slightly (the hero lags during fast scrolling, as in Nuvio) | `HomeViewModel.kt:84‑121`, `1289`, `4540‑4638`; `HomeScreen.kt:730`, `589‑686`, `1000‑1200` |
| R6 | Trailer autoplay on by default on TV (2.3.2) | Default `trailerAutoPlay` to off on TV (or on low-RAM devices only), or start trailers only after ≥ 3 s of settle. Reuse `OkHttpProvider.playbackClient` instead of building a new `OkHttpClient` per hero player. | S | Low (product decision) | `SettingsViewModel.kt:644`; `HomeScreen.kt:523‑557` |
| R7 | Stream prefetch waits for the whole Details pipeline (2.4) | Start `prefetchStreamsInBackground` as soon as `externalIdsDeferred` completes, or immediately when the navigation argument or the `imdbIdCache` already holds a `tt` ID. Pass the add-on's IMDb ID through navigation. | S | Low | `DetailsViewModel.kt:508‑737`, `1663‑1740`; navigation args |
| R8 | Main-thread Gson + file write per row on phone (2.2.4) | Replace it with a single debounced (≥ 1 s) `Dispatchers.IO` writer, as on TV. | S | Low | `HomeViewModel.kt:1140‑1163`, `3381`, `3406` |
| R9 | Eager singleton graph in `Application` (2.1) | Inject `dagger.Lazy<…>`/`Provider<…>` in `ArflixApplication`. Wire `cloudSyncRepository.onPushCompleted` inside the delayed coroutine. Stop calling `.get()` on the `Lazy` repos in `setContent` (pass the `Lazy`, or obtain them in the destinations that need them). Remove `StartupViewModel`. | S–M | Low | `ArflixApplication.kt:60‑170`; `MainActivity.kt:180‑205`, `298‑405`; `ui/startup/StartupViewModel.kt` |
| R10 | Stream resolution outlives its collector (2.4) | Run the work in the `callbackFlow` producer scope (or keep the `Job` and cancel it in `awaitClose`). If cache warming must survive navigation, keep only the result write detached. | S | Low–medium: the Details prefetch relies on the cache being filled even if the user leaves quickly | `StreamRepository.kt:2247‑2491`, `2840‑3117` |
| R11 | Image loader behind the current state (2.3.1) | After R3: consider Coil 3 (stale-while-revalidate, `bitmapFactoryMaxParallelism`) and a RAM-class-relative memory cache on TVs with more than 2 GB. | M | Medium (API migration across 106 image call sites) | `ArflixApplication.kt:218‑268`; all `AsyncImage`/`ImageRequest` users |
| R12 | Unbounded metadata maps, 5-minute TTL only in memory (2.6) | Use bounded `LruCache`s. Persist resolved `imdb→tmdb` mappings and basic card metadata to disk (Room/DataStore), because these mappings never change. | M | Low | `MediaRepository.kt:137‑200` |

Expected order of perceived gains, all **[Assumption, medium]**: R1+R2+R3 together bring the largest improvement in Home fill time on a cold process. R4 shortens time-to-fresh-content. R5+R6 improve D-pad responsiveness on low-end TVs. R7 shortens time-to-streams on Details.

## 5. What cannot change without a major rework

- **The TMDB-centric data model.** `MediaItem` is keyed by a TMDB `id: Int`, and Details, watched state, Continue Watching, Trakt/Simkl sync and logo lookups all assume it (for example the `"${mediaType}_${id}"` keys throughout `HomeViewModel.kt`). The "render add-on metas as-is" part of R1, in full, needs a second identity (IMDb or add-on ID) as a first-class key. Nuvio is add-on-/IMDb-ID-first by design (`CatalogRepositoryImpl.kt:59`).
- **The Compose/tv-foundation upgrade.** Moving from BOM 2024.06 + tv-foundation alpha11 to a current BOM means replacing the remaining `TvLazy*` lists (Details, StreamSelector, PersonModal, skeletons), re-validating the custom focus/scroll system (`arvioDpadFocusGroup`, `arvioManualBringIntoViewBoundary`, index-driven Home scrolling), and re-running the TV focus test suites listed in `docs/navigation-performance-2026-09-13.md`. The gains are probably substantial (§2.3.3), but so is the effort and regression risk (L).
- **The monolithic ViewModels and screens.** `HomeViewModel.kt` (5.4 k lines), `HomeScreen.kt` (4.1 k), `DetailsScreen.kt` (4.9 k) and `PlayerScreen.kt` (7.3 k) hold state and orchestration that Nuvio splits into pipelines and presentation layers (`HomeViewModelCatalogPipeline.kt`, `HomeViewModelPresentationPipeline.kt`, `ModernHome*`). R5 can be done locally, but a wider reduction of recomposition scope needs this split.
- **One APK for TV and phone.** Shared ViewModels carry both code paths (`isTvDevice` branches). This is not a performance problem in itself, but it makes Home-pipeline changes riskier than in Nuvio's two separate code bases.

## 6. Measurement plan

Goal: every item from R1–R10 gets a before/after number from a **release-like build** (`assembleSideloadStaging` or the `benchmark` variant; never debug) on the reference TV (TCL C7K), one ≤ 2 GB Android TV device and one mid-range phone.

### M1 — Trace sections (android.os.Trace / androidx.tracing)

Add `trace("arvio:…")` blocks. They are free in release when no tracer is attached:

| Section | Where |
|---|---|
| `arvio:app.onCreate` / `arvio:hilt.inject` | `ArflixApplication.onCreate` around `super.onCreate()` |
| `arvio:home.snapshotShown` (instant event) | `HomeViewModel.kt:1931` after the snapshot publish |
| `arvio:home.loadHomeData` | `HomeViewModel.kt:2457` → end of the final publish |
| `arvio:home.preRowSync` | `HomeViewModel.kt:2514‑2540` |
| `arvio:catalog.row:<id>` (async section) | `MediaRepository.loadCustomCatalogPage` enter → return, with item count |
| `arvio:catalog.hydrate:<id>` | the `jobs.mapNotNull { it.await() }` block (`MediaRepository.kt:1934`) |
| `arvio:hero.update` | `performHeroUpdate` |
| `arvio:details.toStreamsPrefetch` | `loadDetails` start → `prefetchStreamsInBackground` |
| `arvio:streams.firstResult` / `arvio:streams.final` | the first and final `ProgressiveStreamResult` in `DetailsViewModel` |
| `arvio:player.firstFrame` | `Player.Listener.onRenderedFirstFrame` |

Capture with Perfetto (`record_android_trace -a com.arvio.tv … gfx view sched`). Read the numbers with SQL over `slice`, not by eye.

### M2 — Macrobenchmark (existing `:benchmark` module)

- `StartupBenchmarks.coldStartup` (`StartupMode.COLD`, `CompilationMode.Partial()` = with baseline profile): report `timeToInitialDisplayMs` and `timeToFullDisplayMs`. **Add `reportFullyDrawn()`** once the first real (non-skeleton) Home row is published, otherwise TTFD means nothing.
- `homeNavigationJank`: `FrameTimingMetric` (P50/P90/P99 `frameDurationCpuMs`, `frameOverrunMs`) over a fixed D-pad script (20× right, 5× down, 20× right). This is the before/after gate for R5 and R6.
- A new `homeCatalogFill` benchmark with `TraceSectionMetric("arvio:home.loadHomeData")` and `TraceSectionMetric("arvio:catalog.row:%", Mode.Sum)`, run against a **fixed add-on set** (for example Cinemeta plus two catalog add-ons) and a warm or cold process. This is the gate for R1, R2 and R4.
- Regenerate the baseline profile after R1/R5 (`BaselineProfileGenerator`), and add a Details → Sources → Player journey. The current journey stops at Details (`BaselineProfileGenerator.kt:57‑66`).

### M3 — Log timestamps for field and tester builds

Use one structured line per milestone with `SystemClock.elapsedRealtime()` relative to process start (`Process.getStartElapsedRealtime()`), for example `PERF home_first_real_row=1840 rows=3`, `PERF details_first_stream=950 addons=4/7`, `PERF player_first_frame=620`. Testers can then report numbers from `adb logcat -s PERF` instead of impressions.

### M4 — Network accounting

- Log `OkHttpProvider.client.cache` `requestCount`/`networkCount`/`hitCount` after Home has settled. This answers the open question from 2.6 (whether TMDB responses are served from the disk cache).
- Log `dispatcher.queuedCallsCount()` and `runningCallsCount()` every 250 ms while Home loads. This makes the per-host queue from 2.2.2 visible directly, before and after R2.
- Count requests per host per Home load with an `EventListener` (`callStart`/`callEnd`). The target after R1 is ≤ 1 request per add-on row on the critical path.

### M5 — Protocol

- 5 iterations per configuration, the median reported alongside P90. Airplane mode off, same Wi-Fi, same add-on set, same profile, cleared app data for "first launch", and `am kill` (not force-stop) for "cold process with data".
- Compare against Nuvio TV **only** on these user-visible milestones: first Home content, Home fully populated (same add-on set), D-pad frame P90, Details→first stream, Play→first frame. Use screen recording at 60 fps and count frames, because Nuvio cannot be instrumented.
- Record the device's `ActivityManager.isLowRamDevice` / memory class with every run, because ARVIO's code branches on both (`HomeViewModel.kt:1225`, `1291`; `ArflixApplication.kt:233‑239`; `PlayerScreen.kt:6621`).
