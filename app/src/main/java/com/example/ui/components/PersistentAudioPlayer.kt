package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NowPlayingState
import com.example.model.PlayerStatus
import com.example.model.RecordingUiState
import com.example.model.Station
import com.example.ui.theme.Amber400
import com.example.ui.theme.Amber500
import com.example.ui.theme.ConnectingCyan
import com.example.ui.theme.ErrorContainerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.FavoriteRose
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun StreamErrorBanner(
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("stream_error_banner"),
            shape = RoundedCornerShape(12.dp),
            color = ErrorContainerDark,
            border = BorderStroke(1.dp, ErrorRed)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Errore di riproduzione",
                    tint = ErrorRed,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Errore di riproduzione",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFFECACA)
                    )
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFECACA).copy(alpha = 0.9f)
                    )
                }
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFECACA)
                    ),
                    border = BorderStroke(1.dp, ErrorRed),
                    modifier = Modifier.testTag("error_banner_retry_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Riprova", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Chiudi avviso errore",
                        tint = Color(0xFFFECACA)
                    )
                }
            }
        }
    }
}

@Composable
fun PersistentAudioPlayer(
    station: Station?,
    playerStatus: PlayerStatus,
    nowPlayingState: NowPlayingState,
    recordingState: RecordingUiState,
    isFavorite: Boolean,
    volumePercent: Int,
    isMuted: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleRecording: () -> Unit,
    onDismissRecordingFeedback: () -> Unit,
    onStopStation: () -> Unit,
    onToggleFavorite: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("persistent_audio_player"),
        color = Slate900,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            HorizontalDivider(color = Slate800, thickness = 1.dp)

            if (station == null) {
                // Idle bar when no station is selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Seleziona una stazione dal catalogo per avviare l'ascolto in diretta",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    val isWideScreen = maxWidth >= 640.dp

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Top Row: Small Logo + Station Info + Now Playing + Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StationLogo(
                                station = station,
                                size = LogoSize.SMALL,
                                isActiveStation = true,
                                playerStatus = playerStatus,
                                showOverlay = false
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Slate100,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                // Now-Playing live line directly under the station name
                                NowPlayingLine(
                                    playerStatus = playerStatus,
                                    nowPlayingState = nowPlayingState
                                )

                                // On wide screens, also show location and clickable genre badges
                                if (isWideScreen) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = station.locationLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate400
                                        )
                                        station.genres.take(4).forEach { genre ->
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Slate800,
                                                border = BorderStroke(1.dp, Slate700),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable { onGenreClick(genre) }
                                            ) {
                                                Text(
                                                    text = genre,
                                                    fontSize = 10.sp,
                                                    color = Slate300,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Play / Pause accented circular button
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Amber500)
                                    .clickable { onTogglePlayPause() }
                                    .testTag("player_play_pause_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (playerStatus == PlayerStatus.CONNECTING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Slate950,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (playerStatus == PlayerStatus.PLAYING) {
                                            Icons.Filled.Pause
                                        } else {
                                            Icons.Filled.PlayArrow
                                        },
                                        contentDescription = if (playerStatus == PlayerStatus.PLAYING) {
                                            "Metti in pausa"
                                        } else {
                                            "Riproduci"
                                        },
                                        tint = Slate950,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            // Record / Stop button: visible ONLY when a station is playing (or recording is active)
                            if (playerStatus == PlayerStatus.PLAYING || recordingState.isRecording) {
                                Spacer(modifier = Modifier.width(6.dp))

                                RecordStopButton(
                                    isRecording = recordingState.isRecording,
                                    formattedDuration = recordingState.formattedDuration,
                                    onClick = onToggleRecording
                                )
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            // Favorite heart button
                            IconButton(
                                onClick = onToggleFavorite,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_favorite_button")
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) {
                                        "Rimuovi dai preferiti"
                                    } else {
                                        "Aggiungi ai preferiti"
                                    },
                                    tint = if (isFavorite) FavoriteRose else Slate300
                                )
                            }

                            // Stop button (closes station)
                            IconButton(
                                onClick = onStopStation,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_stop_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "Ferma e chiudi stazione",
                                    tint = Slate300
                                )
                            }
                        }

                        // Active Recording Status or Last Recording Summary Banner
                        AnimatedVisibility(
                            visible = recordingState.isRecording || !recordingState.lastFeedbackMessage.isNullOrBlank()
                        ) {
                            RecordingStatusBanner(
                                recordingState = recordingState,
                                onDismissFeedback = onDismissRecordingFeedback
                            )
                        }

                        // Volume Slider Row (0-100%, mute button, visible monospace percentage)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("player_mute_button")
                            ) {
                                val volumeIcon = when {
                                    isMuted || volumePercent == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                                    volumePercent < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                    else -> Icons.AutoMirrored.Filled.VolumeUp
                                }
                                Icon(
                                    imageVector = volumeIcon,
                                    contentDescription = if (isMuted) "Riattiva audio" else "Muta audio",
                                    tint = if (isMuted) ErrorRed else Amber400,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Slider(
                                value = if (isMuted) 0f else volumePercent.toFloat(),
                                onValueChange = { onVolumeChange(it.toInt()) },
                                valueRange = 0f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .testTag("player_volume_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber500,
                                    activeTrackColor = Amber500,
                                    inactiveTrackColor = Slate700
                                )
                            )

                            Text(
                                text = if (isMuted) "MUTO" else "$volumePercent%",
                                fontFamily = MonoFontFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMuted) ErrorRed else Slate300,
                                modifier = Modifier
                                    .width(44.dp)
                                    .testTag("player_volume_label")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordStopButton(
    isRecording: Boolean,
    formattedDuration: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (isRecording) ErrorRed else Slate800,
        border = BorderStroke(
            width = 1.5.dp,
            color = ErrorRed
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .testTag("player_record_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = if (isRecording) {
                    "Ferma registrazione ($formattedDuration)"
                } else {
                    "Avvia registrazione sessione"
                },
                tint = if (isRecording) Color.White else ErrorRed,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (isRecording) formattedDuration else "REC",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) Color.White else Slate100
            )
        }
    }
}

@Composable
private fun RecordingStatusBanner(
    recordingState: RecordingUiState,
    onDismissFeedback: () -> Unit
) {
    val isActive = recordingState.isRecording
    val isError = recordingState.isErrorFeedback
    val borderColor = when {
        isActive || isError -> ErrorRed
        else -> LiveGreen.copy(alpha = 0.7f)
    }
    val containerColor = when {
        isActive || isError -> ErrorContainerDark.copy(alpha = 0.75f)
        else -> Slate800
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player_recording_status_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when {
                    isActive -> Icons.Filled.FiberManualRecord
                    isError -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    isActive || isError -> ErrorRed
                    else -> LiveGreen
                },
                modifier = Modifier.size(16.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isActive) {
                        "REC ${recordingState.formattedDuration} • ${recordingState.fileName.orEmpty()} (in Radioweb record/${recordingState.genreFolder.orEmpty()}/)"
                    } else {
                        recordingState.lastFeedbackMessage.orEmpty()
                    },
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate100,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Uso personale offline • Non distribuire • File singolo dell'intera sessione (non tagliato per brano)",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = Slate300,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isActive) {
                IconButton(
                    onClick = onDismissFeedback,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Chiudi riepilogo registrazione",
                        tint = Slate300,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingLine(
    playerStatus: PlayerStatus,
    nowPlayingState: NowPlayingState
) {
    val label = when {
        playerStatus == PlayerStatus.PLAYING || playerStatus == PlayerStatus.CONNECTING -> {
            when (nowPlayingState) {
                is NowPlayingState.Loading, is NowPlayingState.Idle -> "Caricamento brano…"
                is NowPlayingState.Available -> nowPlayingState.title
                is NowPlayingState.Unavailable -> "Brano non disponibile"
            }
        }
        nowPlayingState is NowPlayingState.Available -> nowPlayingState.title
        else -> "Premi Play per ascoltare lo stream live"
    }

    val isSongHighlighted = nowPlayingState is NowPlayingState.Available

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (isSongHighlighted) Amber400 else Slate400,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isSongHighlighted) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (isSongHighlighted) Amber400 else Slate300,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("now_playing_text")
        )
    }
}
