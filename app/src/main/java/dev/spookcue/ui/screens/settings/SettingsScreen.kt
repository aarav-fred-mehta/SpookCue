package dev.spookcue.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.spookcue.ui.theme.CrimsonDark
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.SurfaceDark
import dev.spookcue.ui.theme.TextPrimary
import dev.spookcue.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = PureBlack,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.titleLarge,
                        color = CrimsonPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "ALERTS & THEATER MODE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CrimsonPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Haptic Vibrations", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Vibrate phone 10s / 5s / 2s before scares", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = uiState.hapticsEnabled,
                    onCheckedChange = { viewModel.toggleHaptics(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = CrimsonPrimary, checkedTrackColor = CrimsonDark)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Peripheral Crimson Pulse", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Flash dark crimson on screen before major scares", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = uiState.pulseEnabled,
                    onCheckedChange = { viewModel.togglePulse(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = CrimsonPrimary, checkedTrackColor = CrimsonDark)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SurfaceDark)
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "DATABASE & UPDATES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CrimsonPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Local catalog version: ${if (uiState.localVersion > 0) "v${uiState.localVersion}" else "Bundled (v1)"}",
                color = TextSecondary,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.syncCatalogNow() },
                enabled = !uiState.isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonDark, contentColor = TextPrimary)
            ) {
                if (uiState.isSyncing) {
                    CircularProgressIndicator(
                        color = TextPrimary,
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Checking CDN...")
                } else {
                    Text("Sync Catalog from CDN")
                }
            }

            if (uiState.syncMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.syncMessage ?: "",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
