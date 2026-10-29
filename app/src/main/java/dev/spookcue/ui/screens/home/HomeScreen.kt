package dev.spookcue.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import dev.spookcue.ui.components.FeaturedMovieCard
import dev.spookcue.ui.components.GhostLoadingIndicator
import dev.spookcue.ui.components.MovieCard
import dev.spookcue.ui.components.MovieDetailBottomSheet
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.LunarBorder
import dev.spookcue.ui.theme.LunarSlateDim
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.LunarSurface
import dev.spookcue.ui.theme.LunarWhite
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.TheaterAmber
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
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
    val listState = rememberLazyListState()

    // Anchor to top whenever initial loading completes or carousels are populated
    LaunchedEffect(uiState.isInitialLoading) {
        if (!uiState.isInitialLoading) {
            listState.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(uiState.theaterMovies.isNotEmpty()) {
        if (uiState.theaterMovies.isNotEmpty() && listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress) {
            listState.scrollToItem(0, 0)
        }
    }

    // 1. Optimized scroll lookahead prefetching (pauses during active flings, runs when settled)
    LaunchedEffect(listState, movies) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .debounce(250L)
            .collect { (isScrolling, firstIndex) ->
                if (!isScrolling && movies.isNotEmpty()) {
                    val lookAheadIndex = firstIndex + 2
                    val slice = movies.drop(lookAheadIndex).take(8)
                    viewModel.prefetchMovies(slice)
                }
            }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar("Catalog sync: $it")
        }
    }

    // 2. GPU-Accelerated Cinematic Transition (pre-composed Scaffold beneath overlay)
    val loadingAlpha by animateFloatAsState(
        targetValue = if (uiState.isInitialLoading) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "LoadingAlpha"
    )
    val loadingScale by animateFloatAsState(
        targetValue = if (uiState.isInitialLoading) 1f else 1.06f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "LoadingScale"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Base: Main Catalog Screen (always composed, rendered underneath with zero layout freeze)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = PureBlack,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "SPOOKCUE",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp
                            ),
                            color = LunarWhite
                        )
                    },
                    actions = {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                color = LunarWhite,
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(end = 6.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { viewModel.syncCatalog() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sync Catalog",
                                    tint = LunarSlateMuted
                                )
                            }
                        }

                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = LunarSlateMuted
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
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                // Dynamic Search Input Field
                val searchCount = if (uiState.totalMovieCount > 0) uiState.totalMovieCount else movies.size
                val placeholderText = if (searchCount > 0) {
                    "Search $searchCount horror movies..."
                } else {
                    "Search horror movies..."
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(placeholderText, color = LunarSlateMuted) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = LunarSlateDim
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = LunarSlateMuted
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LunarWhite,
                            unfocusedBorderColor = LunarBorder,
                            focusedTextColor = LunarWhite,
                            unfocusedTextColor = LunarWhite,
                            focusedContainerColor = LunarSurface,
                            unfocusedContainerColor = LunarSurface
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Main Content: Two Stacked Featured Carousels + Catalog List
                if (movies.isEmpty() && uiState.searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching movies found for \"${uiState.searchQuery}\"",
                            color = LunarSlateMuted,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = innerPadding.calculateBottomPadding() + 28.dp
                        )
                    ) {
                        // Two Stacked Featured Carousels (Visible when not actively searching)
                        if (uiState.searchQuery.isBlank()) {
                            // Shelf 1: NOW IN THEATERS & RECENT
                            if (uiState.theaterMovies.isNotEmpty()) {
                                item(key = "header_theater") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Movie,
                                            contentDescription = null,
                                            tint = CrimsonPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "NOW IN THEATERS & NEW",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            ),
                                            color = LunarWhite
                                        )
                                    }

                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uiState.theaterMovies, key = { "theater_${it.tmdbId}" }) { featuredMovie ->
                                            FeaturedMovieCard(
                                                movie = featuredMovie,
                                                isNew = true,
                                                onClick = { viewModel.onMovieClicked(featuredMovie) }
                                            )
                                        }
                                    }
                                }
                            }

                            // Shelf 2: POPULAR HOME WATCH
                            if (uiState.popularHomeMovies.isNotEmpty()) {
                                item(key = "header_home") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 2.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocalFireDepartment,
                                            contentDescription = null,
                                            tint = TheaterAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "POPULAR HOME WATCH",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            ),
                                            color = LunarWhite
                                        )
                                    }

                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uiState.popularHomeMovies, key = { "home_${it.tmdbId}" }) { featuredMovie ->
                                            FeaturedMovieCard(
                                                movie = featuredMovie,
                                                isNew = false,
                                                onClick = { viewModel.onMovieClicked(featuredMovie) }
                                            )
                                        }
                                    }
                                }
                            }

                            // Catalog Section Header
                            item(key = "header_all") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ALL MOVIES (${movies.size})",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        ),
                                        color = LunarSlateMuted
                                    )
                                }
                            }
                        }

                        // Vertical List of All Movies
                        items(movies, key = { it.tmdbId }) { movie ->
                            MovieCard(
                                movie = movie,
                                onClick = { viewModel.onMovieClicked(movie) },
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Overlay: Loading Screen with GPU-Accelerated Fade & Scale Out
        if (loadingAlpha > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = loadingAlpha
                        scaleX = loadingScale
                        scaleY = loadingScale
                    }
                    .background(PureBlack)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                GhostLoadingIndicator(
                    modifier = Modifier.fillMaxSize(),
                    statusText = "Summoning Horror Catalog...",
                    subText = "Caching posters & jump scare timestamps"
                )
            }
        }
    }

    // Movie Detail Bottom Sheet
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
