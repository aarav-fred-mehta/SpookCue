package dev.spookcue.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.spookcue.ui.theme.CrimsonPulse
import dev.spookcue.ui.theme.PureBlack

@Composable
fun PulseOverlay(
    isPulsing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "CrimsonPulse")
        val animatedColor by transition.animateColor(
            initialValue = PureBlack,
            targetValue = CrimsonPulse,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseColor"
        )
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(animatedColor)
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PureBlack)
        ) {
            content()
        }
    }
}
