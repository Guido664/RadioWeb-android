package com.example

import com.example.data.CsvCatalogParser
import com.example.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class ExampleUnitTest {

    @Test
    fun csvParser_handlesQuotedCommas_deduplicatesById_andResolvesLogoFallback() {
        val sampleCsv = """
            name,id,city,country,genres,logo100,logo175,logo300,stream_url,format,status
            "RAI Radio 1, Roma",rairadio1,Rome,Italy,Talk;Pop;Italian Music,https://img/100.png,,https://img/300.png,https://stream.rai.it/1.mp3,MP3,VALID
            "RAI Radio 1, Backup",rairadio1,Rome,Italy,Talk,https://img/100b.png,https://img/175b.png,,https://stream.rai.it/1b.aac,AAC,VALID
            Radio Offline,offline1,Milan,Italy,Rock,,,,https://stream.offline/1.mp3,MP3,INVALID
            Radio HLS Test,hlstest,Milan,Italy,Dance;Electro,,https://img/175.png,,https://cdn.example.com/live/master.m3u8,AAC,VALID
        """.trimIndent()

        val result = CsvCatalogParser.parseCatalog(
            inputStream = ByteArrayInputStream(sampleCsv.toByteArray(StandardCharsets.UTF_8)),
            fileName = "catalog_stations.csv",
            isCustomCatalog = false
        )

        assertEquals(2, result.stations.size)
        assertEquals(4, result.metrics.totalRowsRead)
        assertEquals(1, result.metrics.duplicatesFiltered)
        assertEquals(1, result.metrics.invalidRowsFiltered)

        val rai = result.stations.first { it.id == "rairadio1" }
        assertEquals("RAI Radio 1, Roma", rai.name)
        assertEquals("https://img/100.png", rai.preferredLogo)
        assertEquals(listOf("https://img/100.png", "https://img/300.png"), rai.availableLogos)
        assertFalse(rai.isHls)

        val hls = result.stations.first { it.id == "hlstest" }
        assertTrue(hls.isHls)
        assertEquals("HLS", hls.displayFormat)
        assertEquals("RH", Station.computeInitials(hls.name))
    }

    @Test
    fun csvParser_decompressesGzipCsvAtRuntime() {
        val sampleCsv = """
            name,id,city,country,genres,logo100,logo175,logo300,stream_url,format,status
            RADIO LAC,radiolacch,Geneva,Switzerland,Pop;Talk,,https://img/lac175.png,,https://radiolac.ice.infomaniak.ch/radiolac-high.mp3,MP3,VALID
        """.trimIndent()

        val byteOut = ByteArrayOutputStream()
        GZIPOutputStream(byteOut).use { gzip ->
            gzip.write(sampleCsv.toByteArray(StandardCharsets.UTF_8))
        }

        val result = CsvCatalogParser.parseCatalog(
            inputStream = ByteArrayInputStream(byteOut.toByteArray()),
            fileName = "catalog_stations.csv.gz",
            isCustomCatalog = true
        )

        assertEquals(1, result.stations.size)
        assertEquals("radiolacch", result.stations.first().id)
        assertEquals("RL", result.stations.first().initials)
    }

    @Test
    fun streamRecorder_sanitizesNames_resolvesFirstGenre_andPreservesOriginalExtension() {
        val sanitized = com.example.player.StreamRecorderManager.sanitizeFileSystemName("Radio: Swiss / Jazz * ? \" < > | \\ Live")
        assertEquals("Radio Swiss Jazz Live", sanitized)

        val jazzStation = Station(
            id = "swissjazz",
            name = "Radio Swiss Jazz",
            city = "Basel",
            country = "Switzerland",
            genres = listOf("Jazz", "Blues"),
            logo100 = "",
            logo175 = "",
            logo300 = "",
            preferredLogo = "",
            availableLogos = emptyList(),
            streamUrl = "https://stream.srg-ssr.ch/m/rsj/aacp_96.aac",
            format = "AAC",
            isHls = false,
            searchKey = "radio swiss jazz",
            initials = "RS"
        )
        assertEquals("Jazz", com.example.player.StreamRecorderManager.resolveGenreFolderName(jazzStation))
        assertEquals("aac" to "audio/aac", com.example.player.StreamRecorderManager.resolveExtensionAndMime(jazzStation))

        val noGenreStation = jazzStation.copy(genres = emptyList(), format = "OGG", streamUrl = "https://example.com/live.ogg")
        assertEquals("Generi non specificati", com.example.player.StreamRecorderManager.resolveGenreFolderName(noGenreStation))
        assertEquals("ogg" to "audio/ogg", com.example.player.StreamRecorderManager.resolveExtensionAndMime(noGenreStation))

        val fileName = com.example.player.StreamRecorderManager.buildRecordingFileName(
            stationName = "Radio Swiss: Jazz/FM",
            timestampMillis = 1762007400000L,
            extension = "mp3"
        )
        assertTrue(fileName.startsWith("Radio Swiss Jazz FM "))
        assertTrue(fileName.endsWith(".mp3"))
        assertEquals(
            "Radio Swiss Jazz FM 20251101-1430 (2).mp3",
            com.example.player.StreamRecorderManager.buildIndexedFileName("Radio Swiss Jazz FM 20251101-1430.mp3", 2)
        )
    }

    @Test
    fun stationSearch_matchesStartOfName_city_country_andGenre() {
        val station = Station(
            id = "rairadio1",
            name = "RAI Radio 1",
            city = "Roma",
            country = "Italy",
            genres = listOf("Talk", "Italian Music", "News"),
            logo100 = "",
            logo175 = "",
            logo300 = "",
            preferredLogo = "",
            availableLogos = emptyList(),
            streamUrl = "https://stream.rai.it/1.mp3",
            format = "MP3",
            isHls = false,
            initials = "RR"
        )

        // Start of station name matches, middle word of name does not
        assertTrue(station.matchesSearchQuery("RAI"))
        assertTrue(station.matchesSearchQuery("rai radio"))
        assertFalse(station.matchesSearchQuery("Radio 1"))

        // City matches
        assertTrue(station.matchesSearchQuery("Roma"))
        assertTrue(station.matchesSearchQuery("rom"))

        // Country (Nation) matches
        assertTrue(station.matchesSearchQuery("Italy"))
        assertTrue(station.matchesSearchQuery("ita"))

        // Genre matches
        assertTrue(station.matchesSearchQuery("Talk"))
        assertTrue(station.matchesSearchQuery("Italian Music"))
        assertFalse(station.matchesSearchQuery("Jazz"))
    }

    @Test
    fun nowPlayingTitleTracker_updatesWhenStreamStuckOnFirstTitleAndApiChanges() {
        val tracker = com.example.ui.NowPlayingTitleTracker()
        val t0 = 1_000_000L

        // 1. On opening: ExoPlayer emits "First Song" and initial poll also reports "First Song"
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 1 - First Song"),
            tracker.onExoStreamTitleChanged("Artist 1 - First Song", t0)
        )
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 1 - First Song"),
            tracker.onPollResult(
                com.example.network.NowPlayingPollResult(
                    apiTitle = "Artist 1 - First Song",
                    icyTitle = "Artist 1 - First Song",
                    hadSuccessfulResponse = true
                ),
                t0 + 500
            )
        )

        // 2. 2 minutes later: Stream/ICY is STILL stuck on "Artist 1 - First Song", but API updates to "Artist 2 - Second Song"
        val stateAfterApiUpdate = tracker.onPollResult(
            com.example.network.NowPlayingPollResult(
                apiTitle = "Artist 2 - Second Song",
                icyTitle = "Artist 1 - First Song",
                hadSuccessfulResponse = true
            ),
            t0 + 120_000L
        )
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 2 - Second Song"),
            stateAfterApiUpdate
        )

        // 3. Re-checking ExoPlayer's unchanged opening title must NOT revert back to "First Song"
        val stateAfterExoCheck = tracker.onExoStreamTitleChanged("Artist 1 - First Song", t0 + 121_000L)
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 2 - Second Song"),
            stateAfterExoCheck
        )
    }

    @Test
    fun nowPlayingTitleTracker_updatesWhenApiStuckOnFirstTitleAndStreamChanges() {
        val tracker = com.example.ui.NowPlayingTitleTracker()
        val t0 = 2_000_000L

        // 1. Opening poll sets "Artist 1 - First Song"
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 1 - First Song"),
            tracker.onPollResult(
                com.example.network.NowPlayingPollResult(
                    apiTitle = "Artist 1 - First Song",
                    icyTitle = "Artist 1 - First Song",
                    hadSuccessfulResponse = true
                ),
                t0
            )
        )

        // 2. ExoPlayer in-stream metadata (or ICY probe) updates to "Artist 2 - Second Song" while API is still stuck on "First Song"
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 2 - Second Song"),
            tracker.onExoStreamTitleChanged("Artist 2 - Second Song", t0 + 90_000L)
        )

        // 3. Subsequent poll where API still returns "Artist 1 - First Song" must NOT revert "Second Song"
        val pollState = tracker.onPollResult(
            com.example.network.NowPlayingPollResult(
                apiTitle = "Artist 1 - First Song",
                icyTitle = null,
                hadSuccessfulResponse = true
            ),
            t0 + 102_000L
        )
        assertEquals(
            com.example.model.NowPlayingState.Available("Artist 2 - Second Song"),
            pollState
        )

        // 4. ICY probe also parsing raw StreamTitle block
        val extracted = com.example.network.NowPlayingService.extractIcyStreamTitle(
            "StreamTitle='Daft Punk - Get Lucky';StreamUrl='https://radio.example';"
        )
        assertEquals("Daft Punk - Get Lucky", extracted)
    }

    @Test
    fun carDisplayMediaMetadata_populatesAllThreeLinesForAvrcpAndAndroidAuto() {
        val station = Station(
            id = "radiolacch",
            name = "RADIO LAC",
            city = "Geneva",
            country = "Switzerland",
            genres = listOf("Pop", "Hits"),
            logo100 = "",
            logo175 = "",
            logo300 = "",
            preferredLogo = "",
            availableLogos = emptyList(),
            streamUrl = "https://radiolac.ice.infomaniak.ch/radiolac-high.mp3",
            format = "MP3",
            isHls = false,
            initials = "RL"
        )

        // When playing with a live song title
        val playingMeta = com.example.player.RadioPlayerController.buildCarDisplayMediaMetadata(
            station = station,
            songTitle = "Daft Punk - Get Lucky",
            playerStatus = com.example.model.PlayerStatus.PLAYING
        )
        // Line 1: Station Name
        assertEquals("RADIO LAC", playingMeta.title.toString())
        assertEquals("RADIO LAC", playingMeta.displayTitle.toString())
        // Line 2: Song Title (both AVRCP artist and Android Auto subtitle)
        assertEquals("Daft Punk - Get Lucky", playingMeta.artist.toString())
        assertEquals("Daft Punk - Get Lucky", playingMeta.subtitle.toString())
        // Line 3: City, Country (both AVRCP albumTitle and Android Auto description)
        assertEquals("Geneva, Switzerland", playingMeta.albumTitle.toString())
        assertEquals("Geneva, Switzerland", playingMeta.description.toString())
        assertEquals("Geneva, Switzerland", playingMeta.albumArtist.toString())

        // When playing without a song title yet (never duplicates City, Country on Line 2)
        val noSongMeta = com.example.player.RadioPlayerController.buildCarDisplayMediaMetadata(
            station = station,
            songTitle = null,
            playerStatus = com.example.model.PlayerStatus.PLAYING
        )
        assertEquals("RADIO LAC", noSongMeta.title.toString())
        assertEquals("In diretta", noSongMeta.artist.toString())
        assertEquals("In diretta", noSongMeta.subtitle.toString())
        assertEquals("Geneva, Switzerland", noSongMeta.albumTitle.toString())
        assertEquals("Geneva, Switzerland", noSongMeta.description.toString())
    }
}

