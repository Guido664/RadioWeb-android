package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlayerStatus
import com.example.model.Station
import com.example.ui.theme.Amber500
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

enum class LogoSize(val dpSize: Dp, val cornerRadius: Dp) {
    SMALL(46.dp, 10.dp),
    MEDIUM(64.dp, 14.dp),
    LARGE(88.dp, 18.dp)
}

@Composable
fun StationLogo(
    station: Station,
    size: LogoSize,
    modifier: Modifier = Modifier,
    isActiveStation: Boolean = false,
    playerStatus: PlayerStatus = PlayerStatus.IDLE,
    showOverlay: Boolean = false
) {
    val logos = station.availableLogos
    var currentLogoIndex by remember(station.id, logos) { mutableIntStateOf(0) }
    var allFailed by remember(station.id, logos) { mutableStateOf(logos.isEmpty()) }

    val shape = RoundedCornerShape(size.cornerRadius)
    val borderColor = if (isActiveStation) Amber500 else Slate700.copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .size(size.dpSize)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Slate800, Slate900)
                )
            )
            .border(
                width = if (isActiveStation) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!allFailed && currentLogoIndex in logos.indices) {
            val activeUrl = logos[currentLogoIndex]
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(activeUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Logo ${station.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (currentLogoIndex + 1 < logos.size) {
                        currentLogoIndex += 1
                    } else {
                        allFailed = true
                    }
                }
            )
        } else {
            // Fallback placeholder with radio icon + initials of first 2 words
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Radio,
                    contentDescription = null,
                    tint = Amber500.copy(alpha = 0.85f),
                    modifier = Modifier.size(
                        when (size) {
                            LogoSize.SMALL -> 16.dp
                            LogoSize.MEDIUM -> 22.dp
                            LogoSize.LARGE -> 28.dp
                        }
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = station.initials,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (size) {
                        LogoSize.SMALL -> 10.sp
                        LogoSize.MEDIUM -> 12.sp
                        LogoSize.LARGE -> 15.sp
                    },
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (showOverlay) {
            val overlayAlpha = if (isActiveStation) 0.58f else 0.32f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = overlayAlpha)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isActiveStation && playerStatus == PlayerStatus.CONNECTING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Amber500,
                            strokeWidth = 2.5.dp
                        )
                    }
                    isActiveStation && playerStatus == PlayerStatus.PLAYING -> {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "In riproduzione — metti in pausa",
                            tint = Amber500,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    isActiveStation && playerStatus == PlayerStatus.ERROR -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Errore di riproduzione",
                            tint = ErrorRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Avvia riproduzione",
                            tint = if (isActiveStation) Amber500 else Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
