package de.trei.cambackup.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trei.cambackup.ui.theme.LocalSiteColors

/*
 * Bausteine des Baustellen-Looks: Warnstreifen, Aufkleber (dicker Rand mit versetztem Schatten),
 * Absperrband als Fortschrittsbalken und ein Fortschrittsring.
 */

/** Schräge Warnstreifen (wie Absperrband) als Hintergrund. */
fun Modifier.hazardStripes(first: Color, second: Color, stripe: Dp = 12.dp): Modifier = drawBehind {
    drawRect(second)
    val w = stripe.toPx()
    val h = size.height
    clipRect {
        var x = -h
        while (x < size.width + h) {
            val p = Path().apply {
                moveTo(x, h)
                lineTo(x + w, h)
                lineTo(x + w + h, 0f)
                lineTo(x + h, 0f)
                close()
            }
            drawPath(p, first)
            x += 2 * w
        }
    }
}

/** Waagerechter Warnstreifen, z. B. ganz oben in der App. */
@Composable
fun HazardStripe(modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val site = LocalSiteColors.current
    Box(modifier.fillMaxWidth().height(height).hazardStripes(MaterialTheme.colorScheme.primary, site.stripe))
}

/** Aufkleber: dicker Rand und ein fester, versetzter Schatten statt weicher Schatten. */
fun Modifier.sticker(shape: Shape, edge: Color, fill: Color, offset: Dp = 6.dp, border: Dp = 2.5.dp, radius: Dp = 28.dp): Modifier =
    this
        .drawBehind {
            val o = offset.toPx()
            drawRoundRect(edge, topLeft = Offset(o, o), size = Size(size.width, size.height), cornerRadius = CornerRadius(radius.toPx()))
        }
        .clip(shape)
        .background(fill)
        .border(border, edge, shape)

/** Große Karte im Aufkleber-Stil. */
@Composable
fun StickerCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val site = LocalSiteColors.current
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .padding(end = 6.dp, bottom = 6.dp)
            .fillMaxWidth()
            .sticker(shape, site.stickerEdge, MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/** Kopf einer Schritt-Karte: Absperrband quer, darauf ein runder Aufkleber mit dem Symbol. */
@Composable
fun SymbolBand(@DrawableRes icon: Int, modifier: Modifier = Modifier, done: Boolean = false) {
    val site = LocalSiteColors.current
    val badge = if (done) site.yellow else MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(128.dp).background(site.band), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(18.dp).hazardStripes(MaterialTheme.colorScheme.primary, site.stripe))
        Box(
            Modifier
                .size(92.dp)
                .sticker(CircleShape, site.stickerEdge, badge, offset = 4.dp, border = 3.dp, radius = 46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = if (done) site.onYellow else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(46.dp))
        }
    }
}

/** Hauptknopf im Aufkleber-Stil (orange, dunkle Schrift). */
@Composable
fun StickerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val site = LocalSiteColors.current
    val shape = RoundedCornerShape(18.dp)
    val fill = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .sticker(shape, if (enabled) site.stickerEdge else MaterialTheme.colorScheme.outlineVariant, fill, offset = 4.dp, radius = 18.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Runder Symbol-Knopf (Pausieren, Abbrechen). */
@Composable
fun RoundStickerButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, fill: Color, onFill: Color) {
    val site = LocalSiteColors.current
    Box(
        Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .size(60.dp)
            .sticker(CircleShape, site.stickerEdge, fill, offset = 3.dp, radius = 30.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = onFill, modifier = Modifier.size(26.dp))
    }
}

/** Fortschrittsbalken als Absperrband; [fraction] null = unbestimmt (ganzes Band, gedämpft). */
@Composable
fun HazardProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val site = LocalSiteColors.current
    val f by animateFloatAsState((fraction ?: 1f).coerceIn(0f, 1f), label = "band")
    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(2.dp, site.stickerEdge, RoundedCornerShape(8.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .hazardStripes(MaterialTheme.colorScheme.primary, if (fraction == null) MaterialTheme.colorScheme.surfaceContainerHighest else site.yellow, 10.dp),
        )
    }
}

/** Großer Fortschrittsring mit Inhalt in der Mitte. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, ringSize: Dp = 220.dp, content: @Composable BoxScope.() -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val tick = LocalSiteColors.current.stripe.copy(alpha = 0.18f)
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "ring")
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(fill, -90f, 360f * f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // Feine Querstriche wie auf einem Absperrband
            val dashes = 40
            repeat(dashes) { i ->
                if (i % 2 == 0) drawArc(tick, -90f + i * 360f / dashes, 4f, false, topLeft, arcSize, style = Stroke(stroke))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center, content = content)
        }
    }
}

/** Fortschritt der drei Schritte: aktueller Schritt gestreift, erledigte gefüllt mit Haken. */
@Composable
fun StepIndicator(labels: List<String>, current: Int, done: Set<Int>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val site = LocalSiteColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val isDone = i in done
            val isCurrent = i == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val bar = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                Box(
                    when {
                        isCurrent -> bar.hazardStripes(MaterialTheme.colorScheme.primary, site.stripe, 6.dp)
                        isDone -> bar.background(MaterialTheme.colorScheme.primary)
                        else -> bar.background(MaterialTheme.colorScheme.outlineVariant)
                    },
                )
                Text(
                    (if (isDone && !isCurrent) "✓ " else "${i + 1} ") + label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.onSurface
                        isDone -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Großer Fortschrittsring für laufende Aufträge: Prozent in der Mitte, darunter die Restzeit. */
@Composable
fun JobRing(fraction: Float, caption: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProgressRing(fraction, ringSize = 200.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(fraction.coerceIn(0f, 1f) * 100).toInt()} %", style = MaterialTheme.typography.displayMedium)
                if (caption != null) {
                    Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
