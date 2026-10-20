package dev.spookcue.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.spookcue.data.model.JumpScare
import dev.spookcue.ui.theme.CrimsonLight
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.SurfaceDark
import dev.spookcue.ui.theme.TextPrimary

@Composable
fun ScareTimeline(
    scares: List<JumpScare>,
    totalDurationMs: Long,
    currentTimeMs: Long,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        if (totalDurationMs <= 0) return@Canvas

        drawLine(
            color = SurfaceDark,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 6.dp.toPx()
        )

        for (scare in scares) {
            val fraction = (scare.resolvedTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            val x = fraction * width
            val isMajor = scare.type.equals("major", ignoreCase = true)
            val tickColor = if (isMajor) CrimsonLight else CrimsonPrimary
            val tickHeight = if (isMajor) 18.dp.toPx() else 10.dp.toPx()

            drawLine(
                color = tickColor,
                start = Offset(x, centerY - tickHeight / 2),
                end = Offset(x, centerY + tickHeight / 2),
                strokeWidth = if (isMajor) 3.dp.toPx() else 2.dp.toPx()
            )
        }

        val currentFraction = (currentTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        val playheadX = currentFraction * width

        drawCircle(
            color = TextPrimary,
            radius = 6.dp.toPx(),
            center = Offset(playheadX, centerY)
        )
    }
}
