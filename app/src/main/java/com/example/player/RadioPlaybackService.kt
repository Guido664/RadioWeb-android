package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.example.MainActivity
import com.example.R
import com.example.data.FavoritesRepository
import com.example.model.PlayerStatus
import com.example.model.Station
import com.example.network.NowPlayingService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
class RadioPlaybackService : MediaLibraryService() {

    companion object {
        private const val CHANNEL_ID = "radioweb_playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_UPDATE = "com.example.player.action.START_OR_UPDATE"
        const val ACTION_CLEAR_FOREGROUND = "com.example.player.action.CLEAR_FOREGROUND"
        const val ACTION_STOP_SERVICE = "com.example.player.action.STOP_SERVICE"
        const val ACTION_USER_TOGGLE_PLAY = "com.example.player.action.USER_TOGGLE_PLAY"
        const val ACTION_USER_STOP = "com.example.player.action.USER_STOP"
        const val ACTION_USER_PREVIOUS = "com.example.player.action.USER_PREVIOUS"
        const val ACTION_USER_NEXT = "com.example.player.action.USER_NEXT"

        private const val EXTRA_STATION_NAME = "extra_station_name"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"
        private const val EXTRA_LOCATION_TEXT = "extra_location_text"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        // Browse tree IDs for Car Bluetooth ("Sfoglia") & Android Auto
        const val MEDIA_ID_ROOT = "radioweb_root"
        const val MEDIA_ID_FAVORITES = "radioweb_favorites"
        const val MEDIA_ID_ACTIVE_LIST = "radioweb_active_list"
        const val MEDIA_ID_GENRES_ROOT = "radioweb_genres_root"
        const val PREFIX_GENRE = "genre:"
        const val PREFIX_STATION = "station:"

        private val CAR_BROWSE_GENRES = listOf(
            "Pop",
            "Hits",
            "Rock",
            "80s",
            "90s",
            "Italian Music",
            "Top 40 & Charts",
            "Jazz",
            "Talk",
            "Classical",
            "Dance",
            "Electro",
            "Chillout",
            "Oldies",
            "Classic Rock"
        )

        private fun createFallbackStation(
            id: String,
            name: String,
            city: String,
            country: String,
            genres: List<String>,
            streamUrl: String
        ): Station = Station(
            id = id,
            name = name,
            city = city,
            country = country,
            genres = genres,
            logo100 = "",
            logo175 = "",
            logo300 = "",
            preferredLogo = "",
            availableLogos = emptyList(),
            streamUrl = streamUrl,
            format = "MP3",
            isHls = false,
            searchKey = name.lowercase(),
            initials = Station.computeInitials(name)
        )

        // Built-in instant stations used if Bluetooth probes the service before the 58k CSV finishes loading
        private val FALLBACK_BROWSE_STATIONS = listOf(
            createFallbackStation(
                id = "radiolacch",
                name = "RADIO LAC",
                city = "Geneva",
                country = "Switzerland",
                genres = listOf("Pop", "Hits", "Talk"),
                streamUrl = "https://radiolac.ice.infomaniak.ch/radiolac-high.mp3"
            ),
            createFallbackStation(
                id = "rtl1025",
                name = "RTL 102.5",
                city = "Milan",
                country = "Italy",
                genres = listOf("Pop", "Hits", "Italian Music"),
                streamUrl = "https://streamingv2.shoutcast.com/rtl-1025"
            ),
            createFallbackStation(
                id = "890rtl",
                name = "89.0 RTL",
                city = "Halle (Saale)",
                country = "Germany",
                genres = listOf("Pop", "Top 40 & Charts"),
                streamUrl = "https://fhhalle.streamabc.net/fhhal-890rtl-mp3-128-7126118"
            ),
            createFallbackStation(
                id = "radioswisspop",
                name = "Radio Swiss Pop",
                city = "Basel",
                country = "Switzerland",
                genres = listOf("Pop", "Hits"),
                streamUrl = "https://stream.srg-ssr.ch/m/rsp/mp3_128"
            ),
            createFallbackStation(
                id = "radioswissjazz",
                name = "Radio Swiss Jazz",
                city = "Basel",
                country = "Switzerland",
                genres = listOf("Jazz", "Chillout"),
                streamUrl = "https://stream.srg-ssr.ch/m/rsj/mp3_128"
            )
        )

        @Volatile
        var sharedForwardingPlayer: Player? = null

        @Volatile
        private var activeServiceInstance: RadioPlaybackService? = null

        @Volatile
        var currentArtworkBitmap: Bitmap? = null

        @Volatile
        var favoriteStationsProvider: (() -> List<Station>)? = null

        @Volatile
        var activeStationsProvider: (() -> List<Station>)? = null

        @Volatile
        var allStationsProvider: (() -> List<Station>)? = null

        @Volatile
        var onTogglePlayPauseCallback: (() -> Unit)? = null

        @Volatile
        var onStopCallback: (() -> Unit)? = null

        @Volatile
        var onPreviousCallback: (() -> Unit)? = null

        @Volatile
        var onNextCallback: (() -> Unit)? = null

        @Volatile
        var onPlayStationByIdCallback: ((String) -> Unit)? = null

        @Volatile
        var standaloneActiveStationId: String? = null

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; RadioWeb/1.0) ExoPlayerLib/2.19"

        fun attachPlayer(context: Context, player: Player) {
            sharedForwardingPlayer = player
            activeServiceInstance?.swapToControllerPlayer(player)
            try {
                // Start the service in background so MediaLibrarySession is registered with Bluetooth right away
                val intent = Intent(context.applicationContext, RadioPlaybackService::class.java)
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun detachPlayerAndCallbacks(detachedPlayer: Player?) {
            if (detachedPlayer == null || sharedForwardingPlayer === detachedPlayer) {
                sharedForwardingPlayer = null
            }
            currentArtworkBitmap = null
            onTogglePlayPauseCallback = null
            onStopCallback = null
            onPreviousCallback = null
            onNextCallback = null
        }

        fun clearViewModelProviders() {
            favoriteStationsProvider = null
            activeStationsProvider = null
            allStationsProvider = null
            onPlayStationByIdCallback = null
        }

        fun notifyCatalogOrFavoritesChanged() {
            activeServiceInstance?.notifyBrowserChildrenChanged()
        }

        fun startOrUpdate(
            context: Context,
            stationName: String,
            statusText: String,
            isPlaying: Boolean,
            locationText: String? = null
        ) {
            val intent = Intent(context, RadioPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_STATION_NAME, stationName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
                putExtra(EXTRA_LOCATION_TEXT, locationText)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Ignore if service cannot be started in unit test environments
            }
        }

        fun clearForegroundNotification(context: Context) {
            try {
                activeServiceInstance?.clearForegroundInternal()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            try {
                activeServiceInstance?.stopServiceInternal()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)
                context.stopService(Intent(context, RadioPlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var fallbackInternalPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val standaloneNowPlayingService = NowPlayingService()
    private var standalonePollingJob: Job? = null
    private var standaloneResolvedSong: String? = null
    private var audioDeviceCallbackRegistered = false
    private var ignoreInitialAudioDevices = true

    private val carAudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (ignoreInitialAudioDevices || addedDevices.isNullOrEmpty()) return
            val isCarBluetoothOrBus = addedDevices.any { device ->
                device.isSink && (
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BUS
                )
            }
            if (isCarBluetoothOrBus) {
                mainHandler.post {
                    handleCarBluetoothConnectedAutoPlay()
                }
            }
        }
    }

    private fun resolveAllStations(): List<Station> {
        val provided = allStationsProvider?.invoke().orEmpty()
        if (provided.isNotEmpty()) return provided
        val repo = FavoritesRepository(applicationContext)
        val cachedFavs = repo.loadFavoriteStationsCache()
        val lastPlayed = repo.loadLastPlayedStation()
        val combined = LinkedHashMap<String, Station>()
        lastPlayed?.let { combined[it.id] = it }
        cachedFavs.forEach { combined[it.id] = it }
        FALLBACK_BROWSE_STATIONS.forEach { if (it.id !in combined) combined[it.id] = it }
        return combined.values.toList()
    }

    private fun resolveActiveStations(): List<Station> {
        val provided = activeStationsProvider?.invoke().orEmpty()
        return if (provided.isNotEmpty()) provided else resolveAllStations()
    }

    private fun resolveFavoriteStations(): List<Station> {
        val provided = favoriteStationsProvider?.invoke().orEmpty()
        if (provided.isNotEmpty()) return provided
        return try {
            val repo = FavoritesRepository(applicationContext)
            val cachedFavs = repo.loadFavoriteStationsCache()
            if (cachedFavs.isNotEmpty()) return cachedFavs
            val favIds = repo.loadFavoriteIds()
            val all = resolveAllStations()
            val matched = all.filter { it.id in favIds }
            if (matched.isNotEmpty()) matched else resolveActiveStations().take(30)
        } catch (_: Exception) {
            resolveActiveStations().take(30)
        }
    }

    /**
     * Resolves the queue of stations and starting index for automatic car playback:
     * Prioritizes Favorites (if present) and resumes the last played station (or first Favorite).
     */
    private fun resolveCarResumptionQueueAndStartIndex(): Pair<List<Station>, Int> {
        val repo = FavoritesRepository(applicationContext)
        val lastPlayed = repo.loadLastPlayedStation()
        val favorites = resolveFavoriteStations()
        val baseList = if (favorites.isNotEmpty()) favorites.take(45) else resolveActiveStations().take(45)

        if (baseList.isEmpty()) {
            val fallback = listOfNotNull(lastPlayed) + FALLBACK_BROWSE_STATIONS
            return fallback to 0
        }

        if (lastPlayed != null) {
            val existingIdx = baseList.indexOfFirst { it.id == lastPlayed.id }
            if (existingIdx >= 0) {
                return baseList to existingIdx
            }
            return (listOf(lastPlayed) + baseList.take(44)) to 0
        }

        return baseList to 0
    }

    private fun handleCarBluetoothConnectedAutoPlay() {
        val repo = FavoritesRepository(applicationContext)
        if (!repo.isAutoPlayOnCarConnectEnabled()) return

        val activePlayer = mediaLibrarySession?.player
        if (activePlayer != null && activePlayer.isPlaying) return

        val (queue, startIndex) = resolveCarResumptionQueueAndStartIndex()
        val targetStation = queue.getOrNull(startIndex) ?: queue.firstOrNull() ?: return

        val vmPlayCallback = onPlayStationByIdCallback
        if (vmPlayCallback != null) {
            vmPlayCallback.invoke(targetStation.id)
        } else {
            startStandalonePlayback(queue, startIndex)
        }
    }

    private fun startStandalonePlayback(queue: List<Station>, startIndex: Int) {
        val targetStation = queue.getOrNull(startIndex) ?: queue.firstOrNull() ?: return
        standaloneActiveStationId = targetStation.id
        standaloneResolvedSong = null
        FavoritesRepository(applicationContext).saveLastPlayedStation(targetStation)
        val player = getOrCreateFallbackPlayer()
        val safeStartIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val items = queue.mapIndexed { idx, st ->
            if (idx == safeStartIndex) {
                stationToPlayableMediaItem(st, songTitle = null, activeStatus = PlayerStatus.CONNECTING)
            } else {
                stationToPlayableMediaItem(st)
            }
        }
        player.setMediaItems(items, safeStartIndex, C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
        promoteStandaloneNotification(
            stationName = targetStation.name,
            statusText = "Connessione in corso…",
            locationText = targetStation.locationLabel,
            isPlaying = true
        )
        startStandaloneNowPlayingPolling(targetStation)
    }

    private fun promoteStandaloneNotification(
        stationName: String,
        statusText: String,
        isPlaying: Boolean,
        locationText: String? = null
    ) {
        startOrUpdate(
            context = applicationContext,
            stationName = stationName,
            statusText = statusText,
            locationText = locationText,
            isPlaying = isPlaying
        )
    }

    private fun startStandaloneNowPlayingPolling(station: Station) {
        standalonePollingJob?.cancel()
        standalonePollingJob = serviceScope.launch {
            var lastApiTitle: String? = null
            while (isActive && sharedForwardingPlayer == null && standaloneActiveStationId == station.id) {
                val poll = standaloneNowPlayingService.pollNowPlayingSources(
                    stationId = station.id,
                    stationName = station.name,
                    stationLocation = station.locationLabel,
                    streamUrl = station.streamUrl,
                    isHls = station.isHls,
                    previousApiTitle = lastApiTitle
                )
                if (!isActive || sharedForwardingPlayer != null || standaloneActiveStationId != station.id) break
                if (!poll.apiTitle.isNullOrBlank()) {
                    lastApiTitle = poll.apiTitle
                }
                val candidate = poll.icyTitle?.takeIf { it.isNotBlank() }
                    ?: poll.apiTitle?.takeIf { it.isNotBlank() }
                if (candidate != standaloneResolvedSong) {
                    standaloneResolvedSong = candidate
                    updateStandaloneActiveItemMetadata(station)
                }
                delay(12_000L)
            }
        }
    }

    private fun updateStandaloneActiveItemMetadata(station: Station) {
        val fp = fallbackInternalPlayer ?: return
        if (sharedForwardingPlayer != null) return
        val currentIdx = fp.currentMediaItemIndex
        val currentItem = fp.currentMediaItem ?: return
        if (currentItem.mediaId.removePrefix(PREFIX_STATION) != station.id) return

        val status = when {
            fp.isPlaying -> PlayerStatus.PLAYING
            fp.playbackState == Player.STATE_BUFFERING -> PlayerStatus.CONNECTING
            else -> PlayerStatus.PAUSED
        }
        val updatedItem = stationToPlayableMediaItem(
            station = station,
            songTitle = standaloneResolvedSong,
            activeStatus = status
        )
        try {
            fp.replaceMediaItem(currentIdx, updatedItem)
        } catch (_: Exception) {
        }
        val statusLine = when {
            status == PlayerStatus.PAUSED && !standaloneResolvedSong.isNullOrBlank() -> "In pausa • $standaloneResolvedSong"
            status == PlayerStatus.PAUSED -> "In pausa • ${station.locationLabel}"
            !standaloneResolvedSong.isNullOrBlank() -> standaloneResolvedSong!!
            status == PlayerStatus.CONNECTING -> "Connessione in corso…"
            else -> "In diretta • ${station.locationLabel}"
        }
        promoteStandaloneNotification(
            stationName = station.name,
            statusText = statusLine,
            locationText = station.locationLabel,
            isPlaying = status == PlayerStatus.PLAYING || status == PlayerStatus.CONNECTING
        )
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
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
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = buildFolderItem(
                mediaId = MEDIA_ID_ROOT,
                title = "RadioWeb",
                subtitle = "Stazioni Radio & Preferiti"
            )
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children: List<MediaItem> = when {
                parentId == MEDIA_ID_ROOT -> {
                    listOf(
                        buildFolderItem(
                            mediaId = MEDIA_ID_FAVORITES,
                            title = "Preferiti",
                            subtitle = "Le tue stazioni radio preferite"
                        ),
                        buildFolderItem(
                            mediaId = MEDIA_ID_ACTIVE_LIST,
                            title = "Catalogo Stazioni",
                            subtitle = "Elenco stazioni radio"
                        ),
                        buildFolderItem(
                            mediaId = MEDIA_ID_GENRES_ROOT,
                            title = "Sfoglia per Genere",
                            subtitle = "Pop, Rock, 80s, Jazz, Italian Music"
                        )
                    )
                }

                parentId == MEDIA_ID_FAVORITES -> {
                    resolveFavoriteStations()
                        .take(80)
                        .map { stationToPlayableMediaItem(it) }
                }

                parentId == MEDIA_ID_ACTIVE_LIST -> {
                    resolveActiveStations()
                        .take(80)
                        .map { stationToPlayableMediaItem(it) }
                }

                parentId == MEDIA_ID_GENRES_ROOT -> {
                    CAR_BROWSE_GENRES.map { genre ->
                        buildFolderItem(
                            mediaId = "$PREFIX_GENRE$genre",
                            title = genre,
                            subtitle = "Stazioni $genre"
                        )
                    }
                }

                parentId.startsWith(PREFIX_GENRE) -> {
                    val genreName = parentId.removePrefix(PREFIX_GENRE)
                    val all = resolveAllStations()
                    val matched = all.asSequence()
                        .filter { st -> st.genres.any { g -> g.equals(genreName, ignoreCase = true) } }
                        .take(80)
                        .map { stationToPlayableMediaItem(it) }
                        .toList()
                    if (matched.isNotEmpty()) {
                        matched
                    } else {
                        all.take(30).map { stationToPlayableMediaItem(it) }
                    }
                }

                else -> {
                    resolveActiveStations()
                        .take(50)
                        .map { stationToPlayableMediaItem(it) }
                }
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val stationId = mediaId.removePrefix(PREFIX_STATION)
            val station = resolveAllStations().firstOrNull { it.id == stationId }
            if (station != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(stationToPlayableMediaItem(station), null))
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val count = searchStationsByQuery(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val results = searchStationsByQuery(query).map { stationToPlayableMediaItem(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(results, params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val all = resolveAllStations()
            val resolved = mediaItems.map { item ->
                val rawId = item.mediaId
                val stationId = rawId.removePrefix(PREFIX_STATION)
                val station = all.firstOrNull { it.id == stationId }
                if (station != null) {
                    stationToPlayableMediaItem(station)
                } else {
                    item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val firstItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            if (firstItem != null) {
                val stationId = firstItem.mediaId.removePrefix(PREFIX_STATION)
                mainHandler.post {
                    val vmPlayCallback = onPlayStationByIdCallback
                    if (vmPlayCallback != null) {
                        vmPlayCallback.invoke(stationId)
                    } else {
                        val all = resolveAllStations()
                        val target = all.firstOrNull { it.id == stationId }
                        if (target != null) {
                            standaloneActiveStationId = target.id
                            standaloneResolvedSong = null
                            FavoritesRepository(applicationContext).saveLastPlayedStation(target)
                            promoteStandaloneNotification(
                                stationName = target.name,
                                statusText = "In diretta • ${target.locationLabel}",
                                locationText = target.locationLabel,
                                isPlaying = true
                            )
                            startStandaloneNowPlayingPolling(target)
                        }
                    }
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val (queue, startIndex) = resolveCarResumptionQueueAndStartIndex()
            val targetStation = queue.getOrNull(startIndex) ?: queue.firstOrNull()
            val safeStartIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            val mediaItems = queue.mapIndexed { idx, st ->
                if (idx == safeStartIndex) {
                    stationToPlayableMediaItem(st, songTitle = standaloneResolvedSong, activeStatus = PlayerStatus.PLAYING)
                } else {
                    stationToPlayableMediaItem(st)
                }
            }

            if (targetStation != null && FavoritesRepository(applicationContext).isAutoPlayOnCarConnectEnabled()) {
                mainHandler.post {
                    val vmPlayCallback = onPlayStationByIdCallback
                    if (vmPlayCallback != null) {
                        vmPlayCallback.invoke(targetStation.id)
                    } else {
                        standaloneActiveStationId = targetStation.id
                        standaloneResolvedSong = null
                        FavoritesRepository(applicationContext).saveLastPlayedStation(targetStation)
                        promoteStandaloneNotification(
                            stationName = targetStation.name,
                            statusText = "In diretta • ${targetStation.locationLabel}",
                            locationText = targetStation.locationLabel,
                            isPlaying = true
                        )
                        startStandaloneNowPlayingPolling(targetStation)
                    }
                }
            }

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems,
                    safeStartIndex,
                    C.TIME_UNSET
                )
            )
        }
    }

    private fun searchStationsByQuery(query: String): List<Station> {
        val trimmed = query.trim()
        val all = resolveAllStations()
        if (trimmed.isEmpty()) return all.take(50)
        return all.asSequence()
            .filter { it.matchesSearchQuery(trimmed) }
            .take(50)
            .toList()
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        ensureNotificationChannel()
        ensureLibrarySessionCreated()
        registerCarAudioDeviceCallback()
    }

    private fun registerCarAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            ignoreInitialAudioDevices = true
            audioManager.registerAudioDeviceCallback(carAudioDeviceCallback, mainHandler)
            audioDeviceCallbackRegistered = true
            mainHandler.postDelayed({
                ignoreInitialAudioDevices = false
            }, 1500L)
        } catch (_: Exception) {
        }
    }

    private fun unregisterCarAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(carAudioDeviceCallback)
        } catch (_: Exception) {
        } finally {
            audioDeviceCallbackRegistered = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        ensureLibrarySessionCreated()
        return mediaLibrarySession
    }

    private val standalonePlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sharedForwardingPlayer != null) return
            val rawId = mediaItem?.mediaId?.removePrefix(PREFIX_STATION) ?: return
            val station = resolveAllStations().firstOrNull { it.id == rawId }
            val title = station?.name ?: mediaItem.mediaMetadata.title?.toString() ?: "RadioWeb"
            val subtitle = station?.locationLabel ?: mediaItem.mediaMetadata.subtitle?.toString() ?: "In diretta"
            if (standaloneActiveStationId != rawId) {
                standaloneActiveStationId = rawId
                standaloneResolvedSong = null
                if (station != null) {
                    FavoritesRepository(applicationContext).saveLastPlayedStation(station)
                    startStandaloneNowPlayingPolling(station)
                }
            }
            if (fallbackInternalPlayer?.playWhenReady == true) {
                promoteStandaloneNotification(
                    stationName = title,
                    statusText = standaloneResolvedSong ?: "In diretta • $subtitle",
                    locationText = station?.locationLabel,
                    isPlaying = true
                )
            }
        }

        override fun onMetadata(metadata: Metadata) {
            if (sharedForwardingPlayer != null) return
            val activeId = standaloneActiveStationId ?: return
            val station = resolveAllStations().firstOrNull { it.id == activeId } ?: return
            val builder = MediaMetadata.Builder()
            for (i in 0 until metadata.length()) {
                metadata.get(i).populateMediaMetadata(builder)
            }
            val built = builder.build()
            val candidate = NowPlayingService.cleanAndValidateSongTitle(
                rawTitle = built.title?.toString() ?: built.displayTitle?.toString(),
                stationName = station.name,
                stationId = station.id,
                stationLocation = station.locationLabel
            )
            if (!candidate.isNullOrBlank() && candidate != standaloneResolvedSong) {
                standaloneResolvedSong = candidate
                updateStandaloneActiveItemMetadata(station)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (sharedForwardingPlayer != null) return
            val item = fallbackInternalPlayer?.currentMediaItem ?: return
            val rawId = item.mediaId.removePrefix(PREFIX_STATION)
            val station = resolveAllStations().firstOrNull { it.id == rawId }
            val title = station?.name ?: item.mediaMetadata.title?.toString() ?: "RadioWeb"
            val location = station?.locationLabel ?: item.mediaMetadata.subtitle?.toString() ?: "In diretta"
            if (isPlaying) {
                standaloneActiveStationId = rawId
                if (station != null) {
                    updateStandaloneActiveItemMetadata(station)
                    if (standalonePollingJob?.isActive != true) {
                        startStandaloneNowPlayingPolling(station)
                    }
                } else {
                    promoteStandaloneNotification(title, "In diretta • $location", isPlaying = true, locationText = location)
                }
            } else if (fallbackInternalPlayer?.playbackState == Player.STATE_READY) {
                standalonePollingJob?.cancel()
                if (station != null) {
                    updateStandaloneActiveItemMetadata(station)
                } else {
                    promoteStandaloneNotification(title, "In pausa • $location", isPlaying = false, locationText = location)
                }
            }
        }
    }

    private fun getOrCreateFallbackPlayer(): Player {
        sharedForwardingPlayer?.let { return it }
        fallbackInternalPlayer?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(16_000)
            .setReadTimeoutMs(16_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(applicationContext)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setPauseAtEndOfMediaItems(true)
            .build()
            .also { fallbackPlayer ->
                fallbackPlayer.addListener(standalonePlayerListener)
                val (queue, startIndex) = resolveCarResumptionQueueAndStartIndex()
                val items = queue.map { stationToPlayableMediaItem(it) }
                fallbackPlayer.setMediaItems(
                    items,
                    startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                    C.TIME_UNSET
                )
                fallbackInternalPlayer = fallbackPlayer
            }
    }

    private fun swapToControllerPlayer(newPlayer: Player) {
        mainHandler.post {
            standalonePollingJob?.cancel()
            standalonePollingJob = null
            val session = mediaLibrarySession
            if (session != null && session.player !== newPlayer) {
                fallbackInternalPlayer?.removeListener(standalonePlayerListener)
                fallbackInternalPlayer?.stop()
                fallbackInternalPlayer?.release()
                fallbackInternalPlayer = null
                session.player = newPlayer
            } else if (session == null) {
                ensureLibrarySessionCreated()
            }
            notifyBrowserChildrenChanged()
        }
    }

    private fun notifyBrowserChildrenChanged() {
        mainHandler.post {
            val session = mediaLibrarySession ?: return@post
            try {
                session.notifyChildrenChanged(MEDIA_ID_ROOT, 3, null)
                session.notifyChildrenChanged(MEDIA_ID_FAVORITES, resolveFavoriteStations().size.coerceAtMost(80), null)
                session.notifyChildrenChanged(MEDIA_ID_ACTIVE_LIST, resolveActiveStations().size.coerceAtMost(80), null)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureLibrarySessionCreated() {
        if (mediaLibrarySession != null) {
            val controllerPlayer = sharedForwardingPlayer
            if (controllerPlayer != null && mediaLibrarySession?.player !== controllerPlayer) {
                mediaLibrarySession?.player = controllerPlayer
                fallbackInternalPlayer?.release()
                fallbackInternalPlayer = null
            }
            return
        }
        val player = getOrCreateFallbackPlayer()
        try {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            mediaLibrarySession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
                .also { session ->
                    addSession(session)
                }
        } catch (_: Exception) {
        }
    }

    private fun clearForegroundInternal() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun stopServiceInternal() {
        standalonePollingJob?.cancel()
        standalonePollingJob = null
        standaloneActiveStationId = null
        standaloneResolvedSong = null
        try {
            fallbackInternalPlayer?.playWhenReady = false
            fallbackInternalPlayer?.stop()
        } catch (_: Exception) {
        }
        clearForegroundInternal()
        stopSelf()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Notification lifecycle is explicitly managed via ACTION_START_OR_UPDATE and clearForegroundInternal()
        // so Media3's internal MediaNotificationManager does not resurrect the notification after app close.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        standalonePollingJob?.cancel()
        standalonePollingJob = null
        standaloneActiveStationId = null
        standaloneResolvedSong = null
        try {
            onStopCallback?.invoke()
            sharedForwardingPlayer?.stop()
            fallbackInternalPlayer?.playWhenReady = false
            fallbackInternalPlayer?.stop()
        } catch (_: Exception) {
        }
        clearForegroundInternal()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CLEAR_FOREGROUND) {
            clearForegroundInternal()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP_SERVICE) {
            stopServiceInternal()
            return START_NOT_STICKY
        }
        if (action == ACTION_USER_STOP) {
            standalonePollingJob?.cancel()
            standalonePollingJob = null
            standaloneActiveStationId = null
            standaloneResolvedSong = null
            val cb = onStopCallback
            if (cb != null) {
                cb.invoke()
            } else {
                fallbackInternalPlayer?.playWhenReady = false
                fallbackInternalPlayer?.stop()
            }
            clearForegroundInternal()
            if (cb == null) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        ensureLibrarySessionCreated()

        // If the user disabled auto-play on car Bluetooth connect, ignore incoming automatic MEDIA_BUTTON PLAY events when idle
        if (action == Intent.ACTION_MEDIA_BUTTON) {
            val autoPlayEnabled = FavoritesRepository(applicationContext).isAutoPlayOnCarConnectEnabled()
            val activePlayer = mediaLibrarySession?.player
            val isCurrentlyIdle = activePlayer == null ||
                (activePlayer.playbackState == Player.STATE_IDLE && !activePlayer.playWhenReady)
            if (!autoPlayEnabled && isCurrentlyIdle) {
                return START_NOT_STICKY
            }
        }

        if (action == Intent.ACTION_MEDIA_BUTTON) {
            super.onStartCommand(intent, flags, startId)
        }

        when (action) {
            ACTION_USER_PREVIOUS -> {
                val cb = onPreviousCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    fallbackInternalPlayer?.seekToPreviousMediaItem()
                }
            }
            ACTION_USER_NEXT -> {
                val cb = onNextCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    fallbackInternalPlayer?.seekToNextMediaItem()
                }
            }
            ACTION_USER_TOGGLE_PLAY -> {
                val cb = onTogglePlayPauseCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    val fp = fallbackInternalPlayer
                    if (fp != null) {
                        if (fp.isPlaying) {
                            fp.pause()
                        } else {
                            if (fp.playbackState == Player.STATE_IDLE) {
                                fp.prepare()
                            }
                            fp.play()
                        }
                    }
                }
            }
            ACTION_START_OR_UPDATE -> {
                val stationName = intent.getStringExtra(EXTRA_STATION_NAME) ?: "RadioWeb"
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "In diretta"
                val locationText = intent.getStringExtra(EXTRA_LOCATION_TEXT)
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                val notification = buildNotification(stationName, statusText, isPlaying, locationText)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun buildFolderItem(
        mediaId: String,
        title: String,
        subtitle: String
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun stationToPlayableMediaItem(
        station: Station,
        songTitle: String? = null,
        activeStatus: PlayerStatus? = null
    ): MediaItem {
        val metadata = if (activeStatus != null || !songTitle.isNullOrBlank()) {
            RadioPlayerController.buildCarDisplayMediaMetadata(
                station = station,
                songTitle = songTitle,
                playerStatus = activeStatus ?: PlayerStatus.PLAYING
            )
        } else {
            val locationLine = station.locationLabel.ifBlank { "RadioWeb" }
            val metaBuilder = MediaMetadata.Builder()
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

            if (station.preferredLogo.isNotBlank()) {
                metaBuilder.setArtworkUri(Uri.parse(station.preferredLogo))
            }
            metaBuilder.build()
        }

        val lowerUrl = station.streamUrl.trim().lowercase(Locale.ROOT)
        val builder = MediaItem.Builder()
            .setMediaId("$PREFIX_STATION${station.id}")
            .setUri(station.streamUrl.trim())
            .setMediaMetadata(metadata)

        if (station.isHls || lowerUrl.contains(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        return builder.build()
    }

    private fun buildNotification(
        stationName: String,
        statusText: String,
        isPlaying: Boolean,
        locationText: String? = null
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_USER_PREVIOUS
        }
        val prevPendingIntent = PendingIntent.getService(
            this,
            3,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_USER_TOGGLE_PLAY
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_USER_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this,
            4,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_USER_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseLabel = if (isPlaying) "Pausa" else "Riproduci"
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val subTextLine = locationText?.takeIf { it.isNotBlank() } ?: "RadioWeb"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(stationName) // Riga 1: Nome della radio
            .setContentText(statusText)   // Riga 2: Titolo del brano / stato
            .setSubText(subTextLine)      // Riga 3: Città, Nazione
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Precedente", prevPendingIntent)
            .addAction(playPauseIcon, playPauseLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Successiva", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        currentArtworkBitmap?.let { bmp ->
            builder.setLargeIcon(bmp)
        }

        mediaLibrarySession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Riproduzione Radio in Background",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene attiva la riproduzione della radio a schermo spento e via Bluetooth auto"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        standalonePollingJob?.cancel()
        serviceScope.cancel()
        unregisterCarAudioDeviceCallback()
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        fallbackInternalPlayer?.removeListener(standalonePlayerListener)
        fallbackInternalPlayer?.release()
        fallbackInternalPlayer = null
        super.onDestroy()
    }
}
