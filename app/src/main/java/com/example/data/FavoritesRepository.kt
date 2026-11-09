package com.example.data

import android.content.Context
import com.example.model.Station
import org.json.JSONArray
import org.json.JSONObject

class FavoritesRepository(context: Context) {

    companion object {
        private const val PREFS_FILE = "radioweb_preferences"
        const val FAVORITES_KEY = "radioweb_favorites_v1"
        private const val FAVORITE_STATIONS_CACHE_KEY = "radioweb_favorite_stations_cache_v1"
        private const val LAST_PLAYED_STATION_KEY = "radioweb_last_played_station_v1"
        private const val AUTO_PLAY_CAR_BLUETOOTH_KEY = "radioweb_auto_play_car_bt_v1"
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun loadFavoriteIds(): Set<String> {
        val raw = prefs.getString(FAVORITES_KEY, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            val result = LinkedHashSet<String>(array.length())
            for (i in 0 until array.length()) {
                val id = array.optString(i).trim()
                if (id.isNotEmpty()) {
                    result.add(id)
                }
            }
            result
        } catch (_: Exception) {
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    fun saveFavoriteIds(ids: Set<String>) {
        val jsonArray = JSONArray()
        ids.forEach { jsonArray.put(it) }
        prefs.edit().putString(FAVORITES_KEY, jsonArray.toString()).apply()
    }

    /**
     * Caches full Station metadata for favorites so RadioPlaybackService can immediately
     * start playback and populate the car Bluetooth browse queue even when the app is closed.
     */
    fun saveFavoriteStationsCache(stations: List<Station>) {
        try {
            val array = JSONArray()
            stations.forEach { st ->
                array.put(stationToJson(st))
            }
            prefs.edit().putString(FAVORITE_STATIONS_CACHE_KEY, array.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun loadFavoriteStationsCache(): List<Station> {
        val raw = prefs.getString(FAVORITE_STATIONS_CACHE_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Station>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                jsonToStation(obj)?.let { list.add(it) }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLastPlayedStation(station: Station) {
        try {
            prefs.edit().putString(LAST_PLAYED_STATION_KEY, stationToJson(station).toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun loadLastPlayedStation(): Station? {
        val raw = prefs.getString(LAST_PLAYED_STATION_KEY, null) ?: return null
        return try {
            jsonToStation(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun isAutoPlayOnCarConnectEnabled(): Boolean {
        return prefs.getBoolean(AUTO_PLAY_CAR_BLUETOOTH_KEY, true)
    }

    fun setAutoPlayOnCarConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_PLAY_CAR_BLUETOOTH_KEY, enabled).apply()
    }

    private fun stationToJson(station: Station): JSONObject {
        return JSONObject().apply {
            put("id", station.id)
            put("name", station.name)
            put("city", station.city)
            put("country", station.country)
            put("genres", JSONArray(station.genres))
            put("logo100", station.logo100)
            put("logo175", station.logo175)
            put("logo300", station.logo300)
            put("preferredLogo", station.preferredLogo)
            put("streamUrl", station.streamUrl)
            put("format", station.format)
            put("isHls", station.isHls)
        }
    }

    private fun jsonToStation(obj: JSONObject): Station? {
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        val streamUrl = obj.optString("streamUrl").trim()
        if (id.isEmpty() || name.isEmpty() || streamUrl.isEmpty()) return null

        val city = obj.optString("city").trim()
        val country = obj.optString("country").trim()
        val genresArray = obj.optJSONArray("genres")
        val genres = if (genresArray != null) {
            buildList(genresArray.length()) {
                for (i in 0 until genresArray.length()) {
                    val g = genresArray.optString(i).trim()
                    if (g.isNotEmpty()) add(g)
                }
            }
        } else {
            emptyList()
        }

        val logo100 = obj.optString("logo100").trim()
        val logo175 = obj.optString("logo175").trim()
        val logo300 = obj.optString("logo300").trim()
        val availableLogos = buildList(3) {
            if (logo175.isNotEmpty()) add(logo175)
            if (logo100.isNotEmpty() && logo100 != logo175) add(logo100)
            if (logo300.isNotEmpty() && logo300 != logo175 && logo300 != logo100) add(logo300)
        }
        val preferredLogo = obj.optString("preferredLogo").trim().ifEmpty {
            availableLogos.firstOrNull().orEmpty()
        }
        val format = obj.optString("format", "MP3").trim()
        val isHls = obj.optBoolean("isHls", streamUrl.contains(".m3u8", ignoreCase = true))

        return Station(
            id = id,
            name = name,
            city = city,
            country = country,
            genres = genres,
            logo100 = logo100,
            logo175 = logo175,
            logo300 = logo300,
            preferredLogo = preferredLogo,
            availableLogos = availableLogos,
            streamUrl = streamUrl,
            format = format,
            isHls = isHls,
            initials = Station.computeInitials(name)
        )
    }
}
