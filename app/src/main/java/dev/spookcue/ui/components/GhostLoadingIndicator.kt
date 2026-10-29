package dev.spookcue.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spookcue.ui.theme.LunarSlate
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.PureBlack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural loading animation that matches the exact SpookCue app ghost geometry
 * with floating motion, soft ambient glow, and an undulating animated bottom skirt wave.
 */
@Composable
fun GhostLoadingIndicator(
    modifier: Modifier = Modifier,
    statusText: String = "Summoning Jump Scare Catalog...",
    subText: String = "Synchronizing horror timestamps"
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GhostMotion")

    // Vertical floating animation
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GhostFloat"
    )

    // Undulating bottom wave phase (0 to 2*PI)
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GhostWave"
    )

    // Ambient glow pulse
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowPulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                modifier = Modifier.size(160.dp)
            ) {
                val scale = size.minDimension / 108f
                val centerX = size.width / 2f
                val centerY = (size.height / 2f) + (floatOffset * scale)

                // 1. Ambient radial glow behind the ghost
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = glowAlpha),
                            Color.White.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = size.minDimension * 0.48f
                    ),
                    radius = size.minDimension * 0.48f,
                    center = Offset(centerX, centerY)
                )

                // 2. Exact App Ghost Silhouette with animated bottom skirt
                drawAnimatedGhost(
                    scale = scale,
                    originX = centerX - (54f * scale),
                    originY = centerY - (54f * scale),
                    wavePhase = wavePhase
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = LunarSlate,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                color = LunarSlateMuted
            )
        }
    }
}

/**
 * Draws the exact vector path from ic_launcher_foreground.xml:
 * Head & sides:
 *   M54,20 C36,20 28,34 28,52 C28,66 32,82 36,88
 * Bottom wave:
 *   C40,94 46,84 54,84 C62,84 68,94 72,88
 * Up right:
 *   C76,82 80,66 80,52 C80,34 72,20 54,20 Z
 * Eyes:
 *   (44,41) r=4, (64,41) r=4
 */
private fun DrawScope.drawAnimatedGhost(
    scale: Float,
    originX: Float,
    originY: Float,
    wavePhase: Float
) {
    // Dynamic offsets for bottom wave points
    val wave1 = (sin(wavePhase.toDouble()) * 3.5).toFloat()
    val wave2 = (sin((wavePhase + PI * 0.7).toDouble()) * 3.5).toFloat()
    val wave3 = (sin((wavePhase + PI * 1.4).toDouble()) * 3.5).toFloat()

    val path = Path().apply {
        // Start top head
        moveTo(originX + 54f * scale, originY + 20f * scale)

        // Left side curve down
        cubicTo(
            originX + 36f * scale, originY + 20f * scale,
            originX + 28f * scale, originY + 34f * scale,
            originX + 28f * scale, originY + 52f * scale
        )
        cubicTo(
            originX + 28f * scale, originY + 66f * scale,
            originX + 32f * scale, originY + 82f * scale,
            originX + 36f * scale, originY + (88f + wave1 * 0.4f) * scale
        )

        // Animated bottom skirt ripples
        cubicTo(
            originX + 40f * scale, originY + (94f + wave1) * scale,
            originX + 46f * scale, originY + (84f - wave2) * scale,
            originX + 54f * scale, originY + (84f + wave2 * 0.6f) * scale
        )
        cubicTo(
            originX + 62f * scale, originY + (84f - wave3) * scale,
            originX + 68f * scale, originY + (94f + wave3) * scale,
            originX + 72f * scale, originY + (88f - wave1 * 0.4f) * scale
        )

        // Right side curve up
        cubicTo(
            originX + 76f * scale, originY + 82f * scale,
            originX + 80f * scale, originY + 66f * scale,
            originX + 80f * scale, originY + 52f * scale
        )
        cubicTo(
            originX + 80f * scale, originY + 34f * scale,
            originX + 72f * scale, originY + 20f * scale,
            originX + 54f * scale, originY + 20f * scale
        )
        close()
    }

    // Ghost body in pure crisp white
    drawPath(path = path, color = Color.White)

    // Left eye (dark cutout from SVG: 44, 41 with radii rx=4, ry=5)
    drawOval(
        color = PureBlack,
        topLeft = Offset(originX + 40f * scale, originY + 36f * scale),
        size = androidx.compose.ui.geometry.Size(8f * scale, 10f * scale)
    )

    // Right eye (dark cutout from SVG: 64, 41 with radii rx=4, ry=5)
    drawOval(
        color = PureBlack,
        topLeft = Offset(originX + 60f * scale, originY + 36f * scale),
        size = androidx.compose.ui.geometry.Size(8f * scale, 10f * scale)
    )
}
