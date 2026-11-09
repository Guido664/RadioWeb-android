package com.example.model

enum class PlayerStatus(val wireValue: String) {
    IDLE("idle"),
    CONNECTING("connecting"),
    PLAYING("playing"),
    PAUSED("paused"),
    ERROR("error")
}

data class Station(
    val id: String,
    val name: String,
    val city: String,
    val country: String,
    val genres: List<String>,
    val logo100: String,
    val logo175: String,
    val logo300: String,
    val preferredLogo: String,
    val availableLogos: List<String>,
    val streamUrl: String,
    val format: String,
    val isHls: Boolean,
    val searchKey: String = "",
    val initials: String
) {
    val displayFormat: String
        get() = if (isHls) "HLS" else format.ifBlank { "UNKNOWN" }

    val locationLabel: String
        get() = when {
            city.isNotBlank() && country.isNotBlank() -> "$city, $country"
            city.isNotBlank() -> city
            country.isNotBlank() -> country
            else -> "Internazionale"
        }

    /**
     * Matches if the query is a prefix of:
     * - station name (from the start of the name)
     * - city
     * - country (nation)
     * - any of the station's genres
     */
    fun matchesSearchQuery(rawQuery: String): Boolean {
        val q = rawQuery.trim()
        if (q.isEmpty()) return true
        return name.trimStart().startsWith(q, ignoreCase = true) ||
            city.trimStart().startsWith(q, ignoreCase = true) ||
            country.trimStart().startsWith(q, ignoreCase = true) ||
            genres.any { genre -> genre.trimStart().startsWith(q, ignoreCase = true) }
    }

    companion object {
        fun computeInitials(stationName: String): String {
            var firstChar = '\u0000'
            var secondChar = '\u0000'
            var inWord = false
            var wordCount = 0

            for (i in 0 until stationName.length) {
                val ch = stationName[i]
                if (ch.isLetterOrDigit()) {
                    if (!inWord) {
                        inWord = true
                        wordCount++
                        if (wordCount == 1) {
                            firstChar = ch.uppercaseChar()
                        } else if (wordCount == 2) {
                            secondChar = ch.uppercaseChar()
                            break
                        }
                    } else if (wordCount == 1 && secondChar == '\u0000') {
                        secondChar = ch.uppercaseChar()
                    }
                } else if (ch.isWhitespace()) {
                    inWord = false
                }
            }

            return when {
                firstChar != '\u0000' && secondChar != '\u0000' -> "$firstChar$secondChar"
                firstChar != '\u0000' -> "$firstChar"
                else -> "RW"
            }
        }
    }
}

data class GenreItem(
    val name: String,
    val count: Int
)

data class CatalogMetrics(
    val uniqueStations: Int = 0,
    val totalRowsRead: Int = 0,
    val duplicatesFiltered: Int = 0,
    val invalidRowsFiltered: Int = 0,
    val genresCount: Int = 0,
    val activeFileName: String = "catalog_stations.csv",
    val isCustomCatalog: Boolean = false,
    val isSmallCatalog: Boolean = false
)

sealed interface NowPlayingState {
    data object Idle : NowPlayingState
    data object Loading : NowPlayingState
    data class Available(val title: String) : NowPlayingState
    data object Unavailable : NowPlayingState
}

enum class SortOption(val label: String) {
    NAME_ASC("Nome A→Z"),
    NAME_DESC("Nome Z→A"),
    COUNTRY_ASC("Paese A→Z")
}

enum class AppTab(val label: String) {
    CATALOG("Catalogo"),
    FAVORITES("Preferiti"),
    GENRES("Generi"),
    CSV_DATA("Dati CSV")
}

data class RecordingUiState(
    val isRecording: Boolean = false,
    val stationId: String? = null,
    val stationName: String? = null,
    val genreFolder: String? = null,
    val fileName: String? = null,
    val elapsedSeconds: Long = 0L,
    val bytesWritten: Long = 0L,
    val lastFeedbackMessage: String? = null,
    val isErrorFeedback: Boolean = false
) {
    val formattedDuration: String
        get() {
            val mins = elapsedSeconds / 60
            val secs = elapsedSeconds % 60
            return String.format( java.util.Locale.US, "%02d:%02d", mins, secs)
        }
}

