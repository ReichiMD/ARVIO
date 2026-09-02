package com.arflix.tv.util

/**
 * The content language the user picked in Settings ("de-DE", "en-US", ...).
 *
 * MediaRepository.contentLanguage is the source of truth and its setter is the only
 * place this is ever written; this object exists so code that must not depend on the
 * whole repository — the sideload-only plugin path — can still read the setting.
 *
 * Why not java.util.Locale.getDefault(): the app locale is process-global mutable state,
 * written once from MainActivity.attachBaseContext (out of a separate SharedPreferences)
 * and again from a Compose remember block once the profile's DataStore value arrives.
 * What it holds at the moment a background scraper runs is therefore a timing question.
 * This value is not — it is set from the same profile preference the rest of the app uses.
 */
object ContentLanguage {
    @Volatile
    var tag: String = "en-US"
}
