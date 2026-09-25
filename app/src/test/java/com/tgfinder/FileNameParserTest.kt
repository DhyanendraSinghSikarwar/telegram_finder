package com.tgfinder

import com.tgfinder.search.FileNameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameParserTest {

    @Test fun dottedMovieRelease() {
        val p = FileNameParser.parse("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv")
        assertEquals("The Matrix", p.title)
        assertEquals(1999, p.year)
        assertEquals("1080p", p.quality)
        assertEquals("BluRay", p.source)
        assertEquals("H.264", p.codec)
        assertNull(p.season)
    }

    @Test fun underscoresAndBracketedYear() {
        val p = FileNameParser.parse("Inception_(2010)_[720p]_WEB-DL_Hindi_English.mp4")
        assertEquals("Inception", p.title)
        assertEquals(2010, p.year)
        assertEquals("WEB-DL", p.source)
    }

    @Test fun releaseGroupPrefixAndHandleRemoved() {
        val p = FileNameParser.parse("[YTS.MX] Dune Part Two 2024 2160p WEBRip x265 @MoviesChannel.mkv")
        assertEquals("Dune Part Two", p.title)
        assertEquals(2024, p.year)
        assertEquals("4K", p.quality)
        assertEquals("HEVC", p.codec)
    }

    @Test fun tvEpisode() {
        val p = FileNameParser.parse("Breaking.Bad.S05E14.Ozymandias.720p.HDTV.x264.mkv")
        assertEquals("Breaking Bad", p.title)
        assertEquals(5, p.season)
        assertEquals(14, p.episode)
        assertEquals("S05E14", p.episodeLabel)
    }

    @Test fun tvEpisodeWithYear() {
        val p = FileNameParser.parse("The Last of Us 2023 S01 E03 1080p HMAX WEB-DL DDP5.1 H.264.mkv")
        assertEquals("The Last of Us", p.title)
        assertEquals(2023, p.year)
        assertEquals(1, p.season)
        assertEquals(3, p.episode)
        assertEquals("H.264", p.codec)
    }

    @Test fun numericTitleIsNotAYear() {
        val p = FileNameParser.parse("1917.2019.1080p.BluRay.mkv")
        assertEquals("1917", p.title)
        assertEquals(2019, p.year)
    }

    @Test fun titleStartingWithYearLikeNumber() {
        val p = FileNameParser.parse("2001.A.Space.Odyssey.1968.REMASTERED.1080p.mkv")
        assertEquals("2001 A Space Odyssey", p.title)
        assertEquals(1968, p.year)
    }

    @Test fun genericNameFallsBackToCaption() {
        val p = FileNameParser.parse("video_2023-04-01_12-00-00.mp4", "Oppenheimer (2023) 1080p\nJoin @channel")
        assertEquals("Oppenheimer", p.title)
        assertEquals(2023, p.year)
        assertEquals("1080p", p.quality)
    }

    @Test fun lowercaseNameIsTitleCased() {
        val p = FileNameParser.parse("john.wick.chapter.4.2023.720p.mkv")
        assertEquals("John Wick Chapter 4", p.title)
        assertEquals(2023, p.year)
    }

    @Test fun normalizedKeyIgnoresArticlesAndPunctuation() {
        assertEquals(FileNameParser.normalizedKey("The Matrix"), FileNameParser.normalizedKey("matrix"))
        assertEquals("fast and furious", FileNameParser.normalizedKey("Fast & Furious"))
    }
}
