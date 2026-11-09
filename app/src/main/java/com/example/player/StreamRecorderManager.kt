package com.example.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.example.model.RecordingUiState
import com.example.model.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

class StreamRecorderManager(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val ROOT_FOLDER_NAME = "Radioweb record"
        const val UNSPECIFIED_GENRE_FOLDER = "Generi non specificati"
        private const val PREFS_NAME = "radioweb_recordings_prefs"
        private const val KEY_RECORDED_ENTRIES = "radioweb_recorded_entries_v1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; RadioWeb/1.0) ExoPlayerLib/2.19"

        private val INVALID_FS_CHARS_REGEX = Regex("""[/\\:*?"<>|\u0000-\u001F]""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        /**
         * Sanitizes a station or folder name by removing invalid filesystem characters:
         * / \ : * ? " < > |
         */
        fun sanitizeFileSystemName(raw: String, fallback: String = "Stazione"): String {
            val cleaned = raw
                .replace(INVALID_FS_CHARS_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
                .trimEnd('.')
                .trim()
            return cleaned.ifEmpty { fallback }
        }

        /**
         * Resolves the subfolder name from the station's first genre (genres[0]),
         * or "Generi non specificati" if the station has no genres.
         */
        fun resolveGenreFolderName(station: Station): String {
            val firstGenre = station.genres.firstOrNull()?.trim().orEmpty()
            if (firstGenre.isEmpty()) {
                return UNSPECIFIED_GENRE_FOLDER
            }
            return sanitizeFileSystemName(firstGenre, fallback = UNSPECIFIED_GENRE_FOLDER)
        }

        /**
         * Determines the raw stream file extension and MIME type without transcoding:
         * - MP3 -> .mp3 (audio/mpeg)
         * - AAC -> .aac (audio/aac)
         * - OGG -> .ogg (audio/ogg)
         */
        fun resolveExtensionAndMime(station: Station): Pair<String, String> {
            val fmt = station.format.trim().uppercase(Locale.ROOT)
            val lowerUrl = station.streamUrl.trim().lowercase(Locale.ROOT)
                .substringBefore('?')
                .substringBefore('#')

            return when {
                fmt == "MP3" || lowerUrl.endsWith(".mp3") -> "mp3" to "audio/mpeg"
                fmt == "AAC" || fmt == "AAC+" || fmt == "AACP" || lowerUrl.endsWith(".aac") -> "aac" to "audio/aac"
                fmt == "OGG" || fmt == "OPUS" || fmt == "VORBIS" || lowerUrl.endsWith(".ogg") || lowerUrl.endsWith(".oga") ->
                    "ogg" to "audio/ogg"
                lowerUrl.contains(".aac") -> "aac" to "audio/aac"
                lowerUrl.contains(".ogg") -> "ogg" to "audio/ogg"
                else -> "mp3" to "audio/mpeg"
            }
        }

        /**
         * Builds the recording file name: "<nomeStazione> <yyyyMMdd-HHmm>.<ext>"
         * Example: "Radio Swiss Jazz 20251101-1430.mp3"
         */
        fun buildRecordingFileName(
            stationName: String,
            timestampMillis: Long,
            extension: String
        ): String {
            val safeStationName = sanitizeFileSystemName(stationName, fallback = "Radio")
            val dateFormat = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
            val timestampPart = dateFormat.format(Date(timestampMillis))
            val cleanExt = extension.trim().trimStart('.').lowercase(Locale.ROOT).ifEmpty { "mp3" }
            return "$safeStationName $timestampPart.$cleanExt"
        }

        fun formatDurationMmSs(seconds: Long): String {
            val mins = (seconds / 60).coerceAtLeast(0)
            val secs = (seconds % 60).coerceAtLeast(0)
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }
        /**
         * Resolves a unique file name in the target genre folder by appending " (2)", " (3)", etc.
         * if a recording for the same station was already saved within the same minute.
         */
        fun buildIndexedFileName(baseFileName: String, index: Int): String {
            if (index <= 1) return baseFileName
            val dotIndex = baseFileName.lastIndexOf('.')
            return if (dotIndex > 0) {
                val namePart = baseFileName.substring(0, dotIndex)
                val extPart = baseFileName.substring(dotIndex)
                "$namePart ($index)$extPart"
            } else {
                "$baseFileName ($index)"
            }
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(16, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _recordingState = MutableStateFlow(RecordingUiState())
    val recordingState: StateFlow<RecordingUiState> = _recordingState.asStateFlow()

    private var recordingJob: Job? = null
    private var timerJob: Job? = null

    @Volatile
    private var activeHttpCall: okhttp3.Call? = null

    @Volatile
    private var isStopping = false

    private data class ActiveOutputTarget(
        val outputStream: OutputStream,
        val mediaStoreUri: Uri? = null,
        val directFile: File? = null,
        val genreFolder: String,
        val fileName: String
    )

    fun toggleRecording(station: Station) {
        if (_recordingState.value.isRecording) {
            stopRecording()
        } else {
            startRecording(station)
        }
    }

    private fun resolveUniqueRecordingFileName(genreFolder: String, baseFileName: String): String {
        var candidate = baseFileName
        var attempt = 1
        while (isDuplicateRecordingCaseInsensitive(genreFolder, candidate) && attempt < 100) {
            attempt++
            candidate = buildIndexedFileName(baseFileName, attempt)
        }
        return candidate
    }

    fun startRecording(station: Station, timestampMillis: Long = System.currentTimeMillis()) {
        val previousJob = recordingJob
        if (_recordingState.value.isRecording) {
            stopRecording()
        }

        val genreFolder = resolveGenreFolderName(station)
        val (extension, mimeType) = resolveExtensionAndMime(station)
        val baseFileName = buildRecordingFileName(
            stationName = station.name,
            timestampMillis = timestampMillis,
            extension = extension
        )
        val fileName = resolveUniqueRecordingFileName(genreFolder, baseFileName)

        isStopping = false
        _recordingState.value = RecordingUiState(
            isRecording = true,
            stationId = station.id,
            stationName = station.name,
            genreFolder = genreFolder,
            fileName = fileName,
            elapsedSeconds = 0L,
            bytesWritten = 0L,
            lastFeedbackMessage = null,
            isErrorFeedback = false
        )

        val startRealtimeMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && _recordingState.value.isRecording) {
                delay(1_000L)
                val elapsed = ((System.currentTimeMillis() - startRealtimeMs) / 1_000L).coerceAtLeast(0L)
                if (_recordingState.value.isRecording) {
                    _recordingState.value = _recordingState.value.copy(elapsedSeconds = elapsed)
                }
            }
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                previousJob?.join()
            } catch (_: Exception) {
            }
            var target: ActiveOutputTarget? = null
            var totalBytes = 0L
            try {
                val finalFileName = resolveUniqueRecordingFileName(genreFolder, fileName)
                if (finalFileName != fileName && _recordingState.value.isRecording) {
                    _recordingState.value = _recordingState.value.copy(fileName = finalFileName)
                }

                target = openOutputTarget(genreFolder, finalFileName, mimeType)

                val streamUrl = station.streamUrl.trim()
                val lowerUrl = streamUrl.lowercase(Locale.ROOT)
                val isHlsStream = station.isHls || lowerUrl.contains(".m3u8")

                if (isHlsStream) {
                    totalBytes = recordHlsSegmentsLoop(
                        playlistUrl = streamUrl,
                        outputStream = target.outputStream
                    ) { written ->
                        totalBytes = written
                        _recordingState.value = _recordingState.value.copy(bytesWritten = written)
                    }
                } else {
                    totalBytes = recordDirectStreamLoop(
                        streamUrl = streamUrl,
                        outputStream = target.outputStream
                    ) { written ->
                        totalBytes = written
                        _recordingState.value = _recordingState.value.copy(bytesWritten = written)
                    }
                }

                // Finalize if loop ended cleanly via stopRecording()
                finalizeRecordingTarget(
                    target = target,
                    totalBytes = totalBytes,
                    elapsedSeconds = ((System.currentTimeMillis() - startRealtimeMs) / 1_000L).coerceAtLeast(1L)
                )
            } catch (ce: CancellationException) {
                if (target != null) {
                    finalizeRecordingTarget(
                        target = target,
                        totalBytes = totalBytes,
                        elapsedSeconds = ((System.currentTimeMillis() - startRealtimeMs) / 1_000L).coerceAtLeast(1L)
                    )
                } else {
                    _recordingState.value = RecordingUiState(isRecording = false)
                }
                throw ce
            } catch (e: SecurityException) {
                cleanupFailedTarget(target)
                val errMsg = "Permessi di scrittura negati: impossibile salvare la registrazione."
                showToast(errMsg)
                _recordingState.value = RecordingUiState(
                    isRecording = false,
                    lastFeedbackMessage = errMsg,
                    isErrorFeedback = true
                )
            } catch (e: IOException) {
                val msgLower = e.message?.lowercase(Locale.ROOT).orEmpty()
                val isNoSpace = msgLower.contains("enospc") || msgLower.contains("no space") || msgLower.contains("space left")
                if (isStopping && target != null) {
                    finalizeRecordingTarget(
                        target = target,
                        totalBytes = totalBytes,
                        elapsedSeconds = ((System.currentTimeMillis() - startRealtimeMs) / 1_000L).coerceAtLeast(1L)
                    )
                } else {
                    cleanupFailedTarget(target)
                    val errMsg = if (isNoSpace) {
                        "Spazio di archiviazione insufficiente sul dispositivo."
                    } else {
                        "Errore durante la registrazione dello stream: ${e.localizedMessage ?: "connessione interrotta"}"
                    }
                    showToast(errMsg)
                    _recordingState.value = RecordingUiState(
                        isRecording = false,
                        lastFeedbackMessage = errMsg,
                        isErrorFeedback = true
                    )
                }
            } catch (e: Exception) {
                if (isStopping && target != null) {
                    finalizeRecordingTarget(
                        target = target,
                        totalBytes = totalBytes,
                        elapsedSeconds = ((System.currentTimeMillis() - startRealtimeMs) / 1_000L).coerceAtLeast(1L)
                    )
                } else {
                    cleanupFailedTarget(target)
                    val errMsg = "Impossibile completare la registrazione: ${e.localizedMessage ?: "errore sconosciuto"}"
                    showToast(errMsg)
                    _recordingState.value = RecordingUiState(
                        isRecording = false,
                        lastFeedbackMessage = errMsg,
                        isErrorFeedback = true
                    )
                }
            } finally {
                activeHttpCall = null
                timerJob?.cancel()
            }
        }
    }

    /**
     * Stops the current recording session, closes the file, updates MediaStore/library,
     * and notifies the user with the saved summary.
     */
    fun stopRecording() {
        if (!_recordingState.value.isRecording && !isStopping) return
        isStopping = true
        timerJob?.cancel()
        try {
            activeHttpCall?.cancel()
        } catch (_: Exception) {
        }
    }

    fun clearFeedbackMessage() {
        _recordingState.value = _recordingState.value.copy(
            lastFeedbackMessage = null,
            isErrorFeedback = false
        )
    }

    private suspend fun recordDirectStreamLoop(
        streamUrl: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()

        var writtenBytes = 0L
        val call = httpClient.newCall(request)
        activeHttpCall = call
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server stream ha risposto con codice HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Nessun dato audio ricevuto dallo stream")
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (isActive && !isStopping) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        writtenBytes += read
                        onProgress(writtenBytes)
                    }
                }
                outputStream.flush()
            }
        }
        writtenBytes
    }

    private suspend fun recordHlsSegmentsLoop(
        playlistUrl: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        var writtenBytes = 0L
        val downloadedSegments = LinkedHashSet<String>()
        var activeMediaPlaylistUrl = playlistUrl

        while (isActive && !isStopping) {
            val playlistRequest = Request.Builder()
                .url(activeMediaPlaylistUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val playlistCall = httpClient.newCall(playlistRequest)
            activeHttpCall = playlistCall
            val playlistLines = playlistCall.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Impossibile scaricare playlist HLS (${response.code})")
                }
                response.body?.string()?.lines().orEmpty()
            }

            if (!isActive || isStopping) break

            // Check if this is a master playlist (#EXT-X-STREAM-INF)
            val isMaster = playlistLines.any { it.trim().startsWith("#EXT-X-STREAM-INF") }
            if (isMaster) {
                val variantLine = playlistLines
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (variantLine != null) {
                    activeMediaPlaylistUrl = resolveRelativeUrl(activeMediaPlaylistUrl, variantLine)
                    continue
                }
            }

            // Collect media segment URLs
            val segmentUrls = playlistLines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { resolveRelativeUrl(activeMediaPlaylistUrl, it) }

            for (segUrl in segmentUrls) {
                if (!isActive || isStopping) break
                if (downloadedSegments.add(segUrl)) {
                    // Keep sliding window of seen segments bounded
                    if (downloadedSegments.size > 200) {
                        val first = downloadedSegments.iterator().next()
                        downloadedSegments.remove(first)
                    }
                    val segReq = Request.Builder()
                        .url(segUrl)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()

                    val segCall = httpClient.newCall(segReq)
                    activeHttpCall = segCall
                    segCall.execute().use { segResp ->
                        if (segResp.isSuccessful) {
                            segResp.body?.byteStream()?.use { segIn ->
                                val buf = ByteArray(16 * 1024)
                                while (isActive && !isStopping) {
                                    val r = segIn.read(buf)
                                    if (r == -1) break
                                    if (r > 0) {
                                        outputStream.write(buf, 0, r)
                                        writtenBytes += r
                                        onProgress(writtenBytes)
                                    }
                                }
                                outputStream.flush()
                            }
                        }
                    }
                }
            }

            if (!isActive || isStopping) break
            delay(1_800L)
        }
        writtenBytes
    }

    private fun resolveRelativeUrl(baseUrl: String, reference: String): String {
        if (reference.startsWith("http://", ignoreCase = true) ||
            reference.startsWith("https://", ignoreCase = true)
        ) {
            return reference
        }
        return try {
            URI(baseUrl).resolve(reference).toString()
        } catch (_: Exception) {
            val basePrefix = baseUrl.substringBeforeLast('/')
            "$basePrefix/${reference.trimStart('/')}"
        }
    }

    /**
     * Checks case-insensitively if a file with the same name already exists in
     * "Music/Radioweb record/<genreFolder>/".
     */
    fun isDuplicateRecordingCaseInsensitive(genreFolder: String, fileName: String): Boolean {
        val normalizedKey = "${genreFolder.lowercase(Locale.ROOT)}/${fileName.lowercase(Locale.ROOT)}"
        val recordedSet = prefs.getStringSet(KEY_RECORDED_ENTRIES, emptySet()).orEmpty()
        if (recordedSet.any { it.equals(normalizedKey, ignoreCase = true) }) {
            return true
        }

        // Check MediaStore on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val relativePath = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER_NAME/$genreFolder/"
                val projection = arrayOf(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
                val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%$ROOT_FOLDER_NAME/$genreFolder%")
                appContext.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val existingName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        val existingPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else relativePath
                        if (existingName.equals(fileName, ignoreCase = true) &&
                            existingPath.contains("$ROOT_FOLDER_NAME/$genreFolder", ignoreCase = true)
                        ) {
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Also check public Music directory and app-specific Music directory
        val candidateDirs = listOfNotNull(
            try {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "$ROOT_FOLDER_NAME/$genreFolder"
                )
            } catch (_: Exception) {
                null
            },
            appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
                File(it, "$ROOT_FOLDER_NAME/$genreFolder")
            }
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                val existsInDir = dir.listFiles()?.any { existing ->
                    existing.isFile && existing.name.equals(fileName, ignoreCase = true)
                } == true
                if (existsInDir) return true
            }
        }

        return false
    }

    private fun openOutputTarget(
        genreFolder: String,
        fileName: String,
        mimeType: String
    ): ActiveOutputTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val relativePath = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER_NAME/$genreFolder"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.TITLE, fileName.substringBeforeLast('.'))
                    put(MediaStore.Audio.Media.ALBUM, "$ROOT_FOLDER_NAME - $genreFolder")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val out = resolver.openOutputStream(uri)
                    if (out != null) {
                        return ActiveOutputTarget(
                            outputStream = out,
                            mediaStoreUri = uri,
                            directFile = null,
                            genreFolder = genreFolder,
                            fileName = fileName
                        )
                    } else {
                        resolver.delete(uri, null, null)
                    }
                }
            } catch (_: Exception) {
                // Fall back to direct file in app-accessible Music directory
            }
        }

        // Fallback or pre-Android 10 direct file in Music/Radioweb record/<genreFolder>/
        val publicMusicBase = try {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "$ROOT_FOLDER_NAME/$genreFolder"
            )
        } catch (_: Exception) {
            null
        }

        val targetDir = if (publicMusicBase != null && (publicMusicBase.exists() || publicMusicBase.mkdirs()) && publicMusicBase.canWrite()) {
            publicMusicBase
        } else {
            val appMusicRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir
            File(appMusicRoot, "$ROOT_FOLDER_NAME/$genreFolder").apply { mkdirs() }
        }

        val outFile = File(targetDir, fileName)
        return ActiveOutputTarget(
            outputStream = FileOutputStream(outFile),
            mediaStoreUri = null,
            directFile = outFile,
            genreFolder = genreFolder,
            fileName = fileName
        )
    }

    private fun finalizeRecordingTarget(
        target: ActiveOutputTarget,
        totalBytes: Long,
        elapsedSeconds: Long
    ) {
        try {
            target.outputStream.flush()
        } catch (_: Exception) {
        }
        try {
            target.outputStream.close()
        } catch (_: Exception) {
        }

        if (totalBytes <= 0L) {
            cleanupFailedTarget(target)
            val emptyMsg = "Registrazione annullata: nessun dato audio ricevuto dallo stream."
            showToast(emptyMsg)
            _recordingState.value = RecordingUiState(
                isRecording = false,
                lastFeedbackMessage = emptyMsg,
                isErrorFeedback = true
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.mediaStoreUri != null) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                appContext.contentResolver.update(target.mediaStoreUri, values, null, null)
            } catch (_: Exception) {
            }
        }

        target.directFile?.let { file ->
            try {
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(file.absolutePath),
                    null,
                    null
                )
            } catch (_: Exception) {
            }
        }

        // Persist in deduplication registry
        val normalizedKey = "${target.genreFolder.lowercase(Locale.ROOT)}/${target.fileName.lowercase(Locale.ROOT)}"
        val updatedSet = LinkedHashSet(prefs.getStringSet(KEY_RECORDED_ENTRIES, emptySet()).orEmpty())
        updatedSet.add(normalizedKey)
        prefs.edit().putStringSet(KEY_RECORDED_ENTRIES, updatedSet).apply()

        val durationFormatted = formatDurationMmSs(elapsedSeconds)
        val savedToastMsg = "Salvato: ${target.fileName} in ${target.genreFolder}/"
        val summaryMsg = "Registrazione terminata: ${target.fileName} ($durationFormatted)"

        showToast("$savedToastMsg\n$summaryMsg")
        _recordingState.value = RecordingUiState(
            isRecording = false,
            stationName = null,
            genreFolder = target.genreFolder,
            fileName = target.fileName,
            elapsedSeconds = elapsedSeconds,
            bytesWritten = totalBytes,
            lastFeedbackMessage = "$summaryMsg • Salvato in $ROOT_FOLDER_NAME/${target.genreFolder}/",
            isErrorFeedback = false
        )
    }

    private fun cleanupFailedTarget(target: ActiveOutputTarget?) {
        if (target == null) return
        try {
            target.outputStream.close()
        } catch (_: Exception) {
        }
        if (target.mediaStoreUri != null) {
            try {
                appContext.contentResolver.delete(target.mediaStoreUri, null, null)
            } catch (_: Exception) {
            }
        }
        target.directFile?.let { file ->
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }
}
