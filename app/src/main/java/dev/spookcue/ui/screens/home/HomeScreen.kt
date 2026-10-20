package dev.spookcue.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.spookcue.ui.components.MovieCard
import dev.spookcue.ui.components.MovieDetailBottomSheet
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.SurfaceDark
import dev.spookcue.ui.theme.TextPrimary
import dev.spookcue.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMovieSelected: (tmdbId: Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val movies by viewModel.movies.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar("Catalog sync error: $it")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = PureBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SPOOKCUE",
                        style = MaterialTheme.typography.titleLarge,
                        color = CrimsonPrimary
                    )
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            color = CrimsonPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.syncCatalog() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Catalog",
                                tint = TextSecondary
                            )
                        }
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PureBlack
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search horror movies...", color = TextSecondary) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CrimsonPrimary,
                    unfocusedBorderColor = SurfaceDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (movies.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.searchQuery.isBlank()) "No movies loaded yet" else "No matching movies found",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(movies, key = { it.tmdbId }) { movie ->
                        MovieCard(
                            movie = movie,
                            onClick = { viewModel.onMovieClicked(movie) },
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    // Task 3.1.5: Movie detail bottom sheet showing scare timeline, start button, "last synced" info
    if (uiState.selectedMovie != null) {
        val selected = uiState.selectedMovie!!
        MovieDetailBottomSheet(
            movie = selected,
            movieDetails = uiState.selectedMovieDetails,
            isLoadingDetails = uiState.isLoadingDetails,
            onStartSync = {
                viewModel.onStartSync(selected, onMovieSelected)
            },
            onDismiss = {
                viewModel.onDismissBottomSheet()
            }
        )
    }
}
