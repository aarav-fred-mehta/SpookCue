package dev.spookcue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.spookcue.R
import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.ui.theme.CrimsonBorder
import dev.spookcue.ui.theme.CrimsonDark
import dev.spookcue.ui.theme.CrimsonLight
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.SurfaceDark
import dev.spookcue.ui.theme.TextPrimary
import dev.spookcue.ui.theme.TextSecondary
import dev.spookcue.util.formatLastSynced
import dev.spookcue.util.formatTimeMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailBottomSheet(
    movie: MovieCatalogItem,
    movieDetails: MovieDetails?,
    isLoadingDetails: Boolean,
    onStartSync: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }

    val imageRequest = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .crossfade(true)
            .placeholder(R.drawable.ic_skull)
            .error(R.drawable.ic_skull)
            .build()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        scrimColor = PureBlack.copy(alpha = 0.75f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = TextSecondary.copy(alpha = 0.4f)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Row: Poster + Title + Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PureBlack)
                        .border(1.dp, CrimsonBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (posterUrl != null) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = movie.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_skull),
                            contentDescription = "Skull placeholder",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CrimsonPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${movie.releaseYear} • ${movie.runtimeMinutes} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = CrimsonDark,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${movie.scareCount} JUMP SCARES",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Sync capability status
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (movie.hasSubtitles) Icons.Default.GraphicEq else Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = if (movie.hasSubtitles) CrimsonLight else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (movie.hasSubtitles) "Phonetic Dialogue Sync" else "Clock Sync Only",
                            fontSize = 11.sp,
                            color = if (movie.hasSubtitles) CrimsonLight else TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Last Synced Info
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Last synced: ${formatLastSynced(movie.lastSyncedAt)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = CrimsonBorder.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // Scare Timeline Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SCARE TIMELINE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                // Legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(CrimsonLight, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Major", fontSize = 10.sp, color = TextSecondary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(CrimsonPrimary, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Minor", fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val scares = movieDetails?.scares ?: emptyList()
            val totalDurationMs = movie.runtimeMinutes * 60_000L

            if (isLoadingDetails) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = CrimsonPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                ScareTimeline(
                    scares = scares,
                    totalDurationMs = totalDurationMs,
                    currentTimeMs = 0L,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Jump scare preview list
            Text(
                text = "UPCOMING SCARES PREVIEW (${scares.size})",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = TextSecondary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (scares.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scares, key = { it.id }) { scare ->
                        ScarePreviewRow(scare = scare)
                    }
                }
            } else if (!isLoadingDetails) {
                Text(
                    text = "No jump scare timestamps available",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action: Start Sync
            Button(
                onClick = onStartSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonPrimary,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "START SYNC",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun ScarePreviewRow(scare: JumpScare) {
    val isMajor = scare.type.equals("major", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PureBlack, RoundedCornerShape(6.dp))
            .border(1.dp, CrimsonBorder.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (isMajor) CrimsonLight else CrimsonDark,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = scare.resolvedTimeMs.formatTimeMs(),
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = if (isMajor) CrimsonDark else PureBlack,
            shape = RoundedCornerShape(3.dp)
        ) {
            Text(
                text = if (isMajor) "MAJOR" else "MINOR",
                color = if (isMajor) CrimsonLight else TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = scare.description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
