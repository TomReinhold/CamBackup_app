package de.trei.cambackup.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import de.trei.cambackup.R
import de.trei.cambackup.ui.theme.LocalSiteColors
import androidx.compose.ui.unit.dp

/** Farben für den Sicherungsstatus – überall gleich (Übersicht, Fotos, Legenden). */
object StatusColors {
    val compressed = Color(0xFF3FA55B)
    @Composable fun backedUp() = MaterialTheme.colorScheme.primary
    @Composable fun open() = MaterialTheme.colorScheme.outlineVariant
}

/**
 * Karte im Aufkleber-Stil. Groß (Schritt-Karte): oben ein Absperrband mit rundem Symbol, darunter eine
 * große Überschrift. [compact]: nur eine Kopfzeile mit kleinem Symbol (Nebenkarten wie Speichermedium).
 */
@Composable
fun FeatureCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    compact: Boolean = false,
    done: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    StickerCard(modifier) {
        Column {
            if (!compact) SymbolBand(icon, done = done)
            Column(
                Modifier.padding(if (compact) 16.dp else 20.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    trailing?.invoke()
                }
                content()
            }
        }
    }
}

enum class BannerTone { INFO, SUCCESS, WARNING, ERROR }

/**
 * Farbig hinterlegter Hinweis. Blaue Info-Hinweise sind eingeklappt: sichtbar sind nur ⓘ und
 * [title]; ein Tipp klappt den ganzen Text auf und wieder zu. Kurze Hinweise ohne Titel stehen direkt da.
 */
@Composable
fun Banner(text: String, tone: BannerTone = BannerTone.INFO, modifier: Modifier = Modifier, title: String? = null) {
    if (tone == BannerTone.INFO && (title != null || text.length > SHORT_INFO)) {
        InfoBanner(title ?: stringResource(R.string.info_default_title), text, modifier)
        return
    }
    val (bg, fg) = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.SUCCESS -> StatusColors.compressed.copy(alpha = 0.15f) to MaterialTheme.colorScheme.onSurface
        BannerTone.WARNING -> Color(0xFFFFE8B0) to Color(0xFF4A3500)
        BannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = bg, contentColor = fg) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** Info-Hinweise bis zu dieser Länge werden nicht eingeklappt. */
private const val SHORT_INFO = 45

@Composable
private fun InfoBanner(title: String, text: String, modifier: Modifier) {
    // Bleibt beim Scrollen und Drehen erhalten, pro Hinweistext
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier
                .toggleable(value = open, role = Role.Button, onValueChange = { open = it })
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(
                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    stringResource(if (open) R.string.info_collapse else R.string.info_expand),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (open) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    }
}

/** Pausieren/Fortsetzen und Abbrechen als runde Knöpfe; ohne [onPause] nur Abbrechen. */
@Composable
fun JobControls(paused: Boolean, onPause: ((Boolean) -> Unit)?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val site = LocalSiteColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
        onPause?.let { toggle ->
            RoundStickerButton(
                icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                description = stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                onClick = { toggle(!paused) },
                fill = site.yellow,
                onFill = site.onYellow,
            )
        }
        RoundStickerButton(
            icon = R.drawable.ic_close,
            description = stringResource(R.string.action_cancel),
            onClick = onCancel,
            fill = MaterialTheme.colorScheme.surfaceContainer,
            onFill = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Abschnitt eines gestapelten Balkens. */
data class BarSegment(val value: Long, val color: Color)

/** Gestapelter Balken; [total] = 100 % (Standard: Summe der Abschnitte), Rest bleibt grau. */
@Composable
fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier, height: Int = 12, total: Long? = null) {
    val totalF = (total ?: segments.sumOf { it.value }).coerceAtLeast(1).toFloat()
    Row(
        modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        var used = 0f
        segments.forEachIndexed { i, s ->
            val f by animateFloatAsState(s.value / totalF, label = "seg$i")
            used += f
            // Kleine, aber vorhandene Anteile bleiben als schmaler Strich sichtbar
            if (s.value > 0) Box(Modifier.fillMaxHeight().weight(f.coerceAtLeast(0.004f)).background(s.color))
        }
        if (used < 0.999f) Spacer(Modifier.weight(1f - used))
    }
}

/** Kennzahl mit Farbpunkt, Wert und Beschriftung (Legende unter Balken). */
@Composable
fun LegendStat(color: Color, label: String, value: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
