package dev.spookcue.ui.screens.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.spookcue.data.model.JumpScare
import dev.spookcue.sync.SyncState
import dev.spookcue.ui.components.PulseOverlay
import dev.spookcue.ui.components.ScareTimeline
import dev.spookcue.ui.theme.LunarBorder
import dev.spookcue.ui.theme.LunarSlate
import dev.spookcue.ui.theme.LunarSlateDim
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.LunarSurface
import dev.spookcue.ui.theme.LunarWhite
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.TheaterAmber
import dev.spookcue.ui.theme.TheaterAmberDim
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
    val isScreenOff by viewModel.isScreenOff.collectAsState()
    val revealedScareIds by viewModel.revealedScareIds.collectAsState()

    // Debug State Observers
    val rmsDb by viewModel.rmsDb.collectAsState()
    val speechDetected by viewModel.speechDetected.collectAsState()
    val liveTranscript by viewModel.liveTranscript.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val isDebugVisible by viewModel.isDebugVisible.collectAsState()

    LaunchedEffect(tmdbId) {
        viewModel.loadMovieAndStartSync(tmdbId)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
        }
    }

    // 1. OLED Screen Off (Stealth Mode)
    if (isScreenOff) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            viewModel.setScreenOff(false)
                        }
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Screen Off • Double-tap to wake",
                color = LunarSlateDim.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
        return
    }

    val allScares = movie?.scares ?: emptyList()
    val totalDurationMs = (movie?.runtimeMinutes?.toLong() ?: 120L) * 60_000L
    val currentMovieTime = when (val s = syncState) {
        is SyncState.Locked -> s.currentMovieTimeMs
        is SyncState.Verifying -> s.currentMovieTimeMs
        else -> 0L
    }

    val nextScare = allScares.firstOrNull { it.resolvedTimeMs > currentMovieTime } ?: allScares.firstOrNull()
    val remainingScares = if (nextScare != null) allScares.filter { it.id != nextScare.id } else allScares

    // 2. Active Theater Sync UI with systemBarsPadding
    PulseOverlay(isPulsing = isPulsing) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Main Viewport (Above the fold): Takes full screen height
            item {
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // TOP ROW: Back + Top-Left Movie Details + DEBUG & SCREEN OFF buttons
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = onNavigateBack,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = LunarSlateMuted
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Movie Details: Top left, smaller, dim grey
                                Column {
                                    Text(
                                        text = movie?.title?.uppercase() ?: "LOADING...",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = LunarSlateMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = movie?.let {
                                            val yr = if (it.releaseYear != null && it.releaseYear > 0) "${it.releaseYear} • " else ""
                                            "${yr}${it.runtimeMinutes} min • ${it.scares.size} scares"
                                        } ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LunarSlateDim
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Debug Toggle Button
                            OutlinedButton(
                                onClick = { viewModel.toggleDebugVisible() },
                                border = BorderStroke(1.dp, if (isDebugVisible) TheaterAmber else LunarBorder),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = LunarSurface,
                                    contentColor = if (isDebugVisible) TheaterAmber else LunarSlateDim
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (isDebugVisible) TheaterAmber else LunarSlateDim,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "DEBUG",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Boxed Screen Off button
                            OutlinedButton(
                                onClick = { viewModel.setScreenOff(true) },
                                border = BorderStroke(1.dp, LunarBorder),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = LunarSurface,
                                    contentColor = LunarSlate
                                ),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = LunarSlateMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "SCREEN OFF",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Real-Time Audio Transcription Debug Console
                        AnimatedVisibility(
                            visible = isDebugVisible,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            AudioDebugConsole(
                                rmsDb = rmsDb,
                                speechDetected = speechDetected,
                                isListening = isListening,
                                liveTranscript = liveTranscript,
                                debugInfo = debugInfo,
                                onSimulate = { viewModel.simulateSpeech(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                            )
                        }
                    }

                    // CENTER: Next Scare Countdown & Subtle Controls
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (val state = syncState) {
                            is SyncState.Idle -> {
                                Text(
                                    text = "INITIALIZING",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LunarSlateDim,
                                    letterSpacing = 1.sp
                                )
                            }

                            is SyncState.Acquiring -> {
                                CircularProgressIndicator(
                                    color = LunarSlateMuted,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Text(
                                    text = "LISTENING FOR DIALOGUE...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LunarSlate,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Candidates detected: ${state.candidateMatches} / 2",
                                    color = LunarSlateDim,
                                    fontSize = 12.sp
                                )
                            }

                            is SyncState.Locked -> {
                                if (state.nextScareCountdownMs != null && state.nextScareCountdownMs > 0) {
                                    Text(
                                        text = "NEXT SCARE IN",
                                        fontSize = 12.sp,
                                        color = LunarSlateDim,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.nextScareCountdownMs.formatCountdownMs(),
                                        style = MaterialTheme.typography.displayLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 54.sp
                                        ),
                                        color = if (state.nextScareCountdownMs <= 10_000L) TheaterAmber else LunarWhite
                                    )
                                } else {
                                    Text(
                                        text = "NO MORE SCARES DETECTED",
                                        color = LunarSlateMuted,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Movie Clock: ${state.currentMovieTimeMs.formatTimeMs()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LunarSlateDim
                                )
                            }

                            is SyncState.Verifying -> {
                                Text(
                                    text = "VERIFYING AUDIO SYNC",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LunarSlateMuted
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Movie Clock: ${state.currentMovieTimeMs.formatTimeMs()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LunarSlateDim
                                )
                            }

                            is SyncState.Error -> {
                                Text(
                                    text = "SYNC ERROR",
                                    color = TheaterAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.message,
                                    color = LunarSlateDim,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Compact Resync & Stop Actions
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.forceResync() },
                                border = BorderStroke(1.dp, LunarBorder),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = LunarSurface,
                                    contentColor = LunarSlate
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = LunarSlateMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Resync", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.stop()
                                    onNavigateBack()
                                },
                                border = BorderStroke(1.dp, LunarBorder),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = LunarSurface,
                                    contentColor = LunarSlateDim
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = LunarSlateDim,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // BOTTOM OF VIEWPORT: Progress Bar & Next Jump Scare Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ScareTimeline(
                            scares = allScares,
                            totalDurationMs = totalDurationMs,
                            currentTimeMs = currentMovieTime,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "00:00", fontSize = 10.sp, color = LunarSlateDim)
                            Text(
                                text = "${movie?.runtimeMinutes ?: 120} min",
                                fontSize = 10.sp,
                                color = LunarSlateDim
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // ONLY the next jump scare tab is shown at the bottom
                        if (nextScare != null) {
                            val isRevealed = revealedScareIds.contains(nextScare.id)
                            NextScareTab(
                                scare = nextScare,
                                isRevealed = isRevealed,
                                onToggleReveal = { viewModel.toggleScareRevealed(nextScare.id) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Scroll indicator cue if more scares exist
                        if (remainingScares.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Scroll for all scares",
                                    fontSize = 10.sp,
                                    color = LunarSlateDim.copy(alpha = 0.7f)
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = LunarSlateDim.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Below the Fold: The rest of the scares (scroll to see)
            if (remainingScares.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "OTHER JUMP SCARES (${remainingScares.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = LunarSlateMuted,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Tap row to reveal",
                                style = MaterialTheme.typography.labelSmall,
                                color = LunarSlateDim
                            )
                        }
                    }
                }

                items(remainingScares, key = { it.id }) { scare ->
                    val isRevealed = revealedScareIds.contains(scare.id)
                    SyncScareRow(
                        scare = scare,
                        isRevealed = isRevealed,
                        onToggleReveal = { viewModel.toggleScareRevealed(scare.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * Real-time audio transcription and phonetic matching debug inspector.
 */
@Composable
private fun AudioDebugConsole(
    rmsDb: Float,
    speechDetected: Boolean,
    isListening: Boolean,
    liveTranscript: String,
    debugInfo: dev.spookcue.sync.SyncDebugInfo,
    onSimulate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(LunarSurface, RoundedCornerShape(10.dp))
            .border(1.dp, LunarBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        // VU Audio Level Meter
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "AUDIO INPUT: ${rmsDb.toInt()} dB",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (speechDetected) TheaterAmber else LunarSlateMuted
            )
            Text(
                text = if (speechDetected) "VOICE DETECTED" else if (isListening) "LISTENING" else "MIC READY",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (speechDetected) TheaterAmber else LunarSlateDim
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Level bar
        val progress = (rmsDb / 90f).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = if (speechDetected) TheaterAmber else LunarSlateMuted,
            trackColor = PureBlack
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Live transcription stream
        val displayedText = if (liveTranscript.isNotBlank()) {
            liveTranscript
        } else if (debugInfo.lastText.isNotBlank()) {
            debugInfo.lastText
        } else {
            "Listening for dialogue around you..."
        }

        Text(
            text = "TRANSCRIPT: \"$displayedText\"",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = LunarWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Phonetic tokens & match score
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "TOKENS: ${debugInfo.tokens.ifEmpty { listOf("None") }.take(4).joinToString(", ")}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = LunarSlateDim
            )
            Text(
                text = "MATCH: ${(debugInfo.matchScore * 100).toInt()}% ${debugInfo.matchedTimeMs?.let { "at " + it.formatTimeMs() } ?: ""}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = if (debugInfo.matchScore > 0.7f) TheaterAmber else LunarSlateDim,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Dialogue Test Simulation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { onSimulate("Did you hear that noise upstairs?") },
                border = BorderStroke(1.dp, LunarBorder),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Speech #1", fontSize = 9.sp, color = LunarSlate)
            }

            OutlinedButton(
                onClick = { onSimulate("Look in the closet right now") },
                border = BorderStroke(1.dp, LunarBorder),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Speech #2", fontSize = 9.sp, color = LunarSlate)
            }
        }
    }
}

@Composable
private fun NextScareTab(
    scare: JumpScare,
    isRevealed: Boolean,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMajor = scare.type.equals("major", ignoreCase = true)

    Row(
        modifier = modifier
            .background(LunarSurface, RoundedCornerShape(10.dp))
            .border(1.dp, if (isMajor) TheaterAmber.copy(alpha = 0.5f) else LunarBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggleReveal)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (isMajor) TheaterAmberDim else LunarBorder,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = scare.resolvedTimeMs.formatTimeMs(),
                color = if (isMajor) TheaterAmber else LunarSlate,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Surface(
            color = if (isMajor) TheaterAmber.copy(alpha = 0.15f) else PureBlack,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, if (isMajor) TheaterAmber.copy(alpha = 0.4f) else LunarBorder)
        ) {
            Text(
                text = if (isMajor) "MAJOR SCARE" else "MINOR SCARE",
                color = if (isMajor) TheaterAmber else LunarSlateDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = if (isRevealed) {
                if (scare.description.isNotBlank()) scare.description else "No description"
            } else {
                "Next scare (tap to reveal hint)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isRevealed) LunarWhite else LunarSlateMuted,
            maxLines = if (isRevealed) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (isRevealed) "Hide" else "Reveal",
            tint = if (isRevealed) LunarSlate else LunarSlateDim,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SyncScareRow(
    scare: JumpScare,
    isRevealed: Boolean,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMajor = scare.type.equals("major", ignoreCase = true)

    Row(
        modifier = modifier
            .background(LunarSurface, RoundedCornerShape(8.dp))
            .border(1.dp, LunarBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleReveal)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (isMajor) TheaterAmberDim else LunarBorder,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = scare.resolvedTimeMs.formatTimeMs(),
                color = if (isMajor) TheaterAmber else LunarSlate,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = if (isMajor) TheaterAmber.copy(alpha = 0.15f) else PureBlack,
            shape = RoundedCornerShape(3.dp),
            border = BorderStroke(1.dp, if (isMajor) TheaterAmber.copy(alpha = 0.4f) else LunarBorder)
        ) {
            Text(
                text = if (isMajor) "MAJOR" else "MINOR",
                color = if (isMajor) TheaterAmber else LunarSlateDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = if (isRevealed) {
                if (scare.description.isNotBlank()) scare.description else "No description available"
            } else {
                "Hidden (tap to reveal)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isRevealed) LunarWhite else LunarSlateDim,
            maxLines = if (isRevealed) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (isRevealed) "Hide" else "Reveal",
            tint = if (isRevealed) LunarSlate else LunarSlateDim,
            modifier = Modifier.size(16.dp)
        )
    }
}
