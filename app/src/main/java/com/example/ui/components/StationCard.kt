package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlayerStatus
import com.example.model.Station
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.AmberContainerDark
import com.example.ui.theme.ConnectingCyan
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.FavoriteRose
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.OnAmberContainerDark
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate900

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationCard(
    station: Station,
    isActiveStation: Boolean,
    playerStatus: PlayerStatus,
    isFavorite: Boolean,
    selectedGenre: String?,
    onCardClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderStroke = when {
        isActiveStation && playerStatus == PlayerStatus.ERROR -> BorderStroke(1.5.dp, ErrorRed)
        isActiveStation -> BorderStroke(1.5.dp, Amber500)
        else -> BorderStroke(1.dp, Slate800)
    }

    val containerColor = if (isActiveStation) Slate850 else Slate900

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("station_card_${station.id}")
            .semantics {
                contentDescription = "Stazione ${station.name}, ${station.locationLabel}"
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
                ) {
                    onCardClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(role = Role.Button, onClick = onCardClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = borderStroke,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActiveStation) 4.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StationLogo(
                    station = station,
                    size = LogoSize.MEDIUM,
                    isActiveStation = isActiveStation,
                    playerStatus = playerStatus,
                    showOverlay = true
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Single-line truncated station name
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isActiveStation) Amber400 else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Location + Format badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = station.locationLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        FormatBadge(
                            formatLabel = station.displayFormat,
                            isHls = station.isHls
                        )
                    }

                    // Active status indicator if this card is the active station
                    if (isActiveStation && playerStatus != PlayerStatus.IDLE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        ActiveStationStatusPill(playerStatus = playerStatus)
                    }
                }

                // Heart favorite button
                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("favorite_btn_${station.id}")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorite) {
                            "Rimuovi ${station.name} dai preferiti"
                        } else {
                            "Aggiungi ${station.name} ai preferiti"
                        },
                        tint = if (isFavorite) FavoriteRose else Slate400
                    )
                }
            }

            // Bottom row: first 4 genres as clickable badges + "+N" if more
            if (station.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                val displayedGenres = station.genres.take(4)
                val extraCount = (station.genres.size - 4).coerceAtLeast(0)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayedGenres.forEach { genre ->
                        val isGenreSelected = selectedGenre != null &&
                            selectedGenre.equals(genre, ignoreCase = true)

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isGenreSelected) AmberContainerDark else Slate800,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isGenreSelected) Amber500 else Slate700.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onGenreClick(genre) }
                                .testTag("card_genre_${station.id}_$genre")
                        ) {
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (isGenreSelected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isGenreSelected) OnAmberContainerDark else Slate300,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (extraCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate800.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Slate700.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "+$extraCount",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate400,
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
fun FormatBadge(
    formatLabel: String,
    isHls: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = if (isHls) AmberContainerDark else Slate800,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHls) Amber500.copy(alpha = 0.7f) else Slate700
        )
    ) {
        Text(
            text = formatLabel,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHls) Amber400 else Slate300,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ActiveStationStatusPill(playerStatus: PlayerStatus) {
    val (label, dotColor) = when (playerStatus) {
        PlayerStatus.CONNECTING -> "Connessione" to ConnectingCyan
        PlayerStatus.PLAYING -> "In riproduzione" to LiveGreen
        PlayerStatus.PAUSED -> "In pausa" to Amber400
        PlayerStatus.ERROR -> "Stream non disponibile" to ErrorRed
        PlayerStatus.IDLE -> "Pronto" to Slate400
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
