package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CatalogRepository
import com.example.data.CsvCatalogParser
import com.example.data.FavoritesRepository
import com.example.data.LoadedCatalogData
import com.example.model.AppTab
import com.example.model.CatalogMetrics
import com.example.model.GenreItem
import com.example.model.NowPlayingState
import com.example.model.PlayerStatus
import com.example.model.RecordingUiState
import com.example.model.SortOption
import com.example.model.Station
import com.example.network.NowPlayingPollResult
import com.example.network.NowPlayingService
import com.example.player.RadioPlayerController
import com.example.player.StreamRecorderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class FilteredStationsUiState(
    val totalCatalogCount: Int = 0,
    val filteredCount: Int = 0,
    val displayedStations: List<Station> = emptyList(),
    val allFilteredStations: List<Station> = emptyList(),
    val hasMorePages: Boolean = false
)

/**
 * Coordinates live song title updates across three independent sources:
 * 1) ExoPlayer in-stream metadata events (onMetadata)
 * 2) Direct ICY stream metadata probe (Icy-MetaData: 1)
 * 3) Remote Now-Playing APIs (prod.radio-api.net / api.radio.de)
 *
 * Ensures that if one source stays stuck on the opening title while another source reports a new song,
 * the newly changed song always wins immediately without being overwritten by stale values.
 */
class NowPlayingTitleTracker {
    companion object {
        const val PREVIOUS_TITLE_ECHO_WINDOW_MS = 90_000L
        const val FROZEN_TITLE_MAX_AGE_MS = 480_000L
    }

    @Volatile
    var lastExoTitle: String? = null
        private set

    @Volatile
    var lastIcyProbeTitle: String? = null
        private set

    @Volatile
    var lastApiTitle: String? = null
        private set

    @Volatile
    var currentTitle: String? = null
        private set

    private var currentTitleSinceMs: Long = 0L
    private var previousTitle: String? = null
    private var previousTitleTransitionMs: Long = 0L

    @Synchronized
    fun reset() {
        lastExoTitle = null
        lastIcyProbeTitle = null
        lastApiTitle = null
        currentTitle = null
        currentTitleSinceMs = 0L
        previousTitle = null
        previousTitleTransitionMs = 0L
    }

    private fun isStaleEchoOfPreviousTitle(candidate: String, nowMs: Long): Boolean {
        val prev = previousTitle ?: return false
        return candidate.equals(prev, ignoreCase = true) &&
            (nowMs - previousTitleTransitionMs) in 0..PREVIOUS_TITLE_ECHO_WINDOW_MS
    }

    private fun transitionToNewTitle(newTitle: String, nowMs: Long) {
        val existing = currentTitle
        if (existing != null && !existing.equals(newTitle, ignoreCase = true)) {
            previousTitle = existing
            previousTitleTransitionMs = nowMs
        }
        currentTitle = newTitle
        currentTitleSinceMs = nowMs
    }

    @Synchronized
    fun onExoStreamTitleChanged(exoTitle: String?, nowMs: Long = System.currentTimeMillis()): NowPlayingState? {
        val cleaned = exoTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null) {
            val exoChanged = !cleaned.equals(lastExoTitle, ignoreCase = true)
            lastExoTitle = cleaned
            if (exoChanged && !cleaned.equals(currentTitle, ignoreCase = true)) {
                if (!isStaleEchoOfPreviousTitle(cleaned, nowMs)) {
                    transitionToNewTitle(cleaned, nowMs)
                }
            } else if (currentTitle == null) {
                transitionToNewTitle(cleaned, nowMs)
            }
        } else {
            // Explicit empty/station-name ICY packet arrived in ExoPlayer
            val oldExo = lastExoTitle
            lastExoTitle = null
            if (oldExo != null && oldExo.equals(currentTitle, ignoreCase = true) &&
                lastApiTitle == null && lastIcyProbeTitle == null
            ) {
                previousTitle = currentTitle
                previousTitleTransitionMs = nowMs
                currentTitle = null
                return NowPlayingState.Unavailable
            }
        }
        return currentTitle?.let { NowPlayingState.Available(it) }
    }

    @Synchronized
    fun onPollResult(poll: NowPlayingPollResult, nowMs: Long = System.currentTimeMillis()): NowPlayingState {
        val icy = poll.icyTitle?.trim()?.takeIf { it.isNotEmpty() }
        val api = poll.apiTitle?.trim()?.takeIf { it.isNotEmpty() }

        val isInitialPoll = lastApiTitle == null && lastIcyProbeTitle == null
        val icyChanged = icy != null && !icy.equals(lastIcyProbeTitle, ignoreCase = true)
        val apiChanged = api != null && !api.equals(lastApiTitle, ignoreCase = true)

        if (icy != null) lastIcyProbeTitle = icy
        if (api != null) lastApiTitle = api

        if (isInitialPoll) {
            // On initial poll, prefer API if available (avoids static ICY slogans), else ICY
            val initialWinner = api ?: icy
            if (initialWinner != null && !initialWinner.equals(currentTitle, ignoreCase = true)) {
                transitionToNewTitle(initialWinner, nowMs)
            }
        } else {
            var updated = false
            // If ICY probe changed to a new song different from currentTitle
            if (icyChanged && icy != null &&
                !icy.equals(currentTitle, ignoreCase = true) &&
                !isStaleEchoOfPreviousTitle(icy, nowMs)
            ) {
                transitionToNewTitle(icy, nowMs)
                updated = true
            }
            // If API changed to a new song different from currentTitle
            if (!updated && apiChanged && api != null &&
                !api.equals(currentTitle, ignoreCase = true) &&
                !isStaleEchoOfPreviousTitle(api, nowMs)
            ) {
                transitionToNewTitle(api, nowMs)
                updated = true
            }
            if (!updated && currentTitle == null) {
                val fallback = api ?: icy
                if (fallback != null) {
                    transitionToNewTitle(fallback, nowMs)
                }
            }
        }

        val active = currentTitle
        if (active != null) {
            // If both live poll sources returned null (e.g. talk break) and ExoPlayer also has no active song
            if (icy == null && api == null && lastExoTitle == null && poll.hadSuccessfulResponse) {
                previousTitle = active
                previousTitleTransitionMs = nowMs
                currentTitle = null
                return NowPlayingState.Unavailable
            }
            // Suppress frozen server title if identical for > 8 minutes
            if (currentTitleSinceMs > 0L && (nowMs - currentTitleSinceMs) >= FROZEN_TITLE_MAX_AGE_MS) {
                return NowPlayingState.Unavailable
            }
            return NowPlayingState.Available(active)
        }

        return NowPlayingState.Unavailable
    }
}

class RadioWebViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val PAGE_SIZE = 100
        const val NOW_PLAYING_POLL_INTERVAL_MS = 12_000L
        const val API_METADATA_MAX_AGE_MS = 480_000L
        const val DEFAULT_STATION_ID = "radiolacch"
        const val DEFAULT_STATION_NAME = "RADIO LAC"
    }

    private val catalogRepository = CatalogRepository(application)
    private val favoritesRepository = FavoritesRepository(application)
    private val nowPlayingService = NowPlayingService()
    val playerController = RadioPlayerController(application, viewModelScope)
    val streamRecorderManager = StreamRecorderManager(application, viewModelScope)

    private val _isLoadingCatalog = MutableStateFlow(true)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    private val _allStations = MutableStateFlow<List<Station>>(emptyList())
    val allStations: StateFlow<List<Station>> = _allStations.asStateFlow()

    private val _canonicalGenres = MutableStateFlow<List<GenreItem>>(emptyList())
    val canonicalGenres: StateFlow<List<GenreItem>> = _canonicalGenres.asStateFlow()

    private val _frequentGenres = MutableStateFlow<List<GenreItem>>(emptyList())
    val frequentGenres: StateFlow<List<GenreItem>> = _frequentGenres.asStateFlow()

    private val _metrics = MutableStateFlow(CatalogMetrics())
    val metrics: StateFlow<CatalogMetrics> = _metrics.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(favoritesRepository.loadFavoriteIds())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _selectedTab = MutableStateFlow(
        if (_favoriteIds.value.isNotEmpty()) AppTab.FAVORITES else AppTab.CATALOG
    )
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.NAME_ASC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _visibleLimit = MutableStateFlow(PAGE_SIZE)
    val visibleLimit: StateFlow<Int> = _visibleLimit.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingState>(NowPlayingState.Idle)
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val titleTracker = NowPlayingTitleTracker()

    private val _csvOperationFeedback = MutableStateFlow<String?>(null)
    val csvOperationFeedback: StateFlow<String?> = _csvOperationFeedback.asStateFlow()

    private val _csvOperationIsError = MutableStateFlow(false)
    val csvOperationIsError: StateFlow<Boolean> = _csvOperationIsError.asStateFlow()

    private val _autoPlayCarBluetooth = MutableStateFlow(favoritesRepository.isAutoPlayOnCarConnectEnabled())
    val autoPlayCarBluetooth: StateFlow<Boolean> = _autoPlayCarBluetooth.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    private var nowPlayingPollingJob: Job? = null

    val currentStation: StateFlow<Station?> = playerController.currentStation
    val playerStatus: StateFlow<PlayerStatus> = playerController.playerStatus
    val errorMessage: StateFlow<String?> = playerController.errorMessage
    val volumePercent: StateFlow<Int> = playerController.volumePercent
    val isMuted: StateFlow<Boolean> = playerController.isMuted
    val recordingState: StateFlow<RecordingUiState> = streamRecorderManager.recordingState

    private data class FilterCriteria(
        val query: String,
        val genre: String?,
        val sort: SortOption,
        val limit: Int
    )

    private val filterCriteriaFlow = combine(
        _searchQuery,
        _selectedGenre,
        _sortOption,
        _visibleLimit
    ) { query, genre, sort, limit ->
        FilterCriteria(query, genre, sort, limit)
    }

    val catalogStationsState: StateFlow<FilteredStationsUiState> = combine(
        _allStations,
        filterCriteriaFlow
    ) { stations, criteria ->
        computeFilteredStations(
            sourceStations = stations,
            totalCatalogSize = stations.size,
            criteria = criteria
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FilteredStationsUiState()
        )

    val favoriteStationsState: StateFlow<FilteredStationsUiState> = combine(
        _allStations,
        _favoriteIds,
        filterCriteriaFlow
    ) { stations, favIds, criteria ->
        val favStations = stations.filter { it.id in favIds }
        computeFilteredStations(
            sourceStations = favStations,
            totalCatalogSize = favStations.size,
            criteria = criteria
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FilteredStationsUiState()
        )

    init {
        playerController.onSkipToPreviousStation = {
            skipStation(direction = -1)
        }
        playerController.onSkipToNextStation = {
            skipStation(direction = 1)
        }
        playerController.queueStationsProvider = {
            getActiveNavigationList()
        }
        com.example.player.RadioPlaybackService.favoriteStationsProvider = {
            val favIds = _favoriteIds.value
            _allStations.value.filter { it.id in favIds }
        }
        com.example.player.RadioPlaybackService.activeStationsProvider = {
            getActiveNavigationList()
        }
        com.example.player.RadioPlaybackService.allStationsProvider = {
            _allStations.value
        }
        com.example.player.RadioPlaybackService.onPlayStationByIdCallback = { stationId ->
            val target = _allStations.value.firstOrNull { it.id == stationId }
            if (target != null) {
                if (streamRecorderManager.recordingState.value.isRecording &&
                    streamRecorderManager.recordingState.value.stationId != target.id
                ) {
                    streamRecorderManager.stopRecording()
                }
                titleTracker.reset()
                _nowPlayingState.value = NowPlayingState.Loading
                playerController.playStation(target)
            }
        }
        loadInitialCatalog()
        observeExoStreamMetadataChanges()
        observeNowPlayingTriggers()
        observeResolvedNowPlayingForPlayer()
        observeRecordingStationAndStatus()
    }

    private fun observeExoStreamMetadataChanges() {
        viewModelScope.launch {
            playerController.streamMetadataTitle.collect { exoTitle ->
                val isActive = playerStatus.value == PlayerStatus.PLAYING ||
                    playerStatus.value == PlayerStatus.CONNECTING
                if (!isActive || currentStation.value == null) return@collect
                val updatedState = titleTracker.onExoStreamTitleChanged(exoTitle)
                if (updatedState != null) {
                    _nowPlayingState.value = updatedState
                }
            }
        }
    }

    private fun observeResolvedNowPlayingForPlayer() {
        viewModelScope.launch {
            nowPlayingState.collect { resolved ->
                val titleForBluetooth = (resolved as? NowPlayingState.Available)?.title
                playerController.updateResolvedNowPlayingTitle(titleForBluetooth)
            }
        }
    }

    private fun observeRecordingStationAndStatus() {
        viewModelScope.launch {
            combine(currentStation, playerStatus) { station, status ->
                station to status
            }.collect { (station, status) ->
                val rec = streamRecorderManager.recordingState.value
                if (rec.isRecording) {
                    val stationChanged = station == null || (rec.stationId != null && station.id != rec.stationId)
                    val playbackStopped = status == PlayerStatus.IDLE || status == PlayerStatus.PAUSED || status == PlayerStatus.ERROR
                    if (stationChanged || playbackStopped) {
                        streamRecorderManager.stopRecording()
                    }
                }
            }
        }
    }

    private fun getActiveNavigationList(): List<Station> {
        return when {
            _selectedTab.value == AppTab.FAVORITES && favoriteStationsState.value.allFilteredStations.isNotEmpty() ->
                favoriteStationsState.value.allFilteredStations
            catalogStationsState.value.allFilteredStations.isNotEmpty() ->
                catalogStationsState.value.allFilteredStations
            else -> _allStations.value
        }
    }

    private fun loadInitialCatalog() {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            try {
                val loaded = catalogRepository.loadInitialCatalog()
                applyLoadedCatalog(loaded, isInitialLoad = true)
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    private fun findDefaultStation(stations: List<Station>): Station? {
        return stations.firstOrNull {
            it.id.equals(DEFAULT_STATION_ID, ignoreCase = true) ||
                it.name.equals(DEFAULT_STATION_NAME, ignoreCase = true)
        } ?: stations.firstOrNull()
    }

    private fun applyLoadedCatalog(loaded: LoadedCatalogData, isInitialLoad: Boolean) {
        _allStations.value = loaded.stations
        _canonicalGenres.value = loaded.canonicalGenresAlphabetical
        _frequentGenres.value = loaded.frequentGenresTop12
        _metrics.value = loaded.metrics
        _visibleLimit.value = PAGE_SIZE

        val favIds = _favoriteIds.value
        val matchedFavorites = if (favIds.isNotEmpty()) {
            loaded.stations.filter { it.id in favIds }
        } else {
            emptyList()
        }
        favoritesRepository.saveFavoriteStationsCache(matchedFavorites)
        com.example.player.RadioPlaybackService.notifyCatalogOrFavoritesChanged()

        if (isInitialLoad) {
            val standaloneId = com.example.player.RadioPlaybackService.standaloneActiveStationId
            val standaloneStation = if (standaloneId != null) {
                loaded.stations.firstOrNull { it.id == standaloneId }
            } else {
                null
            }

            if (matchedFavorites.isNotEmpty()) {
                _selectedTab.value = AppTab.FAVORITES
                if (standaloneStation != null) {
                    playerController.playStation(standaloneStation)
                } else {
                    val lastPlayedId = favoritesRepository.loadLastPlayedStation()?.id
                    val initialFavorite = matchedFavorites.firstOrNull { it.id == lastPlayedId }
                        ?: matchedFavorites.first()
                    playerController.preselectStation(initialFavorite, forceReplace = false)
                }
            } else {
                _selectedTab.value = AppTab.CATALOG
                if (standaloneStation != null) {
                    playerController.playStation(standaloneStation)
                } else {
                    val lastPlayedId = favoritesRepository.loadLastPlayedStation()?.id
                    val defaultStation = loaded.stations.firstOrNull { it.id == lastPlayedId }
                        ?: findDefaultStation(loaded.stations)
                    if (defaultStation != null) {
                        playerController.preselectStation(defaultStation, forceReplace = false)
                    }
                }
            }
        } else {
            val activeId = currentStation.value?.id
            if (activeId != null) {
                val updated = loaded.stations.firstOrNull { it.id == activeId }
                if (updated != null) {
                    playerController.syncActiveStationWithCatalog(updated)
                } else if (playerStatus.value == PlayerStatus.IDLE) {
                    val defaultStation = findDefaultStation(loaded.stations)
                    if (defaultStation != null) {
                        playerController.preselectStation(defaultStation, forceReplace = true)
                    } else {
                        playerController.stopAndCloseStation()
                    }
                }
            } else if (playerStatus.value == PlayerStatus.IDLE) {
                val defaultStation = findDefaultStation(loaded.stations)
                if (defaultStation != null) {
                    playerController.preselectStation(defaultStation, forceReplace = true)
                }
            }
        }
    }

    private fun computeFilteredStations(
        sourceStations: List<Station>,
        totalCatalogSize: Int,
        criteria: FilterCriteria
    ): FilteredStationsUiState {
        val normalizedQuery = criteria.query.trim()
        val targetGenre = criteria.genre

        val filtered = if (normalizedQuery.isEmpty() && targetGenre == null) {
            sourceStations
        } else {
            sourceStations.filter { station ->
                val matchesGenre = targetGenre == null || station.genres.any { g ->
                    g.equals(targetGenre, ignoreCase = true)
                }
                if (!matchesGenre) return@filter false

                station.matchesSearchQuery(normalizedQuery)
            }
        }

        val sorted = when (criteria.sort) {
            SortOption.NAME_ASC -> filtered // Already sorted by Name A->Z at parse time
            SortOption.NAME_DESC -> filtered.asReversed()
            SortOption.COUNTRY_ASC -> filtered.sortedWith(CsvCatalogParser.stationCountryComparator)
        }

        val displayed = if (sorted.size <= criteria.limit) {
            sorted
        } else {
            sorted.subList(0, criteria.limit)
        }

        return FilteredStationsUiState(
            totalCatalogCount = totalCatalogSize,
            filteredCount = sorted.size,
            displayedStations = displayed,
            allFilteredStations = sorted,
            hasMorePages = sorted.size > displayed.size
        )
    }

    fun skipToPreviousStation() {
        skipStation(direction = -1)
    }

    fun skipToNextStation() {
        skipStation(direction = 1)
    }

    private fun skipStation(direction: Int) {
        val activeList = when {
            _selectedTab.value == AppTab.FAVORITES && favoriteStationsState.value.allFilteredStations.isNotEmpty() ->
                favoriteStationsState.value.allFilteredStations
            catalogStationsState.value.allFilteredStations.isNotEmpty() ->
                catalogStationsState.value.allFilteredStations
            else -> _allStations.value
        }
        if (activeList.isEmpty()) return

        val currentId = currentStation.value?.id
        val currentIndex = if (currentId != null) {
            activeList.indexOfFirst { it.id == currentId }
        } else {
            -1
        }

        val nextIndex = if (currentIndex == -1) {
            0
        } else {
            (currentIndex + direction).mod(activeList.size)
        }

        val nextStation = activeList[nextIndex]
        if (streamRecorderManager.recordingState.value.isRecording &&
            streamRecorderManager.recordingState.value.stationId != nextStation.id
        ) {
            streamRecorderManager.stopRecording()
        }
        titleTracker.reset()
        _nowPlayingState.value = NowPlayingState.Loading
        playerController.playStation(nextStation)
    }

    private data class NowPlayingPollTrigger(
        val stationId: String?,
        val stationName: String,
        val stationLocation: String,
        val streamUrl: String,
        val isHls: Boolean,
        val isActivePlayback: Boolean
    )

    private fun observeNowPlayingTriggers() {
        viewModelScope.launch {
            combine(
                currentStation,
                playerStatus
            ) { station, status ->
                NowPlayingPollTrigger(
                    stationId = station?.id,
                    stationName = station?.name.orEmpty(),
                    stationLocation = station?.locationLabel.orEmpty(),
                    streamUrl = station?.streamUrl.orEmpty(),
                    isHls = station?.isHls == true,
                    isActivePlayback = status == PlayerStatus.PLAYING || status == PlayerStatus.CONNECTING
                )
            }
                .distinctUntilChanged()
                .collect { trigger ->
                    nowPlayingPollingJob?.cancel()
                    nowPlayingPollingJob = null

                    val stationId = trigger.stationId
                    if (stationId == null || !trigger.isActivePlayback) {
                        if (stationId == null || playerStatus.value == PlayerStatus.IDLE) {
                            titleTracker.reset()
                            _nowPlayingState.value = NowPlayingState.Idle
                        }
                        return@collect
                    }

                    nowPlayingPollingJob = viewModelScope.launch {
                        titleTracker.reset()
                        // Seed tracker if ExoPlayer already emitted an initial stream title
                        val existingExo = playerController.streamMetadataTitle.value
                        if (!existingExo.isNullOrBlank()) {
                            titleTracker.onExoStreamTitleChanged(existingExo)?.let {
                                _nowPlayingState.value = it
                            }
                        } else {
                            _nowPlayingState.value = NowPlayingState.Loading
                        }

                        while (isActive) {
                            val poll = nowPlayingService.pollNowPlayingSources(
                                stationId = stationId,
                                stationName = trigger.stationName,
                                stationLocation = trigger.stationLocation,
                                streamUrl = trigger.streamUrl,
                                isHls = trigger.isHls,
                                previousApiTitle = titleTracker.lastApiTitle
                            )
                            if (!isActive || currentStation.value?.id != stationId) break

                            // Re-check latest ExoPlayer stream metadata in case it updated during HTTP call
                            playerController.streamMetadataTitle.value?.let { latestExo ->
                                titleTracker.onExoStreamTitleChanged(latestExo)
                            }
                            _nowPlayingState.value = titleTracker.onPollResult(poll)

                            delay(NOW_PLAYING_POLL_INTERVAL_MS)
                        }
                    }
                }
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        val wasBackground = !_isAppInForeground.value
        _isAppInForeground.value = inForeground
        // Always trigger an immediate now-playing refresh when returning to foreground while playing
        if (inForeground && wasBackground) {
            val station = currentStation.value
            val stationId = station?.id
            val isActive = playerStatus.value == PlayerStatus.PLAYING || playerStatus.value == PlayerStatus.CONNECTING
            if (station != null && stationId != null && isActive) {
                viewModelScope.launch {
                    val poll = nowPlayingService.pollNowPlayingSources(
                        stationId = stationId,
                        stationName = station.name,
                        stationLocation = station.locationLabel,
                        streamUrl = station.streamUrl,
                        isHls = station.isHls,
                        previousApiTitle = titleTracker.lastApiTitle
                    )
                    if (currentStation.value?.id == stationId) {
                        _nowPlayingState.value = titleTracker.onPollResult(poll)
                    }
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        _visibleLimit.value = PAGE_SIZE
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _visibleLimit.value = PAGE_SIZE
    }

    fun selectGenreFilter(genre: String?) {
        _selectedGenre.value = genre
        _visibleLimit.value = PAGE_SIZE
    }

    fun toggleGenreBadge(genre: String) {
        val current = _selectedGenre.value
        _selectedGenre.value = if (current != null && current.equals(genre, ignoreCase = true)) {
            null
        } else {
            genre
        }
        _visibleLimit.value = PAGE_SIZE
    }

    fun selectGenreFromGenresTab(genre: String) {
        _selectedGenre.value = genre
        _visibleLimit.value = PAGE_SIZE
        _selectedTab.value = AppTab.CATALOG
    }

    fun updateSortOption(option: SortOption) {
        _sortOption.value = option
        _visibleLimit.value = PAGE_SIZE
    }

    fun resetFilters() {
        _searchQuery.value = ""
        _selectedGenre.value = null
        _sortOption.value = SortOption.NAME_ASC
        _visibleLimit.value = PAGE_SIZE
    }

    fun loadMoreStations() {
        _visibleLimit.value += PAGE_SIZE
    }

    fun toggleFavorite(stationId: String) {
        val updated = LinkedHashSet(_favoriteIds.value)
        if (!updated.remove(stationId)) {
            updated.add(stationId)
        }
        _favoriteIds.value = updated
        favoritesRepository.saveFavoriteIds(updated)
        val matchedFavorites = _allStations.value.filter { it.id in updated }
        favoritesRepository.saveFavoriteStationsCache(matchedFavorites)
        com.example.player.RadioPlaybackService.notifyCatalogOrFavoritesChanged()
    }

    fun toggleAutoPlayCarBluetooth() {
        val nextValue = !_autoPlayCarBluetooth.value
        _autoPlayCarBluetooth.value = nextValue
        favoritesRepository.setAutoPlayOnCarConnectEnabled(nextValue)
    }

    fun onStationCardClicked(station: Station) {
        if (streamRecorderManager.recordingState.value.isRecording) {
            streamRecorderManager.stopRecording()
        }
        if (currentStation.value?.id != station.id) {
            titleTracker.reset()
            _nowPlayingState.value = NowPlayingState.Loading
        }
        playerController.onStationCardClicked(station)
    }

    fun togglePlayPause() {
        if (streamRecorderManager.recordingState.value.isRecording &&
            playerStatus.value == PlayerStatus.PLAYING
        ) {
            streamRecorderManager.stopRecording()
        }
        playerController.togglePlayPause()
    }

    fun toggleRecording() {
        val station = currentStation.value ?: return
        if (streamRecorderManager.recordingState.value.isRecording) {
            streamRecorderManager.stopRecording()
        } else if (playerStatus.value == PlayerStatus.PLAYING) {
            streamRecorderManager.startRecording(station)
        }
    }

    fun dismissRecordingFeedback() {
        streamRecorderManager.clearFeedbackMessage()
    }

    fun retryPlayback() {
        playerController.retryCurrentStation()
    }

    fun stopAndClosePlayer() {
        if (streamRecorderManager.recordingState.value.isRecording) {
            streamRecorderManager.stopRecording()
        }
        playerController.stopAndCloseStation()
    }

    fun dismissErrorBanner() {
        playerController.dismissErrorBanner()
    }

    fun setVolumePercent(percent: Int) {
        playerController.setVolumePercent(percent)
    }

    fun toggleMute() {
        playerController.toggleMute()
    }

    fun uploadCustomCatalog(uri: Uri) {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            _csvOperationFeedback.value = null
            _csvOperationIsError.value = false
            val result = catalogRepository.replaceCatalogFromUri(uri)
            result.fold(
                onSuccess = { loaded ->
                    applyLoadedCatalog(loaded, isInitialLoad = false)
                    _csvOperationIsError.value = false
                    _csvOperationFeedback.value =
                        "Catalogo \"${loaded.metrics.activeFileName}\" caricato con successo (${loaded.metrics.uniqueStations} stazioni uniche)."
                },
                onFailure = { error ->
                    _csvOperationIsError.value = true
                    _csvOperationFeedback.value =
                        "Errore nel caricamento del CSV: ${error.localizedMessage ?: "formato non valido"}"
                }
            )
            _isLoadingCatalog.value = false
        }
    }

    fun restoreBundleCatalog() {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            _csvOperationFeedback.value = null
            _csvOperationIsError.value = false
            try {
                val loaded = catalogRepository.restoreBundleCatalog()
                applyLoadedCatalog(loaded, isInitialLoad = false)
                _csvOperationIsError.value = false
                _csvOperationFeedback.value =
                    "Catalogo di bundle ripristinato (${loaded.metrics.uniqueStations} stazioni uniche)."
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    fun clearCsvFeedback() {
        _csvOperationFeedback.value = null
        _csvOperationIsError.value = false
    }

    override fun onCleared() {
        super.onCleared()
        nowPlayingPollingJob?.cancel()
        streamRecorderManager.stopRecording()
        com.example.player.RadioPlaybackService.clearViewModelProviders()
        playerController.release()
    }
}
