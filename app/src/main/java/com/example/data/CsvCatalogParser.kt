package com.example.data

import com.example.model.CatalogMetrics
import com.example.model.Station
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.Collator
import java.util.Locale
import java.util.zip.GZIPInputStream

data class ParsedCatalogResult(
    val stations: List<Station>,
    val genreCounts: Map<String, Int>,
    val metrics: CatalogMetrics
)

object CsvCatalogParser {

    private val italianCollator: ThreadLocal<Collator> = object : ThreadLocal<Collator>() {
        override fun initialValue(): Collator = Collator.getInstance(Locale.ITALIAN).apply {
            strength = Collator.SECONDARY
        }
    }

    /**
     * Natural comparator for Italian locale with numeric awareness (fast zero-allocation path for ~55,000 stations).
     */
    val stationNameComparator: Comparator<Station> = Comparator { a, b ->
        compareNaturalItalian(a.name, b.name)
    }

    val stationCountryComparator: Comparator<Station> = Comparator { a, b ->
        val cA = a.country.ifEmpty { "zzz" }
        val cB = b.country.ifEmpty { "zzz" }
        val countryCmp = compareNaturalItalian(cA, cB)
        if (countryCmp != 0) countryCmp else compareNaturalItalian(a.name, b.name)
    }

    fun compareNaturalItalian(s1: String, s2: String): Int {
        val lenA = s1.length
        val lenB = s2.length
        var i = 0
        var j = 0

        // Skip leading whitespace
        while (i < lenA && s1[i] <= ' ') i++
        while (j < lenB && s2[j] <= ' ') j++

        while (i < lenA && j < lenB) {
            val c1 = s1[i]
            val c2 = s2[j]

            if (c1 in '0'..'9' && c2 in '0'..'9') {
                // Skip leading zeros
                while (i < lenA && s1[i] == '0') i++
                while (j < lenB && s2[j] == '0') j++

                val numStartA = i
                val numStartB = j
                while (i < lenA && s1[i] in '0'..'9') i++
                while (j < lenB && s2[j] in '0'..'9') j++

                val digitsLenA = i - numStartA
                val digitsLenB = j - numStartB
                if (digitsLenA != digitsLenB) {
                    return digitsLenA.compareTo(digitsLenB)
                }
                for (k in 0 until digitsLenA) {
                    val d1 = s1[numStartA + k]
                    val d2 = s2[numStartB + k]
                    if (d1 != d2) return d1.compareTo(d2)
                }
            } else {
                val u1 = c1.lowercaseChar()
                val u2 = c2.lowercaseChar()
                if (u1 != u2) {
                    if (u1.code < 128 && u2.code < 128) {
                        return u1.compareTo(u2)
                    }
                    val collator = italianCollator.get()
                    val cmp = if (collator != null) {
                        collator.compare(u1.toString(), u2.toString())
                    } else {
                        u1.compareTo(u2)
                    }
                    if (cmp != 0) return cmp
                }
                i++
                j++
            }
        }
        return (lenA - i).compareTo(lenB - j)
    }

    /**
     * Wraps an InputStream with GZIPInputStream if either the file name ends with .gz
     * or the stream starts with the GZIP magic bytes (0x1f, 0x8b).
     */
    fun wrapDecompressedStream(rawInput: InputStream, fileName: String): InputStream {
        val buffered = if (rawInput is BufferedInputStream) rawInput else BufferedInputStream(rawInput, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()

        val isGzipMagic = (b1 == 0x1f && b2 == 0x8b)
        return if (isGzipMagic || fileName.lowercase(Locale.ROOT).endsWith(".gz")) {
            GZIPInputStream(buffered, 64 * 1024)
        } else {
            buffered
        }
    }

    /**
     * Parses a catalog_stations.csv (or .csv.gz) stream using a strict RFC 4180 state machine.
     * Deduplicates by `id` keeping the first row with `status=VALID`.
     */
    fun parseCatalog(
        inputStream: InputStream,
        fileName: String,
        isCustomCatalog: Boolean
    ): ParsedCatalogResult {
        val decompressed = wrapDecompressedStream(inputStream, fileName)
        val reader = BufferedReader(InputStreamReader(decompressed, StandardCharsets.UTF_8), 64 * 1024)

        val seenIds = HashSet<String>(60_000)
        val stations = ArrayList<Station>(56_500)
        val genreCounts = HashMap<String, Int>(256)

        var totalRowsRead = 0
        var duplicatesFiltered = 0
        var invalidRowsFiltered = 0
        var isHeaderRow = true

        // Column index mapping (default positional order per spec)
        var idxName = 0
        var idxId = 1
        var idxCity = 2
        var idxCountry = 3
        var idxGenres = 4
        var idxLogo100 = 5
        var idxLogo175 = 6
        var idxLogo300 = 7
        var idxStreamUrl = 8
        var idxFormat = 9
        var idxStatus = 10

        reader.use {
            parseRfc4180Rows(it) { fields ->
                if (fields.isEmpty() || (fields.size == 1 && fields[0].isBlank())) {
                    return@parseRfc4180Rows
                }

                if (isHeaderRow) {
                    isHeaderRow = false
                    val firstCell = fields[0].trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)
                    if (firstCell == "name" || fields.any { col -> col.trim().equals("stream_url", ignoreCase = true) }) {
                        fields.forEachIndexed { index, rawCol ->
                            when (rawCol.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)) {
                                "name" -> idxName = index
                                "id" -> idxId = index
                                "city" -> idxCity = index
                                "country" -> idxCountry = index
                                "genres" -> idxGenres = index
                                "logo100" -> idxLogo100 = index
                                "logo175" -> idxLogo175 = index
                                "logo300" -> idxLogo300 = index
                                "stream_url" -> idxStreamUrl = index
                                "format" -> idxFormat = index
                                "status" -> idxStatus = index
                            }
                        }
                        return@parseRfc4180Rows
                    }
                }

                totalRowsRead++

                fun fieldAt(idx: Int): String = if (idx in fields.indices) fields[idx].trim() else ""

                val status = fieldAt(idxStatus)
                if (!status.equals("VALID", ignoreCase = true)) {
                    invalidRowsFiltered++
                    return@parseRfc4180Rows
                }

                val id = fieldAt(idxId)
                val name = fieldAt(idxName)
                val streamUrl = fieldAt(idxStreamUrl)

                if (id.isEmpty() || name.isEmpty() || streamUrl.isEmpty()) {
                    invalidRowsFiltered++
                    return@parseRfc4180Rows
                }

                if (!seenIds.add(id)) {
                    duplicatesFiltered++
                    return@parseRfc4180Rows
                }

                val city = fieldAt(idxCity)
                val country = fieldAt(idxCountry)
                val rawGenres = fieldAt(idxGenres)
                val genres = if (rawGenres.isEmpty()) {
                    emptyList()
                } else {
                    rawGenres.split(';')
                        .map { g -> g.trim() }
                        .filter { g -> g.isNotEmpty() }
                        .distinct()
                }

                for (genre in genres) {
                    genreCounts[genre] = (genreCounts[genre] ?: 0) + 1
                }

                val logo100 = fieldAt(idxLogo100)
                val logo175 = fieldAt(idxLogo175)
                val logo300 = fieldAt(idxLogo300)

                val availableLogos = buildList(3) {
                    if (logo175.isNotEmpty()) add(logo175)
                    if (logo100.isNotEmpty() && logo100 != logo175) add(logo100)
                    if (logo300.isNotEmpty() && logo300 != logo175 && logo300 != logo100) add(logo300)
                }
                val preferredLogo = availableLogos.firstOrNull() ?: ""

                val rawFormat = fieldAt(idxFormat).uppercase(Locale.ROOT)
                val format = when (rawFormat) {
                    "MP3", "AAC", "OGG", "UNKNOWN" -> rawFormat
                    else -> "UNKNOWN"
                }

                val isHls = streamUrl.contains(".m3u8", ignoreCase = true)

                stations.add(
                    Station(
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
                )
            }
        }

        stations.sortWith(stationNameComparator)

        val metrics = CatalogMetrics(
            uniqueStations = stations.size,
            totalRowsRead = totalRowsRead,
            duplicatesFiltered = duplicatesFiltered,
            invalidRowsFiltered = invalidRowsFiltered,
            genresCount = genreCounts.size,
            activeFileName = fileName,
            isCustomCatalog = isCustomCatalog,
            isSmallCatalog = totalRowsRead <= 65
        )

        return ParsedCatalogResult(
            stations = stations,
            genreCounts = genreCounts,
            metrics = metrics
        )
    }

    /**
     * Streaming RFC 4180 CSV parser with single-char lookahead (no mark/reset overhead).
     */
    private fun parseRfc4180Rows(
        reader: BufferedReader,
        onRow: (List<String>) -> Unit
    ) {
        val currentRow = ArrayList<String>(12)
        val currentField = StringBuilder(128)
        var inQuotes = false
        var lookahead = -2 // -2 means no char buffered

        fun nextChar(): Int {
            return if (lookahead != -2) {
                val c = lookahead
                lookahead = -2
                c
            } else {
                reader.read()
            }
        }

        while (true) {
            val chInt = nextChar()
            if (chInt == -1) {
                if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
                    currentRow.add(currentField.toString())
                    onRow(currentRow)
                }
                break
            }

            val ch = chInt.toChar()
            if (inQuotes) {
                if (ch == '"') {
                    val nextInt = nextChar()
                    if (nextInt == '"'.code) {
                        currentField.append('"')
                    } else {
                        inQuotes = false
                        lookahead = nextInt
                    }
                } else {
                    currentField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        inQuotes = true
                    }
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                    }
                    '\r' -> {
                        val nextInt = nextChar()
                        if (nextInt != '\n'.code) {
                            lookahead = nextInt
                        }
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        onRow(currentRow)
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        onRow(currentRow)
                        currentRow.clear()
                    }
                    else -> {
                        currentField.append(ch)
                    }
                }
            }
        }
    }
}
