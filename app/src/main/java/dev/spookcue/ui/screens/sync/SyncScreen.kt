package dev.spookcue.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.spookcue.sync.SyncState
import dev.spookcue.ui.components.ManualSyncControls
import dev.spookcue.ui.components.PulseOverlay
import dev.spookcue.ui.components.ScareTimeline
import dev.spookcue.ui.theme.CrimsonDark
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.TextDimCrimson
import dev.spookcue.ui.theme.TextPrimary
import dev.spookcue.ui.theme.TextSecondary
import dev.spookcue.util.formatCountdownMs
import dev.spookcue.util.formatTimeMs

@Composable
fun SyncScreen(
    tmdbId: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val movie by viewModel.movie.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val isPulsing by viewModel.isPulsing.collectAsState()

    LaunchedEffect(tmdbId) {
        viewModel.loadMovieAndStartSync(tmdbId)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
        }
    }

    PulseOverlay(isPulsing = isPulsing) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top back button
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header: Movie Title & Year
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = movie?.title?.uppercase() ?: "LOADING...",
                        style = MaterialTheme.typography.titleLarge,
                        color = CrimsonPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = movie?.let { "${it.releaseYear} • ${it.runtimeMinutes} min • ${it.scares.size} scares" } ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }

                // Middle: Sync State & Timecode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (val state = syncState) {
                        is SyncState.Idle -> {
                            Text(
                                text = "INITIALIZING",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }

                        is SyncState.Acquiring -> {
                            CircularProgressIndicator(
                                color = CrimsonPrimary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "LISTENING FOR DIALOGUE...",
                                style = MaterialTheme.typography.titleLarge,
                                color = CrimsonPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Candidates detected: ${state.candidateMatches} / 2",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }

                        is SyncState.Locked -> {
                            Text(
                                text = "SYNC LOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.currentMovieTimeMs.formatTimeMs(),
                                style = MaterialTheme.typography.displayLarge,
                                color = TextPrimary
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            if (state.nextScareCountdownMs != null && state.nextScareCountdownMs > 0) {
                                Text(
                                    text = "NEXT SCARE IN",
                                    fontSize = 12.sp,
                                    color = TextDimCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = state.nextScareCountdownMs.formatCountdownMs(),
                                    style = MaterialTheme.typography.displayLarge,
                                    color = CrimsonPrimary
                                )
                            } else {
                                Text(
                                    text = "NO MORE SCARES DETECTED",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        is SyncState.Verifying -> {
                            Text(
                                text = "VERIFYING AUDIO SYNC",
                                style = MaterialTheme.typography.titleLarge,
                                color = CrimsonDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.currentMovieTimeMs.formatTimeMs(),
                                style = MaterialTheme.typography.displayLarge,
                                color = TextPrimary
                            )
                        }

                        is SyncState.Error -> {
                            Text(
                                text = "SYNC ERROR",
                                color = CrimsonPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.message,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Scare timeline
                    val totalDurationMs = (movie?.runtimeMinutes?.toLong() ?: 120L) * 60_000L
                    val currentMovieTime = when (val s = syncState) {
                        is SyncState.Locked -> s.currentMovieTimeMs
                        is SyncState.Verifying -> s.currentMovieTimeMs
                        else -> 0L
                    }

                    ScareTimeline(
                        scares = movie?.scares ?: emptyList(),
                        totalDurationMs = totalDurationMs,
                        currentTimeMs = currentMovieTime,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Bottom: Controls
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ManualSyncControls(
                        onAdjustOffset = { delta -> viewModel.adjustOffset(delta) },
                        onForceResync = { viewModel.forceResync() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.stop()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonDark, contentColor = TextPrimary),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                        Text(text = " STOP", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
