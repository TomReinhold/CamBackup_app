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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Farben für den Sicherungsstatus – überall gleich (Übersicht, Fotos, Legenden). */
object StatusColors {
    val compressed = Color(0xFF3FA55B)
    @Composable fun backedUp() = MaterialTheme.colorScheme.primary
    @Composable fun open() = MaterialTheme.colorScheme.outlineVariant
}

/** Karte mit Symbol im farbigen Kreis, Titel und optionaler Unterzeile. */
@Composable
fun FeatureCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

enum class BannerTone { INFO, SUCCESS, WARNING, ERROR }

/** Farbig hinterlegter Hinweis. */
@Composable
fun Banner(text: String, tone: BannerTone = BannerTone.INFO, modifier: Modifier = Modifier) {
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
