package de.trei.cambackup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trei.cambackup.R

/*
 * Baustellen-Look: Orange mit schwarz-orangen Warnstreifen, dazu Helm-Gelb. Feste Farben statt der
 * Farben vom Hintergrundbild, damit die App überall gleich aussieht – hell und dunkel.
 */

/** Farben, die Material 3 nicht kennt: Streifen, Aufkleber-Rand und -Schatten, Helm-Gelb. */
@Immutable
data class SiteColors(
    /** Zweite Farbe der Warnstreifen (die erste ist primary). */
    val stripe: Color,
    /** Rand und versetzter Schatten der Aufkleber-Karten und -Knöpfe. */
    val stickerEdge: Color,
    /** Fläche hinter dem runden Symbol oben auf einer Schritt-Karte. */
    val band: Color,
    val yellow: Color,
    val onYellow: Color,
)

private val Ink = Color(0xFF1F1A14)
private val Night = Color(0xFF15110D)
private val Cream = Color(0xFFFFF6EC)
private val Paper = Color(0xFFFFF3E3)

private val LightSite = SiteColors(
    stripe = Ink,
    stickerEdge = Ink,
    band = Color(0xFFFFE2C7),
    yellow = Color(0xFFFFC531),
    onYellow = Ink,
)

private val DarkSite = SiteColors(
    stripe = Night,
    stickerEdge = Color(0xFFFF8A33),
    band = Color(0xFF2E261E),
    yellow = Color(0xFFFFC531),
    onYellow = Night,
)

val LocalSiteColors = staticCompositionLocalOf { LightSite }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFFF7A1A),
    onPrimary = Ink,
    primaryContainer = Color(0xFFFFE2C7),
    onPrimaryContainer = Ink,
    secondary = Color(0xFFB34700),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2C7),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFFFFC531),
    onTertiary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E6D6),
    onSurfaceVariant = Color(0xFF6E6253),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFFFFBF6),
    surfaceContainerHighest = Color(0xFFF3E6D6),
    outline = Ink,
    outlineVariant = Color(0xFFEADCCB),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF8A33),
    onPrimary = Night,
    primaryContainer = Color(0xFF4A2A10),
    onPrimaryContainer = Paper,
    secondary = Color(0xFFFFB070),
    onSecondary = Night,
    secondaryContainer = Color(0xFF2E261E),
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFFFC531),
    onTertiary = Night,
    background = Night,
    onBackground = Paper,
    surface = Night,
    onSurface = Paper,
    surfaceVariant = Color(0xFF2E261E),
    onSurfaceVariant = Color(0xFFB8A994),
    surfaceContainerLowest = Night,
    surfaceContainerLow = Color(0xFF1E1914),
    surfaceContainer = Color(0xFF241E18),
    surfaceContainerHigh = Color(0xFF2A231C),
    surfaceContainerHighest = Color(0xFF332A22),
    outline = Color(0xFFFF8A33),
    outlineVariant = Color(0xFF3A3128),
)

/** Runde Überschriften-Schrift (Fredoka) und gut lesbarer Fließtext (Nunito), beide SIL Open Font License. */
@OptIn(ExperimentalTextApi::class)
private fun fredoka(weight: Int) = Font(
    R.font.fredoka, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Fredoka = FontFamily(fredoka(500), fredoka(600), fredoka(700))
val Nunito = FontFamily(nunito(400), nunito(500), nunito(700), nunito(800))

private val Base = Typography()

private fun TextStyle.display(weight: Int = 700) = copy(fontFamily = Fredoka, fontWeight = FontWeight(weight))
private fun TextStyle.body(weight: Int = 500) = copy(fontFamily = Nunito, fontWeight = FontWeight(weight))

private val SiteTypography = Typography(
    displayLarge = Base.displayLarge.display(),
    displayMedium = Base.displayMedium.display(),
    displaySmall = Base.displaySmall.display(),
    headlineLarge = Base.headlineLarge.display(),
    headlineMedium = Base.headlineMedium.display(),
    headlineSmall = Base.headlineSmall.display(),
    titleLarge = Base.titleLarge.display(),
    titleMedium = Base.titleMedium.display(600),
    titleSmall = Base.titleSmall.body(800),
    bodyLarge = Base.bodyLarge.body(),
    bodyMedium = Base.bodyMedium.body(),
    bodySmall = Base.bodySmall.body(),
    labelLarge = Base.labelLarge.body(800),
    labelMedium = Base.labelMedium.body(800),
    labelSmall = Base.labelSmall.body(700).copy(fontSize = 11.sp),
)

private val SiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CamBackupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSiteColors provides if (darkTheme) DarkSite else LightSite) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = SiteTypography,
            shapes = SiteShapes,
            content = content,
        )
    }
}
