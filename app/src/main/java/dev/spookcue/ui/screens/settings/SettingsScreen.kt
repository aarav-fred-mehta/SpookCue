package dev.spookcue.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import dev.spookcue.data.local.UserPreferencesManager
import dev.spookcue.ui.theme.LunarBorder
import dev.spookcue.ui.theme.LunarSlate
import dev.spookcue.ui.theme.LunarSlateDim
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.LunarSurface
import dev.spookcue.ui.theme.LunarWhite
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.TheaterAmber

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = PureBlack,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = LunarWhite
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = LunarSlateMuted
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
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = "ALERTS & SCARE SENSITIVITY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = LunarSlateMuted,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Major Scares Only Filter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Major Scares Only", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                    Text("Ignore minor scares and only alert for major heart-stoppers", color = LunarSlateDim, fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.majorScaresOnly,
                    onCheckedChange = { viewModel.toggleMajorScaresOnly(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TheaterAmber,
                        checkedTrackColor = LunarSurface
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Alert Lead Time
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Alert Lead Time", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                Text("How many seconds in advance to be warned", color = LunarSlateDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val leadTimes = listOf(5, 10, 15, 20)
                    for (seconds in leadTimes) {
                        val isSelected = uiState.alertLeadTimeSeconds == seconds
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setAlertLeadTime(seconds) },
                            label = { Text("${seconds}s", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LunarSurface,
                                selectedLabelColor = TheaterAmber,
                                containerColor = PureBlack,
                                labelColor = LunarSlateMuted
                            ),
                            border = BorderStroke(1.dp, if (isSelected) TheaterAmber else LunarBorder)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Haptic Vibrations Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Haptic Vibrations", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                    Text("Vibrate phone during pocket & screen-off mode", color = LunarSlateDim, fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.hapticsEnabled,
                    onCheckedChange = { viewModel.toggleHaptics(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LunarWhite,
                        checkedTrackColor = LunarSurface
                    )
                )
            }

            if (uiState.hapticsEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Haptic Vibration Style", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                    Text("Select your preferred vibration pattern", color = LunarSlateDim, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val patterns = listOf(
                            UserPreferencesManager.PATTERN_HEARTBEAT,
                            UserPreferencesManager.PATTERN_STANDARD
                        )
                        for (pat in patterns) {
                            val isSelected = uiState.hapticPattern == pat
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setHapticPattern(pat) },
                                label = { Text(pat, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LunarSurface,
                                    selectedLabelColor = LunarWhite,
                                    containerColor = PureBlack,
                                    labelColor = LunarSlateMuted
                                ),
                                border = BorderStroke(1.dp, if (isSelected) LunarSlateMuted else LunarBorder)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Peripheral Screen Pulse
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Peripheral Screen Pulse", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                    Text("Subtle ambient glow on screen before major scares", color = LunarSlateDim, fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.pulseEnabled,
                    onCheckedChange = { viewModel.togglePulse(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LunarWhite,
                        checkedTrackColor = LunarSurface
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Audio Transcription Debug Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audio Transcription Debug", color = LunarWhite, fontWeight = FontWeight.SemiBold)
                    Text("Live VU meter, detected speech, and phonetic match inspector on sync screen", color = LunarSlateDim, fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.audioDebugEnabled,
                    onCheckedChange = { viewModel.toggleAudioDebug(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TheaterAmber,
                        checkedTrackColor = LunarSurface
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = LunarBorder)
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "DATABASE & UPDATES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = LunarSlateMuted,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Local catalog version: ${if (uiState.localVersion > 0) "v${uiState.localVersion}" else "Bundled (v1)"}",
                color = LunarSlateDim,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { viewModel.syncCatalogNow() },
                enabled = !uiState.isSyncing,
                border = BorderStroke(1.dp, LunarBorder),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LunarSurface,
                    contentColor = LunarWhite
                )
            ) {
                if (uiState.isSyncing) {
                    CircularProgressIndicator(
                        color = LunarWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text("Sync Catalog Now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            uiState.syncMessage?.let { msg ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = msg, color = LunarSlate, fontSize = 12.sp)
            }
        }
    }
}
