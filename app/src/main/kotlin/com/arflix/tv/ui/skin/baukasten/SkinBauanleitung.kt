package com.arflix.tv.ui.skin.baukasten

/**
 * Skin building kit ("Baukasten") — the building instructions ("Bauanleitung").
 *
 * Everything the skin looks like lives in this one value object. The stones only read from
 * here; nothing about the look is hard-coded in the stones themselves. The object is filled
 * from a small JSON file (see [SkinAnleitungLeser]) that the user keeps in their own
 * repository. Unknown or missing values always fall back to the defaults below — the
 * defaults are the "Glas" look (target picture 1).
 *
 * Plain Kotlin only (no Compose types) so the parser and the loader stay unit-testable
 * on the JVM. Colours are ARGB longs, sizes are dp as plain numbers.
 */
data class SkinBauanleitung(
    val name: String = "Glas",
    val farben: SkinFarben = SkinFarben(),
    val titelkarte: SkinTitelkarteWerte = SkinTitelkarteWerte(),
    val reihen: SkinReihenWerte = SkinReihenWerte(),
    val kacheln: SkinKachelWerte = SkinKachelWerte(),
    val menue: SkinMenueWerte = SkinMenueWerte(),
)

data class SkinFarben(
    /** Accent: selected menu entry, badges, progress bars. */
    val akzent: Long = 0xFF5EEAD4,
    /** Base colour of the whole page behind everything. */
    val hintergrund: Long = 0xFF08121C,
    /** How strongly [hintergrund] darkens the blurred background picture (0 = not, 1 = fully). */
    val hintergrundAbdunklung: Float = 0.55f,
    val text: Long = 0xFFFFFFFF,
    val textLeise: Long = 0xB3FFFFFF,
    /** Tint of every glass surface (menu, buttons, tiles). */
    val glas: Long = 0xFFFFFFFF,
    /** How see-through the glass is (0 = invisible, 1 = solid [glas]). */
    val glasDeckkraft: Float = 0.10f,
    /** Thin edge around glass surfaces. */
    val glasRand: Long = 0x33FFFFFF,
)

enum class SkinGroesse { GROSS, KLEIN }

enum class SkinTitelKnopf { ABSPIELEN, DETAILS, TRAILER, MERKLISTE }

data class SkinTitelkarteWerte(
    val groesse: SkinGroesse = SkinGroesse.GROSS,
    val rahmen: Boolean = true,
    val ecken: Int = 22,
    val randfarbe: Long = 0x40FFFFFF,
    /** Dark shade over the left side of the title picture so the text stays readable. */
    val abdunklung: Float = 0.75f,
    val knoepfe: List<SkinTitelKnopf> = listOf(
        SkinTitelKnopf.ABSPIELEN,
        SkinTitelKnopf.DETAILS,
        SkinTitelKnopf.TRAILER,
        SkinTitelKnopf.MERKLISTE,
    ),
    /** Small label top right ("FILM · WEITERSCHAUEN"). */
    val schild: Boolean = true,
    /** Show the title logo instead of the plain title text when one exists. */
    val logo: Boolean = true,
    val beschreibungZeilen: Int = 3,
)

data class SkinReihenWerte(
    /**
     * Order of the rows. Each entry is a row id or a row title as shown on Prodigy's Home,
     * or one of the words "weiterschauen" and "rest" ("rest" = every row not named,
     * in Prodigy's order). Rows not named and no "rest" in the list are not shown.
     */
    val reihenfolge: List<String> = listOf("weiterschauen", "rest"),
    /** Rows (id or title) that are never shown. */
    val ausblenden: List<String> = emptyList(),
    /** Show "5 Titel" next to the row title. */
    val anzahlZeigen: Boolean = true,
)

enum class SkinKachelStil { GLAS, FLACH }

data class SkinKachelWerte(
    val stil: SkinKachelStil = SkinKachelStil.GLAS,
    val breite: Int = 180,
    val ecken: Int = 14,
    /** Colour of the frame around the focused tile. */
    val leuchtrahmen: Long = 0xFFFFFFFF,
    val fortschritt: Boolean = true,
    val titel: Boolean = true,
)

enum class SkinMenueStil { GLAS, KLASSISCH }

data class SkinMenueWerte(
    val stil: SkinMenueStil = SkinMenueStil.GLAS,
    val begruessung: Boolean = true,
    val datum: Boolean = true,
)

/** Row words the instructions understand besides ids and titles. */
internal const val SKIN_REIHE_WEITERSCHAUEN = "weiterschauen"
internal const val SKIN_REIHE_REST = "rest"

/**
 * The built-in default instructions — identical to [SkinBauanleitung] defaults. This is also
 * the file the user copies into their own repository as a starting point; a unit test
 * guarantees text and defaults never drift apart.
 */
const val SKIN_STANDARD_ANLEITUNG = """{
  "version": 1,
  "name": "Glas",
  "farben": {
    "akzent": "#5EEAD4",
    "hintergrund": "#08121C",
    "hintergrundAbdunklung": 0.55,
    "text": "#FFFFFF",
    "textLeise": "#B3FFFFFF",
    "glas": "#FFFFFF",
    "glasDeckkraft": 0.10,
    "glasRand": "#33FFFFFF"
  },
  "titelkarte": {
    "groesse": "gross",
    "rahmen": true,
    "ecken": 22,
    "randfarbe": "#40FFFFFF",
    "abdunklung": 0.75,
    "knoepfe": ["abspielen", "details", "trailer", "merkliste"],
    "schild": true,
    "logo": true,
    "beschreibungZeilen": 3
  },
  "reihen": {
    "reihenfolge": ["weiterschauen", "rest"],
    "ausblenden": [],
    "anzahlZeigen": true
  },
  "kacheln": {
    "stil": "glas",
    "breite": 180,
    "ecken": 14,
    "leuchtrahmen": "#FFFFFF",
    "fortschritt": true,
    "titel": true
  },
  "menue": {
    "stil": "glas",
    "begruessung": true,
    "datum": true
  }
}
"""
