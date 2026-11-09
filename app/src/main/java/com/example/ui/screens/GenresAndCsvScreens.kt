package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CatalogMetrics
import com.example.model.GenreItem
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.AmberContainerDark
import com.example.ui.theme.ErrorContainerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.OnAmberContainerDark
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun GenresDirectoryScreen(
    canonicalGenres: List<GenreItem>,
    selectedGenre: String?,
    onGenreSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var genreFilterQuery by remember { mutableStateOf("") }

    val filteredGenres = remember(canonicalGenres, genreFilterQuery) {
        if (genreFilterQuery.isBlank()) {
            canonicalGenres
        } else {
            val q = genreFilterQuery.trim().lowercase()
            canonicalGenres.filter { it.name.lowercase().contains(q) }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = when {
            maxWidth < 500.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 4
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag("genres_grid"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Generi Canonici (${canonicalGenres.size})",
                                style = MaterialTheme.typography.titleLarge,
                                color = Slate100
                            )
                            Text(
                                text = "Tocca un genere per filtrare il Catalogo stazioni",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                    }

                    OutlinedTextField(
                        value = genreFilterQuery,
                        onValueChange = { genreFilterQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("genre_screen_search_input"),
                        placeholder = {
                            Text(
                                text = "Filtra elenco generi…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate400
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Filtra generi",
                                tint = Amber400
                            )
                        },
                        trailingIcon = {
                            if (genreFilterQuery.isNotEmpty()) {
                                IconButton(onClick = { genreFilterQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Cancella filtro generi",
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
                            unfocusedTextColor = Slate100
                        )
                    )
                }
            }

            items(
                items = filteredGenres,
                key = { it.name }
            ) { genreItem ->
                val isSelected = selectedGenre != null &&
                    selectedGenre.equals(genreItem.name, ignoreCase = true)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) AmberContainerDark else Slate900,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) Amber500 else Slate800
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onGenreSelected(genreItem.name) }
                        .testTag("genre_item_${genreItem.name}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LibraryMusic,
                                contentDescription = null,
                                tint = if (isSelected) Amber400 else Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = genreItem.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) OnAmberContainerDark else Slate100,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) Amber500 else Slate800
                        ) {
                            Text(
                                text = genreItem.count.toString(),
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Slate950 else Amber400,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CsvDataMetricsScreen(
    metrics: CatalogMetrics,
    feedbackMessage: String?,
    isFeedbackError: Boolean = false,
    onUploadCsvClick: () -> Unit,
    onRestoreBundleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("csv_data_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, Slate800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = Amber500,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text(
                            text = "Gestione Catalogo & Dati CSV",
                            style = MaterialTheme.typography.titleLarge,
                            color = Slate100
                        )
                        Text(
                            text = if (metrics.isCustomCatalog) {
                                "Sorgente attiva: Catalogo personalizzato salvato sul dispositivo"
                            } else {
                                "Sorgente attiva: Catalogo di bundle dell'applicazione"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Slate800,
                    border = BorderStroke(1.dp, Slate700),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "File attivo:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate300
                        )
                        Text(
                            text = metrics.activeFileName,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Amber400,
                            modifier = Modifier.testTag("active_csv_filename")
                        )
                    }
                }

                if (!feedbackMessage.isNullOrBlank()) {
                    val containerColor = if (isFeedbackError) {
                        ErrorContainerDark
                    } else {
                        LiveGreen.copy(alpha = 0.14f)
                    }
                    val borderColor = if (isFeedbackError) {
                        ErrorRed
                    } else {
                        LiveGreen.copy(alpha = 0.5f)
                    }
                    val iconTint = if (isFeedbackError) ErrorRed else LiveGreen
                    val textColor = if (isFeedbackError) Color(0xFFFECACA) else Slate100

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = containerColor,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("csv_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isFeedbackError) {
                                    Icons.Filled.ErrorOutline
                                } else {
                                    Icons.Filled.CheckCircle
                                },
                                contentDescription = if (isFeedbackError) "Errore CSV" else "Operazione completata",
                                tint = iconTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = feedbackMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor
                            )
                        }
                    }
                }

                // Action buttons: Carica CSV + Ripristina catalogo di bundle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onUploadCsvClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber500,
                            contentColor = Slate950
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("upload_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Carica CSV (.csv / .gz)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onRestoreBundleClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Slate100
                        ),
                        border = BorderStroke(1.dp, Slate700),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("restore_bundle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ripristina catalogo di bundle",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // Metrics Grid
        Text(
            text = "Metriche del Catalogo Caricato",
            style = MaterialTheme.typography.titleMedium,
            color = Slate100
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                title = "Stazioni uniche (VALID)",
                value = metrics.uniqueStations.toString(),
                subtitle = "Deduplicate per id",
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                title = "Righe totali lette",
                value = metrics.totalRowsRead.toString(),
                subtitle = "Record nel file CSV",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                title = "Duplicati filtrati",
                value = metrics.duplicatesFiltered.toString(),
                subtitle = "Stream secondari per stesso id",
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                title = "Numero generi",
                value = metrics.genresCount.toString(),
                subtitle = "Generi canonici & estratti",
                modifier = Modifier.weight(1f)
            )
        }

        // In-App README / Limiti dichiarati per la registrazione
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, Slate800),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recording_limits_info_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = Amber400,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Registrazione Sessioni (REC/Stop) • Note & Limiti",
                        style = MaterialTheme.typography.titleMedium,
                        color = Slate100
                    )
                }

                Text(
                    text = "• Uso personale offline: la registrazione è destinata esclusivamente all'uso personale offline; non distribuire i file registrati.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate300
                )
                Text(
                    text = "• Sessione come singolo file: ogni sessione viene salvata come un unico file continuo (non tagliato per singolo brano) e contiene tutto l'audio dalla pressione di REC a quella di Stop nel formato originale dello stream (.mp3, .aac, .ogg).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate300
                )
                Text(
                    text = "• Cartella di salvataggio: Music/Radioweb record/<Primo Genere>/<NomeStazione> <yyyyMMdd-HHmm>.<ext> (oppure \"Generi non specificati\" se la stazione non ha generi).",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = Amber400
                )
            }
        }
    }
}

@Composable
private fun MetricStatCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = BorderStroke(1.dp, Slate800)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Analytics,
                    contentDescription = null,
                    tint = Amber400,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate300,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                fontFamily = MonoFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Amber400
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = Slate400
            )
        }
    }
}
