package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.model.CatalogMetrics
import com.example.model.PlayerStatus
import com.example.model.Station
import com.example.ui.FilteredStationsUiState
import com.example.ui.components.FilterHeaderSection
import com.example.ui.components.StationCard
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun StationGridScreen(
    isFavoritesTab: Boolean,
    stationsUiState: FilteredStationsUiState,
    favoriteIds: Set<String>,
    currentStation: Station?,
    playerStatus: PlayerStatus,
    searchQuery: String,
    selectedGenre: String?,
    metrics: CatalogMetrics,
    onSearchQueryChange: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onToggleGenreBadge: (String) -> Unit,
    onResetFilters: () -> Unit,
    onStationCardClick: (Station) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onLoadMoreStations: () -> Unit,
    onExploreCatalogClick: () -> Unit,
    onUploadCsvClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()

    // Infinite scroll trigger when reaching near the bottom of the current page
    val shouldAutoLoadMore by remember(gridState, stationsUiState.hasMorePages, stationsUiState.displayedStations.size) {
        derivedStateOf {
            if (!stationsUiState.hasMorePages || stationsUiState.displayedStations.isEmpty()) {
                false
            } else {
                val layoutInfo = gridState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleIndex >= totalItems - 6
            }
        }
    }

    LaunchedEffect(shouldAutoLoadMore) {
        if (shouldAutoLoadMore) {
            onLoadMoreStations()
        }
    }

    // Scroll to top when filter changes
    LaunchedEffect(searchQuery, selectedGenre, isFavoritesTab) {
        gridState.scrollToItem(0)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Spec: "Griglia responsive di card (1 colonna mobile, 2–4 su schermi più larghi)."
        val columns = when {
            maxWidth < 600.dp -> 1
            maxWidth < 880.dp -> 2
            maxWidth < 1200.dp -> 3
            else -> 4
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (isFavoritesTab) "favorites_station_grid" else "catalog_station_grid"),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header filter section
            item(
                span = { GridItemSpan(maxLineSpan) },
                key = "filter_header_section"
            ) {
                FilterHeaderSection(
                    searchQuery = searchQuery,
                    selectedGenre = selectedGenre,
                    metrics = metrics,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectGenre = onSelectGenre,
                    onResetFilters = onResetFilters,
                    onUploadCsvClick = onUploadCsvClick
                )
            }

            if (stationsUiState.displayedStations.isEmpty()) {
                item(
                    span = { GridItemSpan(maxLineSpan) },
                    key = "empty_state_card"
                ) {
                    EmptyStationsStateCard(
                        isFavoritesTab = isFavoritesTab,
                        hasNoFavoritesAtAll = isFavoritesTab && favoriteIds.isEmpty(),
                        onResetFilters = onResetFilters,
                        onExploreCatalogClick = onExploreCatalogClick,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                }
            } else {
                items(
                    items = stationsUiState.displayedStations,
                    key = { it.id }
                ) { station ->
                    Box(modifier = Modifier.padding(horizontal = if (columns == 1) 16.dp else 8.dp)) {
                        StationCard(
                            station = station,
                            isActiveStation = currentStation?.id == station.id,
                            playerStatus = if (currentStation?.id == station.id) playerStatus else PlayerStatus.IDLE,
                            isFavorite = station.id in favoriteIds,
                            selectedGenre = selectedGenre,
                            onCardClick = { onStationCardClick(station) },
                            onFavoriteToggle = { onToggleFavorite(station.id) },
                            onGenreClick = onToggleGenreBadge
                        )
                    }
                }

                // Explicit "Mostra altre" pagination button at the bottom
                if (stationsUiState.hasMorePages) {
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "load_more_footer"
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedButton(
                                onClick = onLoadMoreStations,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Slate900,
                                    contentColor = Amber400
                                ),
                                border = BorderStroke(1.dp, Amber500),
                                modifier = Modifier.testTag("load_more_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val remaining = stationsUiState.filteredCount - stationsUiState.displayedStations.size
                                Text(
                                    text = "Mostra altre stazioni ($remaining rimanenti)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStationsStateCard(
    isFavoritesTab: Boolean,
    hasNoFavoritesAtAll: Boolean,
    onResetFilters: () -> Unit,
    onExploreCatalogClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("empty_state_container"),
        shape = RoundedCornerShape(16.dp),
        color = Slate900,
        border = BorderStroke(1.dp, Slate800)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (hasNoFavoritesAtAll) Icons.Filled.FavoriteBorder else Icons.Filled.Radio,
                contentDescription = null,
                tint = Amber400,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = when {
                    hasNoFavoritesAtAll -> "Nessuna stazione preferita"
                    isFavoritesTab -> "Nessun preferito corrisponde ai filtri"
                    else -> "Nessuna stazione trovata"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Slate100,
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    hasNoFavoritesAtAll ->
                        "Tocca l'icona del cuore sulle card delle stazioni o nel player in basso per salvarle nei tuoi Preferiti."
                    else ->
                        "Nessuna stazione corrisponde alla ricerca o al genere selezionato. Prova ad azzerare i filtri attivi."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Slate400,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (hasNoFavoritesAtAll) {
                Button(
                    onClick = onExploreCatalogClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber500,
                        contentColor = Slate950
                    ),
                    modifier = Modifier.testTag("empty_explore_catalog_cta")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Esplora il catalogo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onResetFilters,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber400),
                    border = BorderStroke(1.dp, Amber500),
                    modifier = Modifier.testTag("empty_reset_filters_cta")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterAltOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset filtri",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
