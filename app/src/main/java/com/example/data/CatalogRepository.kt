package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.CatalogMetrics
import com.example.model.GenreItem
import com.example.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class LoadedCatalogData(
    val stations: List<Station>,
    val canonicalGenresAlphabetical: List<GenreItem>,
    val frequentGenresTop12: List<GenreItem>,
    val metrics: CatalogMetrics
)

class CatalogRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "radioweb_catalog_prefs"
        private const val KEY_CUSTOM_FILE_NAME = "custom_catalog_file_name"
        private const val CUSTOM_STORAGE_FILE = "user_catalog_stations.dat"
        private const val BUNDLE_CSV_GZ = "catalog_stations.csv.gz"
        private const val BUNDLE_CSV = "catalog_stations.csv"
        private const val GENRES_TXT = "genres_unique.txt"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun loadInitialCatalog(): LoadedCatalogData = withContext(Dispatchers.IO) {
        val canonicalFromTxt = loadGenresUniqueTxt()

        // 1. Parse bundle catalog (or external files dir if developer placed a large catalog there)
        val bundleParsed = parseBundleOrExternalCatalog()

        // 2. Check if user previously uploaded a custom catalog
        val customFile = File(context.filesDir, CUSTOM_STORAGE_FILE)
        val savedCustomName = prefs.getString(KEY_CUSTOM_FILE_NAME, null)

        val activeParsed = if (customFile.exists() && customFile.length() > 0L && savedCustomName != null) {
            try {
                val userParsed = FileInputStream(customFile).use { input ->
                    CsvCatalogParser.parseCatalog(
                        inputStream = input,
                        fileName = savedCustomName,
                        isCustomCatalog = true
                    )
                }
                // Spec: "Se esiste un catalogo caricato in precedenza dall'utente, usare quello se è più grande del catalogo di bundle."
                if (userParsed.stations.size >= bundleParsed.stations.size && userParsed.stations.isNotEmpty()) {
                    userParsed
                } else {
                    bundleParsed
                }
            } catch (_: Exception) {
                bundleParsed
            }
        } else {
            bundleParsed
        }

        buildLoadedCatalogData(activeParsed, canonicalFromTxt)
    }

    suspend fun replaceCatalogFromUri(uri: Uri): Result<LoadedCatalogData> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveFileName(uri) ?: "catalogo_utente.csv"
            val tempFile = File(context.filesDir, "$CUSTOM_STORAGE_FILE.tmp")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalArgumentException("Impossibile leggere il file selezionato.")

            val parsed = FileInputStream(tempFile).use { input ->
                CsvCatalogParser.parseCatalog(
                    inputStream = input,
                    fileName = displayName,
                    isCustomCatalog = true
                )
            }

            if (parsed.stations.isEmpty()) {
                tempFile.delete()
                throw IllegalArgumentException("Il file CSV selezionato non contiene stazioni valide (status=VALID).")
            }

            val destFile = File(context.filesDir, CUSTOM_STORAGE_FILE)
            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)
            prefs.edit().putString(KEY_CUSTOM_FILE_NAME, displayName).apply()

            val canonicalFromTxt = loadGenresUniqueTxt()
            buildLoadedCatalogData(parsed, canonicalFromTxt)
        }
    }

    suspend fun restoreBundleCatalog(): LoadedCatalogData = withContext(Dispatchers.IO) {
        val customFile = File(context.filesDir, CUSTOM_STORAGE_FILE)
        if (customFile.exists()) {
            customFile.delete()
        }
        prefs.edit().remove(KEY_CUSTOM_FILE_NAME).apply()

        val canonicalFromTxt = loadGenresUniqueTxt()
        val bundleParsed = parseBundleOrExternalCatalog()
        buildLoadedCatalogData(bundleParsed, canonicalFromTxt)
    }

    private fun parseBundleOrExternalCatalog(): ParsedCatalogResult {
        // First check if a full catalog was placed in externalFilesDir (e.g. via Device File Explorer)
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val extGz = File(externalDir, BUNDLE_CSV_GZ)
            val extCsv = File(externalDir, BUNDLE_CSV)
            val candidate = when {
                extGz.exists() && extGz.length() > 0 -> extGz
                extCsv.exists() && extCsv.length() > 0 -> extCsv
                else -> null
            }
            if (candidate != null) {
                try {
                    return FileInputStream(candidate).use {
                        CsvCatalogParser.parseCatalog(it, candidate.name, isCustomCatalog = false)
                    }
                } catch (_: Exception) {
                    // Fallback to assets
                }
            }
        }

        val assetList = context.assets.list("")?.toSet() ?: emptySet()
        val assetFileName = when {
            BUNDLE_CSV_GZ in assetList -> BUNDLE_CSV_GZ
            BUNDLE_CSV in assetList -> BUNDLE_CSV
            else -> BUNDLE_CSV
        }

        return context.assets.open(assetFileName).use { input ->
            CsvCatalogParser.parseCatalog(
                inputStream = input,
                fileName = assetFileName,
                isCustomCatalog = false
            )
        }
    }

    private fun loadGenresUniqueTxt(): List<GenreItem> {
        return try {
            val externalFile = context.getExternalFilesDir(null)?.let { File(it, GENRES_TXT) }
            val inputStream = if (externalFile != null && externalFile.exists()) {
                FileInputStream(externalFile)
            } else {
                context.assets.open(GENRES_TXT)
            }

            val list = ArrayList<GenreItem>(190)
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val tabIdx = trimmed.indexOf('\t')
                        if (tabIdx > 0) {
                            val count = trimmed.substring(0, tabIdx).trim().toIntOrNull() ?: 0
                            val name = trimmed.substring(tabIdx + 1).trim()
                            if (name.isNotEmpty()) {
                                list.add(GenreItem(name = name, count = count))
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildLoadedCatalogData(
        parsed: ParsedCatalogResult,
        canonicalFromTxt: List<GenreItem>
    ): LoadedCatalogData {
        val activeGenreCounts = parsed.genreCounts
        val useLiveCounts = !parsed.metrics.isSmallCatalog || parsed.metrics.isCustomCatalog

        // Map lowercase genre name -> canonical casing name
        val canonicalKeyMap = LinkedHashMap<String, String>(canonicalFromTxt.size + activeGenreCounts.size)
        val mergedGenresMap = LinkedHashMap<String, Int>(canonicalFromTxt.size + activeGenreCounts.size)

        if (canonicalFromTxt.isNotEmpty()) {
            for (item in canonicalFromTxt) {
                val lowerKey = item.name.lowercase( java.util.Locale.ROOT)
                canonicalKeyMap[lowerKey] = item.name
                mergedGenresMap[item.name] = if (useLiveCounts) 0 else item.count
            }

            if (useLiveCounts) {
                for ((csvGenre, liveCount) in activeGenreCounts) {
                    val lowerKey = csvGenre.lowercase(java.util.Locale.ROOT)
                    val targetName = canonicalKeyMap[lowerKey] ?: csvGenre.also {
                        canonicalKeyMap[lowerKey] = it
                    }
                    mergedGenresMap[targetName] = (mergedGenresMap[targetName] ?: 0) + liveCount
                }
            } else {
                // Starter bundle catalog: keep canonical counts from genres_unique.txt, plus any extra genre present in CSV
                for ((csvGenre, liveCount) in activeGenreCounts) {
                    val lowerKey = csvGenre.lowercase(java.util.Locale.ROOT)
                    if (lowerKey !in canonicalKeyMap) {
                        canonicalKeyMap[lowerKey] = csvGenre
                        mergedGenresMap[csvGenre] = liveCount
                    }
                }
            }
        } else {
            for ((genreName, liveCount) in activeGenreCounts) {
                mergedGenresMap[genreName] = liveCount
            }
        }

        val allGenres = mergedGenresMap.entries.map { GenreItem(it.key, it.value) }

        // Spec: "ordina le stazioni per nome (locale italiano, numeric) e i generi alfabeticamente."
        val alphabeticalGenres = allGenres.sortedWith { a, b ->
            CsvCatalogParser.compareNaturalItalian(a.name, b.name)
        }

        // Spec: "Barra 'Generi frequenti': i 12 generi con più stazioni come badge toggleabili"
        val frequentGenresTop12 = allGenres
            .sortedWith(compareByDescending<GenreItem> { it.count }.thenComparator { a, b ->
                CsvCatalogParser.compareNaturalItalian(a.name, b.name)
            })
            .take(12)

        val updatedMetrics = parsed.metrics.copy(
            genresCount = alphabeticalGenres.size
        )

        return LoadedCatalogData(
            stations = parsed.stations,
            canonicalGenresAlphabetical = alphabeticalGenres,
            frequentGenresTop12 = frequentGenresTop12,
            metrics = updatedMetrics
        )
    }

    private fun resolveFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }
}
