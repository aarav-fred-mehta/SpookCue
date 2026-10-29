package dev.spookcue.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.spookcue.data.model.JumpScare
import dev.spookcue.ui.theme.LunarBorder
import dev.spookcue.ui.theme.LunarSlateDim
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.LunarWhite
import dev.spookcue.ui.theme.TheaterAmber

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

        // Base progress track (thinner, subtle dark slate)
        drawLine(
            color = LunarBorder,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Elapsed track line
        val currentFraction = (currentTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        val playheadX = currentFraction * width

        if (playheadX > 0f) {
            drawLine(
                color = LunarSlateDim,
                start = Offset(0f, centerY),
                end = Offset(playheadX, centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Thinner scare markers/tabs
        for (scare in scares) {
            val fraction = (scare.resolvedTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            val x = fraction * width
            val isMajor = scare.type.equals("major", ignoreCase = true)
            val tickColor = if (isMajor) TheaterAmber else LunarSlateMuted.copy(alpha = 0.6f)
            val tickHeight = if (isMajor) 14.dp.toPx() else 8.dp.toPx()
            val tickStroke = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx()

            drawLine(
                color = tickColor,
                start = Offset(x, centerY - tickHeight / 2),
                end = Offset(x, centerY + tickHeight / 2),
                strokeWidth = tickStroke,
                cap = StrokeCap.Round
            )
        }

        // Redesigned luminous playhead beacon:
        // 1. Soft glowing outer pulse halo
        drawCircle(
            color = LunarWhite.copy(alpha = 0.15f),
            radius = 7.dp.toPx(),
            center = Offset(playheadX, centerY)
        )

        // 2. Crisp outer boundary ring
        drawCircle(
            color = LunarWhite,
            radius = 4.5.dp.toPx(),
            center = Offset(playheadX, centerY),
            style = Stroke(width = 1.2.dp.toPx())
        )

        // 3. Luminous amber core dot
        drawCircle(
            color = TheaterAmber,
            radius = 2.dp.toPx(),
            center = Offset(playheadX, centerY)
        )
    }
}
