package de.trei.cambackup.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Vorher/Nachher-Vergleich: links das Original, rechts die verkleinerte Version.
 * Die Trennlinie lässt sich ziehen oder per Tipp versetzen.
 */
@Composable
fun CompareSlider(
    before: ImageBitmap,
    after: ImageBitmap,
    beforeLabel: String,
    afterLabel: String,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableFloatStateOf(0.5f) }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(before.width.toFloat() / before.height)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    split = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        Image(after, contentDescription = afterLabel, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Image(
            before,
            contentDescription = beforeLabel,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } },
        )
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split
            val cy = size.height / 2
            val r = 16.dp.toPx()
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = r + 2.dp.toPx(), center = Offset(x, cy))
            drawCircle(Color.White, radius = r, center = Offset(x, cy))
            // Zwei kleine Pfeile im Griff
            val a = 5.dp.toPx()
            val gap = 4.dp.toPx()
            val arrows = Path().apply {
                moveTo(x - gap, cy - a); lineTo(x - gap - a, cy); lineTo(x - gap, cy + a); close()
                moveTo(x + gap, cy - a); lineTo(x + gap + a, cy); lineTo(x + gap, cy + a); close()
            }
            drawPath(arrows, Color.DarkGray)
        }
        Badge(beforeLabel, Modifier.align(Alignment.TopStart))
        Badge(afterLabel, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
