package com.example.network

import com.example.model.NowPlayingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NowPlayingPollResult(
    val apiTitle: String? = null,
    val icyTitle: String? = null,
    val hadSuccessfulResponse: Boolean = false
)

class NowPlayingService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private val ENDPOINT_BASES = listOf(
            "https://prod.radio-api.net/stations/now-playing?stationIds=",
            "https://api.radio.de/stations/now-playing?stationIds="
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; RadioWeb/1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val ICY_USER_AGENT =
            "VLC/3.0.20 LibVLC/3.0.20"
        private const val MAX_ICY_METAINT_BYTES = 65_536

        private val STREAM_TITLE_REGEX =
            Regex("""StreamTitle\s*=\s*'([^']*)'\s*;?""", RegexOption.IGNORE_CASE)

        fun extractIcyStreamTitle(rawMetadataBlock: String): String? {
            val match = STREAM_TITLE_REGEX.find(rawMetadataBlock)
            if (match != null) {
                return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val prefixIdx = rawMetadataBlock.indexOf("StreamTitle=", ignoreCase = true)
            if (prefixIdx >= 0) {
                val after = rawMetadataBlock.substring(prefixIdx + "StreamTitle=".length).trim()
                val cleaned = after
                    .substringBefore("';")
                    .substringBefore(';')
                    .trim('\'', '"', ' ', '\u0000')
                return cleaned.takeIf { it.isNotEmpty() }
            }
            return null
        }

        fun cleanAndValidateSongTitle(
            rawTitle: String?,
            stationName: String = "",
            stationId: String = "",
            stationLocation: String = ""
        ): String? {
            if (rawTitle.isNullOrBlank()) return null
            val decoded = rawTitle
                .replace("\u0000", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('-', '•', '|', ':', ';')
                .trim()

            if (decoded.length < 2) return null
            val lower = decoded.lowercase()

            // Known stuck/broken Icecast encoder test/frozen strings or generic status placeholders
            if (lower == "unknown" ||
                lower == "null" ||
                lower == "none" ||
                lower == "n/a" ||
                lower == "no title" ||
                lower == "live" ||
                lower == "on air" ||
                lower == "stream" ||
                lower == "diretta" ||
                lower == "audio" ||
                lower == "default" ||
                lower == "counting crows - mr jones" ||
                lower.startsWith("connessione in corso") ||
                lower.startsWith("in pausa") ||
                lower.startsWith("in diretta") ||
                lower.startsWith("caricamento brano") ||
                lower.startsWith("brano non disponibile") ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("www.")
            ) {
                return null
            }

            if (stationName.isNotBlank() && decoded.equals(stationName.trim(), ignoreCase = true)) {
                return null
            }
            if (stationId.isNotBlank() && decoded.equals(stationId.trim(), ignoreCase = true)) {
                return null
            }
            if (stationLocation.isNotBlank() && decoded.equals(stationLocation.trim(), ignoreCase = true)) {
                return null
            }

            // Filter out "Artist - Title" where both sides are empty or identical to station name
            val parts = decoded.split(" - ", limit = 2).map { it.trim() }
            if (parts.size == 2) {
                val left = parts[0]
                val right = parts[1]
                if (left.isEmpty() && right.isEmpty()) return null
                if (stationName.isNotBlank() &&
                    left.equals(stationName.trim(), ignoreCase = true) &&
                    (right.isEmpty() || right.equals("live", ignoreCase = true) || right.equals("diretta", ignoreCase = true))
                ) {
                    return null
                }
            }

            return decoded
        }
    }

    /**
     * Queries both remote Now-Playing APIs (prod.radio-api.net & api.radio.de) AND, if a direct
     * non-HLS streamUrl is provided, probes the Icecast/Shoutcast stream's live ICY metadata block.
     *
     * If the first API endpoint returns the exact same title as [previousApiTitle], the second
     * endpoint is also queried so a fresher update on the secondary server is never missed.
     */
    suspend fun pollNowPlayingSources(
        stationId: String,
        stationName: String = "",
        stationLocation: String = "",
        streamUrl: String = "",
        isHls: Boolean = false,
        previousApiTitle: String? = null
    ): NowPlayingPollResult = withContext(Dispatchers.IO) {
        val apiPair = fetchFromApiEndpoints(
            stationId = stationId,
            stationName = stationName,
            stationLocation = stationLocation,
            previousApiTitle = previousApiTitle
        )

        val trimmedUrl = streamUrl.trim()
        val isHlsStream = isHls || trimmedUrl.contains(".m3u8", ignoreCase = true)
        val icyTitle = if (trimmedUrl.isNotEmpty() && !isHlsStream) {
            fetchIcyStreamTitle(
                streamUrl = trimmedUrl,
                stationName = stationName,
                stationId = stationId,
                stationLocation = stationLocation
            )
        } else {
            null
        }

        NowPlayingPollResult(
            apiTitle = apiPair.first,
            icyTitle = icyTitle,
            hadSuccessfulResponse = apiPair.second || !icyTitle.isNullOrEmpty()
        )
    }

    /**
     * Legacy helper returning a single NowPlayingState.
     */
    suspend fun fetchNowPlaying(
        stationId: String,
        stationName: String = "",
        stationLocation: String = "",
        streamUrl: String = "",
        isHls: Boolean = false,
        previousApiTitle: String? = null
    ): NowPlayingState? {
        val poll = pollNowPlayingSources(
            stationId = stationId,
            stationName = stationName,
            stationLocation = stationLocation,
            streamUrl = streamUrl,
            isHls = isHls,
            previousApiTitle = previousApiTitle
        )
        val bestTitle = poll.icyTitle ?: poll.apiTitle
        return when {
            !bestTitle.isNullOrEmpty() -> NowPlayingState.Available(bestTitle)
            poll.hadSuccessfulResponse -> NowPlayingState.Unavailable
            else -> null
        }
    }

    private fun fetchFromApiEndpoints(
        stationId: String,
        stationName: String,
        stationLocation: String,
        previousApiTitle: String?
    ): Pair<String?, Boolean> {
        val cleanId = stationId.trim()
        if (cleanId.isEmpty()) return null to true

        val encodedId = try {
            URLEncoder.encode(cleanId, "UTF-8")
        } catch (_: Exception) {
            cleanId
        }

        var hadSuccessfulHttpResponse = false
        var fallbackCandidateTitle: String? = null
        val cacheBuster = System.currentTimeMillis()

        for (baseUrl in ENDPOINT_BASES) {
            // Try with cache-busting query param first to bypass CDN edge cache, then raw URL if rejected
            val candidateUrls = listOf(
                "$baseUrl$encodedId&_=$cacheBuster",
                "$baseUrl$encodedId"
            )

            for (targetUrl in candidateUrls) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
                        .header("Pragma", "no-cache")
                        .header("Expires", "0")
                        .header("Origin", "https://www.radio.net")
                        .header("Referer", "https://www.radio.net/")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            hadSuccessfulHttpResponse = true
                            val bodyString = response.body?.string().orEmpty()
                            if (bodyString.isNotBlank()) {
                                val jsonArray = JSONArray(bodyString)
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.optJSONObject(i) ?: continue
                                    val entryId = obj.optString("stationId").trim()
                                    val rawTitle = obj.optString("title").trim()
                                    if (entryId.equals(cleanId, ignoreCase = true) || jsonArray.length() == 1) {
                                        val validTitle = cleanAndValidateSongTitle(
                                            rawTitle = rawTitle,
                                            stationName = stationName,
                                            stationId = cleanId,
                                            stationLocation = stationLocation
                                        )
                                        if (!validTitle.isNullOrEmpty()) {
                                            // If this endpoint returned a brand-new title different from previousApiTitle, return immediately!
                                            if (previousApiTitle.isNullOrBlank() ||
                                                !validTitle.equals(previousApiTitle, ignoreCase = true)
                                            ) {
                                                return validTitle to true
                                            }
                                            // Otherwise remember it and check the next endpoint in case the other server updated faster
                                            if (fallbackCandidateTitle == null) {
                                                fallbackCandidateTitle = validTitle
                                            }
                                        }
                                    }
                                }
                            }
                            // 200 OK received from this baseUrl, no need to retry without cacheBuster on the same host
                            break
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // Try next URL / fallback endpoint
                }
            }
        }

        return fallbackCandidateTitle to hadSuccessfulHttpResponse
    }

    /**
     * Performs a lightweight HTTP GET with `Icy-MetaData: 1` to read the first live ICY metadata block
     * directly from the Icecast/Shoutcast stream and closes the stream immediately.
     */
    private fun fetchIcyStreamTitle(
        streamUrl: String,
        stationName: String,
        stationId: String,
        stationLocation: String
    ): String? {
        return try {
            val icyClient = httpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(streamUrl)
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .header("User-Agent", ICY_USER_AGENT)
                .header("Icy-MetaData", "1")
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
                .header("Connection", "close")
                .get()
                .build()

            icyClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val metaIntHeader = response.header("icy-metaint")
                    ?: response.header("Icy-MetaInt")
                    ?: return null
                val metaInt = metaIntHeader.trim().toIntOrNull() ?: return null
                if (metaInt !in 1..MAX_ICY_METAINT_BYTES) return null

                val input = response.body?.byteStream() ?: return null
                // Skip exactly metaInt bytes of audio data to reach the ICY metadata block
                var remainingToSkip = metaInt
                val skipBuffer = ByteArray(4096)
                while (remainingToSkip > 0) {
                    val toRead = minOf(remainingToSkip, skipBuffer.size)
                    val read = input.read(skipBuffer, 0, toRead)
                    if (read <= 0) return null
                    remainingToSkip -= read
                }

                // Read the 1-byte metadata length specifier (multiply by 16 for byte length)
                val lengthSpecifier = input.read()
                if (lengthSpecifier <= 0) return null
                val metaByteLength = lengthSpecifier * 16
                if (metaByteLength > 4096) return null

                val metaBytes = ByteArray(metaByteLength)
                var metaOffset = 0
                while (metaOffset < metaByteLength) {
                    val read = input.read(metaBytes, metaOffset, metaByteLength - metaOffset)
                    if (read <= 0) break
                    metaOffset += read
                }
                if (metaOffset <= 0) return null

                // Decode as UTF-8, or fallback to ISO-8859-1 if replacement chars appear
                val utf8String = String(metaBytes, 0, metaOffset, Charsets.UTF_8)
                val rawMeta = if (utf8String.contains('\uFFFD')) {
                    String(metaBytes, 0, metaOffset, Charsets.ISO_8859_1)
                } else {
                    utf8String
                }

                val extracted = extractIcyStreamTitle(rawMeta) ?: return null
                cleanAndValidateSongTitle(
                    rawTitle = extracted,
                    stationName = stationName,
                    stationId = stationId,
                    stationLocation = stationLocation
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }
}

