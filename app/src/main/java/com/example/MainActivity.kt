package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppTab
import com.example.ui.RadioWebViewModel
import com.example.ui.components.PersistentAudioPlayer
import com.example.ui.components.StreamErrorBanner
import com.example.ui.screens.CsvDataMetricsScreen
import com.example.ui.screens.GenresDirectoryScreen
import com.example.ui.screens.StationGridScreen
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                RadioWebApp()
            }
        }
    }
}

@Composable
fun RadioWebApp(
    viewModel: RadioWebViewModel = viewModel()
) {
    val isLoadingCatalog by viewModel.isLoadingCatalog.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val canonicalGenres by viewModel.canonicalGenres.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val catalogStationsState by viewModel.catalogStationsState.collectAsStateWithLifecycle()
    val favoriteStationsState by viewModel.favoriteStationsState.collectAsStateWithLifecycle()
    val currentStation by viewModel.currentStation.collectAsStateWithLifecycle()
    val playerStatus by viewModel.playerStatus.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val nowPlayingState by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val volumePercent by viewModel.volumePercent.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val csvFeedback by viewModel.csvOperationFeedback.collectAsStateWithLifecycle()
    val csvFeedbackIsError by viewModel.csvOperationIsError.collectAsStateWithLifecycle()
    val autoPlayCarBluetooth by viewModel.autoPlayCarBluetooth.collectAsStateWithLifecycle()

    // Lifecycle observer to pause live now-playing polling when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    viewModel.onAppForegroundChanged(true)
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.onAppForegroundChanged(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Back navigation from secondary tabs back to Catalogo
    BackHandler(enabled = selectedTab != AppTab.CATALOG) {
        viewModel.selectTab(AppTab.CATALOG)
    }

    // Document picker for .csv or .csv.gz catalog replacement
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadCustomCatalog(uri)
        }
    }

    val launchCsvPicker = {
        csvPickerLauncher.launch(
            arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "application/csv",
                "application/gzip",
                "application/x-gzip",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Slate950,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RadioWebTopHeaderAndTabs(
                selectedTab = selectedTab,
                favoritesCount = favoriteIds.size,
                totalStations = metrics.uniqueStations,
                genresCount = canonicalGenres.size,
                autoPlayCarBluetooth = autoPlayCarBluetooth,
                onToggleAutoPlayCarBluetooth = viewModel::toggleAutoPlayCarBluetooth,
                onSelectTab = viewModel::selectTab
            )
        },
        bottomBar = {
            PersistentAudioPlayer(
                station = currentStation,
                playerStatus = playerStatus,
                nowPlayingState = nowPlayingState,
                recordingState = recordingState,
                isFavorite = currentStation?.id in favoriteIds,
                volumePercent = volumePercent,
                isMuted = isMuted,
                onTogglePlayPause = viewModel::togglePlayPause,
                onToggleRecording = viewModel::toggleRecording,
                onDismissRecordingFeedback = viewModel::dismissRecordingFeedback,
                onStopStation = viewModel::stopAndClosePlayer,
                onToggleFavorite = {
                    currentStation?.id?.let { viewModel.toggleFavorite(it) }
                },
                onVolumeChange = viewModel::setVolumePercent,
                onToggleMute = viewModel::toggleMute,
                onGenreClick = { genre ->
                    viewModel.toggleGenreBadge(genre)
                    viewModel.selectTab(AppTab.CATALOG)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Slate950)
        ) {
            // Stream error banner at the top with Riprova button
            StreamErrorBanner(
                errorMessage = errorMessage,
                onRetry = viewModel::retryPlayback,
                onDismiss = viewModel::dismissErrorBanner
            )

            if (isLoadingCatalog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("loading_catalog_indicator"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Amber500,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Caricamento e indicizzazione del catalogo stazioni…",
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                        Text(
                            text = "Parsing RFC 4180, deduplicazione stream e ordinamento generi",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }
            } else {
                when (selectedTab) {
                    AppTab.CATALOG -> {
                        StationGridScreen(
                            isFavoritesTab = false,
                            stationsUiState = catalogStationsState,
                            favoriteIds = favoriteIds,
                            currentStation = currentStation,
                            playerStatus = playerStatus,
                            searchQuery = searchQuery,
                            selectedGenre = selectedGenre,
                            metrics = metrics,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onSelectGenre = viewModel::selectGenreFilter,
                            onToggleGenreBadge = viewModel::toggleGenreBadge,
                            onResetFilters = viewModel::resetFilters,
                            onStationCardClick = viewModel::onStationCardClicked,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onLoadMoreStations = viewModel::loadMoreStations,
                            onExploreCatalogClick = { viewModel.selectTab(AppTab.CATALOG) },
                            onUploadCsvClick = launchCsvPicker
                        )
                    }

                    AppTab.GENRES -> {
                        GenresDirectoryScreen(
                            canonicalGenres = canonicalGenres,
                            selectedGenre = selectedGenre,
                            onGenreSelected = viewModel::selectGenreFromGenresTab
                        )
                    }

                    AppTab.FAVORITES -> {
                        StationGridScreen(
                            isFavoritesTab = true,
                            stationsUiState = favoriteStationsState,
                            favoriteIds = favoriteIds,
                            currentStation = currentStation,
                            playerStatus = playerStatus,
                            searchQuery = searchQuery,
                            selectedGenre = selectedGenre,
                            metrics = metrics,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onSelectGenre = viewModel::selectGenreFilter,
                            onToggleGenreBadge = viewModel::toggleGenreBadge,
                            onResetFilters = viewModel::resetFilters,
                            onStationCardClick = viewModel::onStationCardClicked,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onLoadMoreStations = viewModel::loadMoreStations,
                            onExploreCatalogClick = { viewModel.selectTab(AppTab.CATALOG) },
                            onUploadCsvClick = launchCsvPicker
                        )
                    }

                    AppTab.CSV_DATA -> {
                        CsvDataMetricsScreen(
                            metrics = metrics,
                            feedbackMessage = csvFeedback,
                            isFeedbackError = csvFeedbackIsError,
                            onUploadCsvClick = launchCsvPicker,
                            onRestoreBundleClick = viewModel::restoreBundleCatalog
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioWebTopHeaderAndTabs(
    selectedTab: AppTab,
    favoritesCount: Int,
    totalStations: Int,
    genresCount: Int,
    autoPlayCarBluetooth: Boolean,
    onToggleAutoPlayCarBluetooth: () -> Unit,
    onSelectTab: (AppTab) -> Unit
) {
    Surface(
        color = Slate900,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // App Bar Brand Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Amber500,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Radio,
                                contentDescription = null,
                                tint = Slate950,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Text(
                        text = "RadioWeb",
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate100
                    )
                }

                // Quick toggle chip for Car Bluetooth Auto-Play
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (autoPlayCarBluetooth) LiveGreen.copy(alpha = 0.16f) else Slate800,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (autoPlayCarBluetooth) LiveGreen else Slate700
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleAutoPlayCarBluetooth() }
                        .testTag("car_bluetooth_autoplay_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DirectionsCar,
                            contentDescription = if (autoPlayCarBluetooth) {
                                "Avvio automatico Bluetooth Auto attivo"
                            } else {
                                "Avvio automatico Bluetooth Auto disattivato"
                            },
                            tint = if (autoPlayCarBluetooth) LiveGreen else Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (autoPlayCarBluetooth) "Auto BT: ON" else "Auto BT: OFF",
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (autoPlayCarBluetooth) LiveGreen else Slate400
                        )
                    }
                }
            }

            // 4 Top Navigation Tabs: Catalogo | Generi | Preferiti (N) | Dati CSV
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Slate900,
                contentColor = Slate100,
                edgePadding = 12.dp,
                indicator = { tabPositions ->
                    if (selectedTab.ordinal in tabPositions.indices) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            height = 3.dp,
                            color = Amber500
                        )
                    }
                },
                divider = {
                    HorizontalDivider(color = Slate800, thickness = 1.dp)
                }
            ) {
                AppTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val icon = when (tab) {
                        AppTab.CATALOG -> Icons.Filled.Radio
                        AppTab.GENRES -> Icons.Filled.LibraryMusic
                        AppTab.FAVORITES -> Icons.Filled.Favorite
                        AppTab.CSV_DATA -> Icons.Filled.Dataset
                    }
                    val badgeCount = when (tab) {
                        AppTab.CATALOG -> totalStations
                        AppTab.GENRES -> genresCount
                        AppTab.FAVORITES -> favoritesCount
                        AppTab.CSV_DATA -> null
                    }

                    Tab(
                        selected = isSelected,
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) Amber400 else Slate400,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) Amber400 else Slate300
                                )
                                if (badgeCount != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) Amber500 else Slate800
                                    ) {
                                        Text(
                                            text = badgeCount.toString(),
                                            fontFamily = MonoFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Slate950 else Slate300,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
