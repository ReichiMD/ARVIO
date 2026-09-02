package com.arflix.tv.ui.components

import java.util.Locale

/**
 * Short language badge for a source or a plugin — a flag plus the language code
 * ("🇩🇪 DE"), or just the code when no flag can be named for the language.
 *
 * Flag *and* code rather than a flag alone, for two reasons. A flag is a country and a
 * language is not: German is spoken in Austria and Switzerland too, and "es"/"pt" have
 * no single obvious flag. And Android TV devices do not all render regional-indicator
 * pairs — where they cannot, the code is still readable on its own.
 *
 * Accepts what the various sources actually produce: ISO codes of either length, a tag
 * with a region ("zh-TW", "pt-BR"), English names ("German"), and the multi-language
 * markers that stream addons emit.
 */
internal fun languageBadgeText(language: String?): String? {
    if (language.isNullOrBlank()) return null
    val normalized = language.trim().uppercase(Locale.ROOT)
    if (normalized.contains("MULTI") || normalized.contains("LANG")) return "🌐 MULTI"

    // A tag that names its own region answers the flag question itself — CloudStream
    // plugins use this form for the cases where language alone is ambiguous.
    val tagged = normalized.replace('_', '-').split('-')
    if (tagged.size >= 2 && tagged[0].length in 2..3 && tagged[1].length == 2) {
        val code = tagged[0].take(2)
        return "${flagEmoji(tagged[1])} $code"
    }

    val code = LANGUAGE_CODES[normalized] ?: return normalized.take(6)
    val country = LANGUAGE_FLAG_COUNTRY[code] ?: return code
    return "${flagEmoji(country)} $code"
}

/** Regional-indicator pair for an ISO-3166 country code, e.g. "DE" -> 🇩🇪. */
private fun flagEmoji(country: String): String {
    if (country.length != 2 || !country.all { it in 'A'..'Z' }) return ""
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + country[0].code)) +
        String(Character.toChars(base + country[1].code))
}

/** Everything a source or manifest might say, mapped to the two-letter code shown. */
private val LANGUAGE_CODES: Map<String, String> = buildMap {
    fun entry(code: String, vararg aliases: String) {
        put(code, code)
        aliases.forEach { put(it, code) }
    }
    entry("EN", "ENG", "ENGLISH")
    entry("DE", "GER", "DEU", "GERMAN", "DEUTSCH")
    entry("FR", "FRE", "FRA", "FRENCH", "FRANCAIS")
    entry("ES", "SPA", "SPANISH", "ESPANOL", "CASTELLANO")
    entry("IT", "ITA", "ITALIAN", "ITALIANO")
    entry("PT", "POR", "PORTUGUESE", "PORTUGUES")
    entry("NL", "NLD", "DUT", "DUTCH", "NEDERLANDS")
    entry("RU", "RUS", "RUSSIAN")
    entry("PL", "POL", "POLISH")
    entry("TR", "TUR", "TURKISH")
    entry("AR", "ARA", "ARABIC")
    entry("HE", "HEB", "IW", "HEBREW")
    entry("FA", "FAS", "PER", "PERSIAN", "FARSI")
    entry("HI", "HIN", "HINDI")
    entry("TA", "TAM", "TAMIL")
    entry("TE", "TEL", "TELUGU")
    entry("ML", "MAL", "MALAYALAM")
    entry("KN", "KAN", "KANNADA")
    entry("BN", "BEN", "BENGALI")
    entry("UR", "URD", "URDU")
    entry("JA", "JPN", "JAPANESE")
    entry("KO", "KOR", "KOREAN")
    entry("ZH", "ZHO", "CHI", "CHINESE", "MANDARIN")
    entry("TH", "THA", "THAI")
    entry("VI", "VIE", "VIETNAMESE")
    entry("ID", "IND", "INDONESIAN")
    entry("MS", "MSA", "MAY", "MALAY")
    entry("TL", "TGL", "FILIPINO", "TAGALOG")
    entry("SV", "SWE", "SWEDISH")
    entry("DA", "DAN", "DANISH")
    entry("NO", "NOR", "NB", "NOB", "NORWEGIAN")
    entry("FI", "FIN", "FINNISH")
    entry("IS", "ISL", "ICE", "ICELANDIC")
    entry("CS", "CES", "CZE", "CZECH")
    entry("SK", "SLK", "SLO", "SLOVAK")
    entry("SL", "SLV", "SLOVENIAN")
    entry("HU", "HUN", "HUNGARIAN")
    entry("RO", "RON", "RUM", "ROMANIAN")
    entry("BG", "BUL", "BULGARIAN")
    entry("EL", "ELL", "GRE", "GREEK")
    entry("UK", "UKR", "UKRAINIAN")
    entry("HR", "HRV", "CROATIAN")
    entry("SR", "SRP", "SERBIAN")
    entry("SQ", "SQI", "ALB", "ALBANIAN")
    entry("MK", "MKD", "MAC", "MACEDONIAN")
    entry("ET", "EST", "ESTONIAN")
    entry("LV", "LAV", "LATVIAN")
    entry("LT", "LIT", "LITHUANIAN")
    entry("CA", "CAT", "CATALAN")
    entry("EU", "EUS", "BAQ", "BASQUE")
    entry("GL", "GLG", "GALICIAN")
    entry("AF", "AFR", "AFRIKAANS")
    entry("SW", "SWA", "SWAHILI")
}

/**
 * The flag shown for a language. Where a language spans countries this is a deliberate
 * pick of the most recognisable one, which is exactly why the code is always shown too.
 */
private val LANGUAGE_FLAG_COUNTRY: Map<String, String> = mapOf(
    "EN" to "GB", "DE" to "DE", "FR" to "FR", "ES" to "ES", "IT" to "IT",
    "PT" to "PT", "NL" to "NL", "RU" to "RU", "PL" to "PL", "TR" to "TR",
    "AR" to "SA", "HE" to "IL", "FA" to "IR", "HI" to "IN", "TA" to "IN",
    "TE" to "IN", "ML" to "IN", "KN" to "IN", "BN" to "BD", "UR" to "PK",
    "JA" to "JP", "KO" to "KR", "ZH" to "CN", "TH" to "TH", "VI" to "VN",
    "ID" to "ID", "MS" to "MY", "TL" to "PH", "SV" to "SE", "DA" to "DK",
    "NO" to "NO", "FI" to "FI", "IS" to "IS", "CS" to "CZ", "SK" to "SK",
    "SL" to "SI", "HU" to "HU", "RO" to "RO", "BG" to "BG", "EL" to "GR",
    "UK" to "UA", "HR" to "HR", "SR" to "RS", "SQ" to "AL", "MK" to "MK",
    "ET" to "EE", "LV" to "LV", "LT" to "LT", "CA" to "ES", "EU" to "ES",
    "GL" to "ES", "AF" to "ZA", "SW" to "KE"
)
