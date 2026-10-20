package dev.spookcue.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spookcue.ui.theme.CrimsonDark
import dev.spookcue.ui.theme.TextPrimary

@Composable
fun ManualSyncControls(
    onAdjustOffset: (deltaMs: Long) -> Unit,
    onForceResync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            onClick = { onAdjustOffset(-10000L) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("-10s", fontSize = 12.sp)
        }

        OutlinedButton(
            onClick = { onAdjustOffset(-1000L) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("-1s", fontSize = 12.sp)
        }

        Button(
            onClick = onForceResync,
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonDark, contentColor = TextPrimary)
        ) {
            Text("Resync", fontSize = 12.sp)
        }

        OutlinedButton(
            onClick = { onAdjustOffset(1000L) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("+1s", fontSize = 12.sp)
        }

        OutlinedButton(
            onClick = { onAdjustOffset(10000L) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("+10s", fontSize = 12.sp)
        }
    }
}
