package com.example.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.model.PlayerStatus
import com.example.model.Station
import com.example.network.NowPlayingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@OptIn(UnstableApi::class)
class RadioPlayerController(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val CONNECTION_TIMEOUT_MS = 16_000L
        const val STREAM_METADATA_MAX_AGE_MS = 240_000L
        const val MAX_CAR_QUEUE_SIZE = 45
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; RadioWeb/1.0) ExoPlayerLib/2.19"

        /**
         * Builds the 3-line MediaMetadata for Car displays (Bluetooth AVRCP & Android Auto):
         * - Line 1 (title & displayTitle): Station Name (e.g. "RADIO LAC")
         * - Line 2 (artist & subtitle): Live Song Title (e.g. "Artist - Song"), or playback status when no song is available
         * - Line 3 (albumTitle & description & albumArtist): City, Country (e.g. "Geneva, Switzerland")
         */
        fun buildCarDisplayMediaMetadata(
            station: Station,
            songTitle: String?,
            playerStatus: PlayerStatus,
            artworkBytes: ByteArray? = null
        ): MediaMetadata {
            val cleanSong = songTitle?.trim()?.takeIf { it.isNotEmpty() }
            val locationLine = station.locationLabel.ifBlank { "RadioWeb" }

            val secondLine = when {
                playerStatus == PlayerStatus.PAUSED && cleanSong != null -> "In pausa • $cleanSong"
                playerStatus == PlayerStatus.PAUSED -> "In pausa"
                !cleanSong.isNullOrEmpty() -> cleanSong
                playerStatus == PlayerStatus.CONNECTING -> "Connessione in corso…"
                else -> "In diretta"
            }

            val builder = MediaMetadata.Builder()
                // Line 1: Nome della radio
                .setTitle(station.name)
                .setDisplayTitle(station.name)
                // Line 2: Titolo del brano in onda (o stato riproduzione)
                .setArtist(secondLine)
                .setSubtitle(secondLine)
                // Line 3: Città, Nazione (sia per AVRCP ALBUM che per Android Auto DISPLAY_DESCRIPTION)
                .setAlbumTitle(locationLine)
                .setDescription(locationLine)
                .setAlbumArtist(locationLine)
                .setStation(station.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

            if (station.preferredLogo.isNotBlank()) {
                builder.setArtworkUri(Uri.parse(station.preferredLogo))
            }

            if (artworkBytes != null) {
                builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }

            return builder.build()
        }
    }

    private val appContext = context.applicationContext
    private val imageLoader = ImageLoader(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var exoPlayer: ExoPlayer? = null
    private var bluetoothForwardingPlayer: BluetoothMetadataForwardingPlayer? = null

    private var connectionTimeoutJob: Job? = null
    private var streamMetadataStaleJob: Job? = null
    private var artworkLoadJob: Job? = null
    private var isVoluntaryStop = false

    private val _currentStation = MutableStateFlow<Station?>(null)
    val currentStation: StateFlow<Station?> = _currentStation.asStateFlow()

    private val _playerStatus = MutableStateFlow(PlayerStatus.IDLE)
    val playerStatus: StateFlow<PlayerStatus> = _playerStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _streamMetadataTitle = MutableStateFlow<String?>(null)
    val streamMetadataTitle: StateFlow<String?> = _streamMetadataTitle.asStateFlow()

    private var hasReceivedInStreamMetadataForCurrentStation = false

    // Active song title coming either from api.radio.de or ICY/HLS stream metadata
    private var resolvedSongTitle: String? = null
    private var currentArtworkBytes: ByteArray? = null

    // Current window of stations loaded into ExoPlayer's Timeline for Car Bluetooth Queue ("Sfoglia")
    @Volatile
    private var currentQueueStations: List<Station> = emptyList()

    var queueStationsProvider: (() -> List<Station>)? = null

    private val _volumePercent = MutableStateFlow(80)
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var volumeBeforeMute = 80

    var onSkipToPreviousStation: (() -> Unit)? = null
    var onSkipToNextStation: (() -> Unit)? = null

    init {
        RadioPlaybackService.onTogglePlayPauseCallback = {
            togglePlayPause()
        }
        RadioPlaybackService.onStopCallback = {
            stopAndCloseStation()
        }
        RadioPlaybackService.onPreviousCallback = {
            onSkipToPreviousStation?.invoke()
        }
        RadioPlaybackService.onNextCallback = {
            onSkipToNextStation?.invoke()
        }
        // Eagerly create player on main thread so Bluetooth MediaLibraryService always has a live session
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                getOrCreatePlayer()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * ForwardingPlayer that guarantees Bluetooth AVRCP and Android Auto MediaSession always receive
     * all 3 lines of metadata:
     * - Line 1 (title / displayTitle) = Station Name
     * - Line 2 (artist / subtitle) = Current Song Title (or live status)
     * - Line 3 (albumTitle / description / albumArtist) = City, Country (locationLabel)
     * - Artwork (artworkData / artworkUri) = Station Logo
     * - << and >> car buttons mapped to Previous / Next radio station
     * - Multi-station Timeline Queue so the car's "Sfoglia" (Now Playing List / Browse) works
     */
    private inner class BluetoothMetadataForwardingPlayer(
        player: ExoPlayer
    ) : ForwardingPlayer(player) {

        private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()
        private val listenerWrappers = ConcurrentHashMap<Player.Listener, Player.Listener>()

        @Volatile
        private var customMetadata: MediaMetadata = buildCustomMediaMetadata()

        override fun addListener(listener: Player.Listener) {
            sessionListeners.add(listener)
            val wrapper = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    listener.onEvents(this@BluetoothMetadataForwardingPlayer, events)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    val fresh = buildCustomMediaMetadata()
                    customMetadata = fresh
                    listener.onTimelineChanged(getCurrentTimeline(), reason)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val fresh = buildCustomMediaMetadata()
                    customMetadata = fresh
                    listener.onMediaItemTransition(getCurrentMediaItem(), reason)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    listener.onTracksChanged(tracks)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    // Always forward our 3-line customMetadata instead of ExoPlayer's raw stream MediaMetadata
                    val fresh = buildCustomMediaMetadata()
                    customMetadata = fresh
                    listener.onMediaMetadataChanged(fresh)
                }

                override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
                    listener.onPlaylistMetadataChanged(getPlaylistMetadata())
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    listener.onIsLoadingChanged(isLoading)
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(getAvailableCommands())
                }

                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    listener.onTrackSelectionParametersChanged(parameters)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener.onPlaybackStateChanged(playbackState)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    listener.onPlayWhenReadyChanged(playWhenReady, reason)
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener.onIsPlayingChanged(isPlaying)
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    listener.onRepeatModeChanged(repeatMode)
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
                }

                override fun onPlayerError(error: PlaybackException) {
                    listener.onPlayerError(error)
                }

                override fun onPlayerErrorChanged(error: PlaybackException?) {
                    listener.onPlayerErrorChanged(error)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    listener.onPlaybackParametersChanged(playbackParameters)
                }

                override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
                    listener.onSeekBackIncrementChanged(seekBackIncrementMs)
                }

                override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
                    listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
                }

                override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {
                    listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
                }

                override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {
                    listener.onAudioAttributesChanged(audioAttributes)
                }

                override fun onVolumeChanged(volume: Float) {
                    listener.onVolumeChanged(volume)
                }

                override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                    listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
                }

                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    listener.onDeviceInfoChanged(deviceInfo)
                }

                override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                    listener.onDeviceVolumeChanged(volume, muted)
                }

                override fun onMetadata(metadata: Metadata) {
                    listener.onMetadata(metadata)
                }
            }
            listenerWrappers[listener] = wrapper
            wrappedPlayer.addListener(wrapper)
        }

        override fun removeListener(listener: Player.Listener) {
            sessionListeners.remove(listener)
            val wrapper = listenerWrappers.remove(listener)
            if (wrapper != null) {
                wrappedPlayer.removeListener(wrapper)
            } else {
                super.removeListener(listener)
            }
        }

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasNextMediaItem(): Boolean = true

        override fun hasPreviousMediaItem(): Boolean = true

        override fun seekToNext() {
            onSkipToNextStation?.invoke()
        }

        override fun seekToNextMediaItem() {
            onSkipToNextStation?.invoke()
        }

        override fun seekForward() {
            onSkipToNextStation?.invoke()
        }

        override fun seekToPrevious() {
            onSkipToPreviousStation?.invoke()
        }

        override fun seekToPreviousMediaItem() {
            onSkipToPreviousStation?.invoke()
        }

        override fun seekBack() {
            onSkipToPreviousStation?.invoke()
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            val target = currentQueueStations.getOrNull(mediaItemIndex)
            if (target != null) {
                playStation(target)
            } else {
                super.seekToDefaultPosition(mediaItemIndex)
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            val target = currentQueueStations.getOrNull(mediaItemIndex)
            if (target != null && (target.id != _currentStation.value?.id || _playerStatus.value != PlayerStatus.PLAYING)) {
                playStation(target)
            } else {
                super.seekTo(mediaItemIndex, positionMs)
            }
        }

        private fun handleExternalMediaItemsRequest(mediaItems: List<MediaItem>, startIndex: Int): Boolean {
            val targetItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull() ?: return false
            val stationId = targetItem.mediaId.removePrefix(RadioPlaybackService.PREFIX_STATION)
            if (stationId.isBlank()) return false

            val current = _currentStation.value
            if (current?.id == stationId &&
                (_playerStatus.value == PlayerStatus.PLAYING || _playerStatus.value == PlayerStatus.CONNECTING)
            ) {
                return true
            }
            val playById = RadioPlaybackService.onPlayStationByIdCallback
            if (playById != null) {
                playById.invoke(stationId)
                return true
            }
            val queueTarget = currentQueueStations.firstOrNull { it.id == stationId }
            if (queueTarget != null) {
                playStation(queueTarget)
                return true
            }
            return false
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            if (!handleExternalMediaItemsRequest(mediaItems, 0)) {
                super.setMediaItems(mediaItems, resetPosition)
            }
        }

        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {
            if (!handleExternalMediaItemsRequest(mediaItems, startIndex)) {
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
            }
        }

        override fun setMediaItem(mediaItem: MediaItem) {
            if (!handleExternalMediaItemsRequest(listOf(mediaItem), 0)) {
                super.setMediaItem(mediaItem)
            }
        }

        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
            if (!handleExternalMediaItemsRequest(listOf(mediaItem), 0)) {
                super.setMediaItem(mediaItem, startPositionMs)
            }
        }

        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
            if (!handleExternalMediaItemsRequest(listOf(mediaItem), 0)) {
                super.setMediaItem(mediaItem, resetPosition)
            }
        }

        override fun getMediaMetadata(): MediaMetadata {
            return customMetadata
        }

        override fun getPlaylistMetadata(): MediaMetadata {
            return MediaMetadata.Builder()
                .setTitle("RadioWeb")
                .setDisplayTitle("RadioWeb - Stazioni Radio")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        }

        override fun getCurrentMediaItem(): MediaItem? {
            val base = super.getCurrentMediaItem()
            val station = _currentStation.value ?: return base
            return (base?.buildUpon() ?: MediaItem.Builder()
                .setMediaId("${RadioPlaybackService.PREFIX_STATION}${station.id}")
                .setUri(station.streamUrl))
                .setMediaMetadata(customMetadata)
                .build()
        }

        override fun getCurrentTimeline(): Timeline {
            val baseTimeline = super.getCurrentTimeline()
            if (baseTimeline.isEmpty) return baseTimeline
            val activeIndex = currentMediaItemIndex
            val activeItem = getCurrentMediaItem()
            return object : ForwardingTimeline(baseTimeline) {
                override fun getWindow(
                    windowIndex: Int,
                    window: Window,
                    defaultPositionProjectionUs: Long
                ): Window {
                    val w = super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                    if (windowIndex == activeIndex && activeItem != null) {
                        w.mediaItem = activeItem
                    }
                    return w
                }
            }
        }

        override fun play() {
            val status = _playerStatus.value
            val station = _currentStation.value
            if (station != null && (status == PlayerStatus.IDLE || status == PlayerStatus.ERROR || wrappedPlayer.playbackState == Player.STATE_IDLE)) {
                playStation(station)
            } else {
                super.play()
            }
        }

        override fun stop() {
            stopAndCloseStation()
        }

        fun refreshAndBroadcastMetadata() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { refreshAndBroadcastMetadata() }
                return
            }
            val newMetadata = buildCustomMediaMetadata()
            customMetadata = newMetadata
            val timeline = getCurrentTimeline()
            val events = Player.Events(
                FlagSet.Builder()
                    .add(Player.EVENT_TIMELINE_CHANGED)
                    .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                    .build()
            )
            for (listener in sessionListeners) {
                if (!timeline.isEmpty) {
                    listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
                listener.onMediaMetadataChanged(newMetadata)
                listener.onEvents(this, events)
            }
        }
    }

    private fun buildCustomMediaMetadata(): MediaMetadata {
        val station = _currentStation.value ?: return MediaMetadata.EMPTY
        val song = resolvedSongTitle?.takeIf { it.isNotBlank() }
            ?: _streamMetadataTitle.value?.takeIf { it.isNotBlank() }

        return buildCarDisplayMediaMetadata(
            station = station,
            songTitle = song,
            playerStatus = _playerStatus.value,
            artworkBytes = currentArtworkBytes
        )
    }

    private fun buildQueueWindowAround(targetStation: Station): List<Station> {
        val sourceList = queueStationsProvider?.invoke().orEmpty()
        if (sourceList.isEmpty()) {
            return listOf(targetStation)
        }
        val idx = sourceList.indexOfFirst { it.id == targetStation.id }
        if (idx == -1) {
            return listOf(targetStation) + sourceList.take(MAX_CAR_QUEUE_SIZE - 1)
        }
        if (sourceList.size <= MAX_CAR_QUEUE_SIZE) {
            return sourceList
        }
        val halfBefore = 10
        val start = (idx - halfBefore).coerceAtLeast(0)
        val end = (start + MAX_CAR_QUEUE_SIZE).coerceAtMost(sourceList.size)
        val adjustedStart = (end - MAX_CAR_QUEUE_SIZE).coerceAtLeast(0)
        return sourceList.subList(adjustedStart, end)
    }

    private fun stationToQueueMediaItem(station: Station, isCurrent: Boolean): MediaItem {
        // IMPORTANT: For the active playing item (isCurrent = true), do NOT set static title/artist
        // on ExoPlayer's raw MediaItem; otherwise Media3 refuses to overwrite title with ICY StreamTitle
        // and echoes our static artist ("Connessione in corso…") back into onMediaMetadataChanged!
        // BluetoothMetadataForwardingPlayer overrides getMediaMetadata(), getCurrentMediaItem(), and getCurrentTimeline()
        // so Bluetooth AVRCP and Android Auto always receive the 3-line customMetadata.
        val locationLine = station.locationLabel.ifBlank { "RadioWeb" }
        val metadata = if (isCurrent) {
            MediaMetadata.Builder()
                .setStation(station.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .apply {
                    if (station.preferredLogo.isNotBlank()) {
                        setArtworkUri(Uri.parse(station.preferredLogo))
                    }
                }
                .build()
        } else {
            MediaMetadata.Builder()
                .setTitle(station.name)
                .setDisplayTitle(station.name)
                .setArtist(locationLine)
                .setSubtitle(locationLine)
                .setAlbumTitle(locationLine)
                .setDescription(locationLine)
                .setAlbumArtist(locationLine)
                .setStation(station.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .apply {
                    if (station.preferredLogo.isNotBlank()) {
                        setArtworkUri(Uri.parse(station.preferredLogo))
                    }
                }
                .build()
        }

        val lowerUrl = station.streamUrl.trim().lowercase(Locale.ROOT)
        val builder = MediaItem.Builder()
            .setMediaId("${RadioPlaybackService.PREFIX_STATION}${station.id}")
            .setUri(station.streamUrl.trim())
            .setMediaMetadata(metadata)

        if (station.isHls || lowerUrl.contains(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    /**
     * Called by RadioWebViewModel whenever the resolved Now-Playing song changes
     * (either from api.radio.de polling or stream metadata).
     */
    fun updateResolvedNowPlayingTitle(songTitle: String?) {
        val cleaned = songTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (resolvedSongTitle != cleaned) {
            resolvedSongTitle = cleaned
            bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
            syncForegroundService()
        }
    }

    private fun syncForegroundService() {
        val station = _currentStation.value
        val status = _playerStatus.value
        if (station == null || status == PlayerStatus.IDLE || status == PlayerStatus.ERROR) {
            RadioPlaybackService.clearForegroundNotification(appContext)
            return
        }
        val liveSong = resolvedSongTitle?.takeIf { it.isNotBlank() }
            ?: _streamMetadataTitle.value?.takeIf { it.isNotBlank() }

        val statusText = when (status) {
            PlayerStatus.CONNECTING -> "Connessione in corso…"
            PlayerStatus.PLAYING -> if (!liveSong.isNullOrBlank()) {
                liveSong
            } else {
                "In diretta • ${station.locationLabel}"
            }
            PlayerStatus.PAUSED -> if (!liveSong.isNullOrBlank()) {
                "In pausa • $liveSong"
            } else {
                "In pausa • ${station.locationLabel}"
            }
            else -> station.locationLabel
        }
        RadioPlaybackService.startOrUpdate(
            context = appContext,
            stationName = station.name,
            statusText = statusText,
            locationText = station.locationLabel,
            isPlaying = status == PlayerStatus.PLAYING || status == PlayerStatus.CONNECTING
        )
    }

    private fun loadArtworkForStation(station: Station) {
        artworkLoadJob?.cancel()
        artworkLoadJob = scope.launch {
            val bitmap = fetchStationLogoBitmapOrFallback(station)
            val pngBytes = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    out.toByteArray()
                }
            }
            if (_currentStation.value?.id == station.id) {
                currentArtworkBytes = pngBytes
                RadioPlaybackService.currentArtworkBitmap = bitmap
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                if (_playerStatus.value != PlayerStatus.IDLE && _playerStatus.value != PlayerStatus.ERROR) {
                    syncForegroundService()
                }
            }
        }
    }

    private suspend fun fetchStationLogoBitmapOrFallback(station: Station): Bitmap = withContext(Dispatchers.IO) {
        for (url in station.availableLogos) {
            try {
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .allowHardware(false)
                    .size(300, 300)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable && drawable.bitmap != null) {
                        return@withContext drawable.bitmap
                    }
                }
            } catch (_: Exception) {
                // Try next fallback logo URL
            }
        }
        createFallbackStationBitmap(station.initials)
    }

    private fun createFallbackStationBitmap(initials: String): Bitmap {
        val sizePx = 256
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val rect = RectF(12f, 12f, sizePx - 12f, sizePx - 12f)
        canvas.drawRoundRect(rect, 36f, 36f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            textSize = 96f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initials, sizePx / 2f, textY, textPaint)

        return bmp
    }

    private fun extractCandidateFromMediaMetadata(mediaMetadata: MediaMetadata): String? {
        val currentName = _currentStation.value?.name.orEmpty()
        val currentId = _currentStation.value?.id.orEmpty()
        val currentLocation = _currentStation.value?.locationLabel.orEmpty()

        val validTitle = NowPlayingService.cleanAndValidateSongTitle(
            rawTitle = mediaMetadata.title?.toString(),
            stationName = currentName,
            stationId = currentId,
            stationLocation = currentLocation
        ).orEmpty()

        val validArtist = NowPlayingService.cleanAndValidateSongTitle(
            rawTitle = mediaMetadata.artist?.toString(),
            stationName = currentName,
            stationId = currentId,
            stationLocation = currentLocation
        ).orEmpty()

        val validDisplay = NowPlayingService.cleanAndValidateSongTitle(
            rawTitle = mediaMetadata.displayTitle?.toString(),
            stationName = currentName,
            stationId = currentId,
            stationLocation = currentLocation
        ).orEmpty()

        val combined = when {
            validArtist.isNotEmpty() && validTitle.isNotEmpty() &&
                !validTitle.contains(validArtist, ignoreCase = true) -> "$validArtist - $validTitle"
            validTitle.isNotEmpty() -> validTitle
            validArtist.isNotEmpty() -> validArtist
            validDisplay.isNotEmpty() -> validDisplay
            else -> ""
        }.trim()

        return NowPlayingService.cleanAndValidateSongTitle(
            rawTitle = combined,
            stationName = currentName,
            stationId = currentId,
            stationLocation = currentLocation
        )
    }

    private data class ExtractedPacketMetadata(
        val candidateTitle: String?,
        val isSongPacket: Boolean
    )

    private fun extractCandidateFromMetadataPacket(metadata: Metadata): ExtractedPacketMetadata {
        val currentName = _currentStation.value?.name.orEmpty()
        val currentId = _currentStation.value?.id.orEmpty()
        val currentLocation = _currentStation.value?.locationLabel.orEmpty()

        val packetBuilder = MediaMetadata.Builder()
        var hasExplicitSongEntry = false
        var rawIcyOrTxxxCandidate: String? = null

        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            entry.populateMediaMetadata(packetBuilder)

            val entryClassName = entry.javaClass.simpleName
            if (entryClassName == "IcyInfo") {
                hasExplicitSongEntry = true
                try {
                    val titleField = entry.javaClass.getField("title")
                    val icyTitle = titleField.get(entry) as? String
                    if (!icyTitle.isNullOrBlank()) {
                        rawIcyOrTxxxCandidate = icyTitle
                    }
                } catch (_: Exception) {
                }
            } else if (entryClassName == "TextInformationFrame") {
                try {
                    val idField = entry.javaClass.getField("id")
                    val frameId = (idField.get(entry) as? String).orEmpty().uppercase(Locale.ROOT)
                    if (frameId == "TIT2" || frameId == "TT2" || frameId == "TPE1" || frameId == "TP1" || frameId == "TXXX") {
                        hasExplicitSongEntry = true
                        if (frameId == "TXXX") {
                            val valueField = entry.javaClass.getField("value")
                            val txxxVal = (valueField.get(entry) as? String)?.trim()
                            if (!txxxVal.isNullOrBlank()) {
                                val parsedIcy = NowPlayingService.extractIcyStreamTitle(txxxVal) ?: txxxVal
                                rawIcyOrTxxxCandidate = parsedIcy
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            } else if (entryClassName == "VorbisComment") {
                try {
                    val keyField = entry.javaClass.getField("key")
                    val key = (keyField.get(entry) as? String).orEmpty().uppercase(Locale.ROOT)
                    if (key == "TITLE" || key == "ARTIST" || key == "STREAMTITLE") {
                        hasExplicitSongEntry = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        val packetMetadata = packetBuilder.build()
        if (packetMetadata.title != null || packetMetadata.artist != null || packetMetadata.displayTitle != null) {
            hasExplicitSongEntry = true
        }

        val standardCandidate = extractCandidateFromMediaMetadata(packetMetadata)
        val finalCandidate = standardCandidate ?: NowPlayingService.cleanAndValidateSongTitle(
            rawTitle = rawIcyOrTxxxCandidate,
            stationName = currentName,
            stationId = currentId,
            stationLocation = currentLocation
        )

        return ExtractedPacketMetadata(
            candidateTitle = finalCandidate,
            isSongPacket = hasExplicitSongEntry
        )
    }

    private fun applyStreamMetadataCandidate(candidate: String?, clearIfEmpty: Boolean) {
        if (!candidate.isNullOrEmpty()) {
            if (_streamMetadataTitle.value != candidate) {
                _streamMetadataTitle.value = candidate
                scheduleStreamMetadataStalenessTimer(candidate)
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                syncForegroundService()
            } else {
                // Refresh staleness timer if the stream re-confirms the active song
                scheduleStreamMetadataStalenessTimer(candidate)
            }
        } else if (clearIfEmpty && _streamMetadataTitle.value != null) {
            streamMetadataStaleJob?.cancel()
            streamMetadataStaleJob = null
            _streamMetadataTitle.value = null
            bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
            syncForegroundService()
        }
    }

    private fun scheduleStreamMetadataStalenessTimer(expectedTitle: String) {
        streamMetadataStaleJob?.cancel()
        streamMetadataStaleJob = scope.launch {
            delay(STREAM_METADATA_MAX_AGE_MS)
            if (_streamMetadataTitle.value == expectedTitle) {
                _streamMetadataTitle.value = null
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                syncForegroundService()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            if (isVoluntaryStop) return
            val extracted = extractCandidateFromMetadataPacket(metadata)
            if (extracted.isSongPacket) {
                hasReceivedInStreamMetadataForCurrentStation = true
            }
            // Only clear previous song if this packet was an explicit song/ICY packet with an empty or station-name title
            applyStreamMetadataCandidate(
                candidate = extracted.candidateTitle,
                clearIfEmpty = extracted.isSongPacket
            )
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (isVoluntaryStop) return
            // Only accept initial track-level metadata before any in-stream packet has arrived;
            // do NOT let ExoPlayer's cumulative MediaMetadata resurrect cleared or expired titles.
            if (!hasReceivedInStreamMetadataForCurrentStation && _streamMetadataTitle.value == null) {
                val candidate = extractCandidateFromMediaMetadata(mediaMetadata)
                if (!candidate.isNullOrEmpty()) {
                    hasReceivedInStreamMetadataForCurrentStation = true
                    applyStreamMetadataCandidate(candidate = candidate, clearIfEmpty = false)
                }
            }
            bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isVoluntaryStop) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (_currentStation.value != null) {
                        _playerStatus.value = PlayerStatus.CONNECTING
                        startConnectionTimeoutTimer()
                        bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                        syncForegroundService()
                    }
                }
                Player.STATE_READY -> {
                    cancelConnectionTimeoutTimer()
                    val player = exoPlayer ?: return
                    if (player.playWhenReady) {
                        _playerStatus.value = PlayerStatus.PLAYING
                        _errorMessage.value = null
                    } else {
                        _playerStatus.value = PlayerStatus.PAUSED
                    }
                    bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                    syncForegroundService()
                }
                Player.STATE_ENDED -> {
                    cancelConnectionTimeoutTimer()
                    if (_playerStatus.value == PlayerStatus.PLAYING || _playerStatus.value == PlayerStatus.CONNECTING) {
                        _playerStatus.value = PlayerStatus.ERROR
                        _errorMessage.value = "Lo stream si è interrotto o è attualmente offline."
                        syncForegroundService()
                    }
                }
                Player.STATE_IDLE -> {
                    cancelConnectionTimeoutTimer()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isVoluntaryStop) return
            if (isPlaying) {
                cancelConnectionTimeoutTimer()
                _playerStatus.value = PlayerStatus.PLAYING
                _errorMessage.value = null
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                syncForegroundService()
            } else {
                val player = exoPlayer ?: return
                if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    _playerStatus.value = PlayerStatus.PAUSED
                    bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                    syncForegroundService()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isVoluntaryStop) return
            if (!playWhenReady && (
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                )
            ) {
                cancelConnectionTimeoutTimer()
                _playerStatus.value = PlayerStatus.PAUSED
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                syncForegroundService()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isVoluntaryStop) return
            cancelConnectionTimeoutTimer()
            val station = _currentStation.value
            val streamUrl = station?.streamUrl.orEmpty()
            _playerStatus.value = PlayerStatus.ERROR
            _errorMessage.value = mapPlaybackErrorToItalianMessage(error, streamUrl)
            syncForegroundService()
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(CONNECTION_TIMEOUT_MS.toInt())
            .setReadTimeoutMs(CONNECTION_TIMEOUT_MS.toInt())
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setPauseAtEndOfMediaItems(true)
            .build()
            .also { player ->
                player.addListener(playerListener)
                applyCurrentVolumeToPlayer(player)
                exoPlayer = player
                ensureForwardingPlayerRegistered(player)
            }
    }

    private fun ensureForwardingPlayerRegistered(player: ExoPlayer) {
        if (bluetoothForwardingPlayer != null) return
        val forwarding = BluetoothMetadataForwardingPlayer(player)
        bluetoothForwardingPlayer = forwarding
        RadioPlaybackService.attachPlayer(appContext, forwarding)
    }

    /**
     * Preselects a station (e.g. RADIO LAC on startup or after catalog reload) without autoplaying,
     * and populates the car browse queue so Bluetooth "Sfoglia" is immediately ready.
     */
    fun preselectStation(station: Station, forceReplace: Boolean = false) {
        if (_currentStation.value == null || (forceReplace && _playerStatus.value == PlayerStatus.IDLE)) {
            _currentStation.value = station
            _playerStatus.value = PlayerStatus.IDLE
            _errorMessage.value = null
            _streamMetadataTitle.value = null
            hasReceivedInStreamMetadataForCurrentStation = false
            resolvedSongTitle = null
            val player = getOrCreatePlayer()
            loadArtworkForStation(station)
            try {
                val queueWindow = buildQueueWindowAround(station)
                currentQueueStations = queueWindow
                val startIndex = queueWindow.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
                val items = queueWindow.mapIndexed { idx, st ->
                    stationToQueueMediaItem(st, isCurrent = idx == startIndex)
                }
                isVoluntaryStop = true
                player.setMediaItems(items, startIndex, C.TIME_UNSET)
                isVoluntaryStop = false
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
            } catch (_: Exception) {
                isVoluntaryStop = false
            }
        }
    }

    /**
     * Updates the current station's metadata and queue window when a new catalog is loaded
     * and the currently selected station still exists in the new catalog.
     */
    fun syncActiveStationWithCatalog(updatedStation: Station) {
        val current = _currentStation.value ?: return
        if (current.id != updatedStation.id) return

        val streamUrlChanged = !current.streamUrl.trim().equals(updatedStation.streamUrl.trim(), ignoreCase = true)
        _currentStation.value = updatedStation
        loadArtworkForStation(updatedStation)

        if (_playerStatus.value == PlayerStatus.IDLE) {
            preselectStation(updatedStation, forceReplace = true)
        } else if (streamUrlChanged && (_playerStatus.value == PlayerStatus.PLAYING || _playerStatus.value == PlayerStatus.CONNECTING)) {
            playStation(updatedStation)
        } else {
            currentQueueStations = buildQueueWindowAround(updatedStation)
            bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
            syncForegroundService()
        }
    }

    /**
     * Handles clicking a station card:
     * - If it's already the active station and playing/connecting -> pause it.
     * - If it's the active station and paused/idle/error -> start/resume playback.
     * - If it's a different station -> stop previous station and play the new one.
     */
    fun onStationCardClicked(station: Station) {
        val current = _currentStation.value
        if (current?.id == station.id) {
            togglePlayPause()
        } else {
            playStation(station)
        }
    }

    fun playStation(station: Station) {
        cancelConnectionTimeoutTimer()
        streamMetadataStaleJob?.cancel()
        streamMetadataStaleJob = null
        RadioPlaybackService.standaloneActiveStationId = null
        com.example.data.FavoritesRepository(appContext).saveLastPlayedStation(station)
        val isNewStation = _currentStation.value?.id != station.id
        _streamMetadataTitle.value = null
        hasReceivedInStreamMetadataForCurrentStation = false
        resolvedSongTitle = null
        if (isNewStation) {
            currentArtworkBytes = null
            RadioPlaybackService.currentArtworkBitmap = null
        }
        _currentStation.value = station
        _errorMessage.value = null

        loadArtworkForStation(station)

        val url = station.streamUrl.trim()
        val lowerUrl = url.lowercase(Locale.ROOT)

        // Check for .pls or .m3u (excluding .m3u8 HLS)
        val pathPart = lowerUrl.substringBefore('?').substringBefore('#')
        if (pathPart.endsWith(".pls") || (pathPart.endsWith(".m3u") && !pathPart.endsWith(".m3u8"))) {
            stopInternalPlayerCleanly()
            _playerStatus.value = PlayerStatus.ERROR
            _errorMessage.value = "Stream playlist (.pls/.m3u) non riproducibile direttamente."
            return
        }

        val player = getOrCreatePlayer()
        isVoluntaryStop = true
        player.stop()
        player.clearMediaItems()
        isVoluntaryStop = false

        _playerStatus.value = PlayerStatus.CONNECTING
        startConnectionTimeoutTimer()
        bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
        syncForegroundService()

        val queueWindow = buildQueueWindowAround(station)
        currentQueueStations = queueWindow
        val startIndex = queueWindow.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        val mediaItems = queueWindow.mapIndexed { idx, st ->
            stationToQueueMediaItem(st, isCurrent = idx == startIndex)
        }

        try {
            player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            cancelConnectionTimeoutTimer()
            _playerStatus.value = PlayerStatus.ERROR
            _errorMessage.value = "Impossibile avviare lo stream: ${e.localizedMessage ?: "formato non supportato"}"
            syncForegroundService()
        }
    }

    fun togglePlayPause() {
        val station = _currentStation.value ?: return
        when (_playerStatus.value) {
            PlayerStatus.PLAYING, PlayerStatus.CONNECTING -> {
                cancelConnectionTimeoutTimer()
                streamMetadataStaleJob?.cancel()
                streamMetadataStaleJob = null
                exoPlayer?.playWhenReady = false
                _playerStatus.value = PlayerStatus.PAUSED
                bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
                syncForegroundService()
            }
            PlayerStatus.PAUSED, PlayerStatus.IDLE, PlayerStatus.ERROR -> {
                // Always reconnect to the live stream edge so live audio and current song metadata are fresh
                playStation(station)
            }
        }
    }

    fun retryCurrentStation() {
        val station = _currentStation.value ?: return
        playStation(station)
    }

    /**
     * Stops playback and closes the active station.
     */
    fun stopAndCloseStation() {
        cancelConnectionTimeoutTimer()
        streamMetadataStaleJob?.cancel()
        streamMetadataStaleJob = null
        artworkLoadJob?.cancel()
        stopInternalPlayerCleanly()
        _playerStatus.value = PlayerStatus.IDLE
        _errorMessage.value = null
        _streamMetadataTitle.value = null
        resolvedSongTitle = null
        currentArtworkBytes = null
        RadioPlaybackService.currentArtworkBitmap = null
        _currentStation.value = null
        bluetoothForwardingPlayer?.refreshAndBroadcastMetadata()
        syncForegroundService()
    }

    fun dismissErrorBanner() {
        _errorMessage.value = null
    }

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volumePercent.value = clamped
        if (clamped > 0) {
            _isMuted.value = false
            volumeBeforeMute = clamped
        } else {
            _isMuted.value = true
        }
        exoPlayer?.let { applyCurrentVolumeToPlayer(it) }
    }

    fun toggleMute() {
        if (_isMuted.value) {
            _isMuted.value = false
            val restored = if (volumeBeforeMute > 0) volumeBeforeMute else 75
            _volumePercent.value = restored
        } else {
            if (_volumePercent.value > 0) {
                volumeBeforeMute = _volumePercent.value
            }
            _isMuted.value = true
        }
        exoPlayer?.let { applyCurrentVolumeToPlayer(it) }
    }

    private fun applyCurrentVolumeToPlayer(player: ExoPlayer) {
        val effectiveVolume = if (_isMuted.value) 0f else (_volumePercent.value / 100f)
        player.volume = effectiveVolume.coerceIn(0f, 1f)
    }

    private fun startConnectionTimeoutTimer() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_playerStatus.value == PlayerStatus.CONNECTING) {
                stopInternalPlayerCleanly()
                _playerStatus.value = PlayerStatus.ERROR
                _errorMessage.value = "Timeout di connessione (16s): il server dello stream non risponde."
                syncForegroundService()
            }
        }
    }

    private fun cancelConnectionTimeoutTimer() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    private fun stopInternalPlayerCleanly() {
        isVoluntaryStop = true
        exoPlayer?.let { player ->
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        }
        isVoluntaryStop = false
    }

    private fun mapPlaybackErrorToItalianMessage(error: PlaybackException, streamUrl: String): String {
        val cause = error.cause
        val isHttpInsecure = streamUrl.startsWith("http://", ignoreCase = true)

        if (cause is HttpDataSource.CleartextNotPermittedException ||
            (isHttpInsecure && error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        ) {
            return "Connessione HTTP non sicura (Mixed Content) bloccata o rifiutata dal server."
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Timeout: il server della stazione non ha risposto entro 16 secondi."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Stream offline o impossibile connettersi al server della stazione."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "Stream attualmente offline o indirizzo non disponibile."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Formato audio dello stream non supportato dal dispositivo."
            else ->
                if (isHttpInsecure) {
                    "Errore su stream HTTP non sicuro (Mixed Content) o flusso offline."
                } else {
                    "Stream offline o formato non riproducibile al momento."
                }
        }
    }

    fun release() {
        cancelConnectionTimeoutTimer()
        streamMetadataStaleJob?.cancel()
        streamMetadataStaleJob = null
        artworkLoadJob?.cancel()
        isVoluntaryStop = true
        onSkipToPreviousStation = null
        onSkipToNextStation = null
        queueStationsProvider = null
        val detachedForwarding = bluetoothForwardingPlayer
        bluetoothForwardingPlayer = null
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.playWhenReady = false
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        RadioPlaybackService.detachPlayerAndCallbacks(detachedForwarding)
        RadioPlaybackService.stopService(appContext)
    }
}
