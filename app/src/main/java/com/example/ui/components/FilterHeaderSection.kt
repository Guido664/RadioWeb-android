package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CatalogMetrics
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.AmberContainerDark
import com.example.ui.theme.OnAmberContainerDark
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate900

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterHeaderSection(
    searchQuery: String,
    selectedGenre: String?,
    metrics: CatalogMetrics,
    onSearchQueryChange: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onResetFilters: () -> Unit,
    onUploadCsvClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasActiveFilters = searchQuery.isNotBlank() || selectedGenre != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Small catalog warning banner (<= ~65 rows)
        if (metrics.isSmallCatalog) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AmberContainerDark.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Amber500.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("small_catalog_warning_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = Amber400,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Catalogo di esempio attivo (${metrics.totalRowsRead} righe)",
                            style = MaterialTheme.typography.titleSmall,
                            color = OnAmberContainerDark
                        )
                        Text(
                            text = "Carica il file completo catalog_stations.csv o .csv.gz (~55.000 stazioni) per esplorare l'intero archivio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnAmberContainerDark.copy(alpha = 0.9f)
                        )
                    }
                    OutlinedButton(
                        onClick = onUploadCsvClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber400),
                        border = BorderStroke(1.dp, Amber500),
                        modifier = Modifier.testTag("banner_upload_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Carica CSV", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_input"),
            placeholder = {
                Text(
                    text = "Cerca per inizio nome, città, nazione o genere…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Cerca",
                    tint = Amber400
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.testTag("clear_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Cancella ricerca",
                            tint = Slate400
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate900,
                unfocusedContainerColor = Slate900,
                focusedBorderColor = Amber500,
                unfocusedBorderColor = Slate700,
                focusedTextColor = Slate100,
                unfocusedTextColor = Slate100,
                cursorColor = Amber500
            )
        )

        // Show active genre pill and Reset button only when filters are active
        if (hasActiveFilters) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedGenre != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmberContainerDark,
                        border = BorderStroke(1.dp, Amber500),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectGenre(null) }
                            .testTag("active_genre_filter_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Genere: $selectedGenre",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = OnAmberContainerDark
                            )
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Rimuovi filtro genere",
                                tint = Amber400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onResetFilters,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Slate900,
                        contentColor = Amber400
                    ),
                    border = BorderStroke(1.dp, Amber500.copy(alpha = 0.7f)),
                    modifier = Modifier.testTag("reset_filters_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterAltOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reset filtri",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
