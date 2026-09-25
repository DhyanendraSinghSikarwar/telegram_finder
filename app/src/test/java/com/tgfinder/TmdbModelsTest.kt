package com.tgfinder

import com.tgfinder.telegram.ErrorMessages
import com.tgfinder.telegram.FloodWaitException
import com.tgfinder.telegram.TdException
import com.tgfinder.tmdb.DetailsDto
import com.tgfinder.tmdb.SearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbModelsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test fun decodesSearchWithNullsAndUnknownFields() {
        val body = """{"page":1,"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-30",
            "poster_path":null,"vote_average":8.2,"vote_count":26000,"genre_ids":[28],"adult":false}]}"""
        val r = json.decodeFromString<SearchResponse>(body).results.single()
        assertEquals(603, r.id)
        assertEquals(1999, r.year)
        assertNull(r.posterPath)
        assertEquals("The Matrix", r.displayTitle)
    }

    @Test fun decodesTvSearchUsingName() {
        val body = """{"results":[{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20"}]}"""
        val r = json.decodeFromString<SearchResponse>(body).results.single()
        assertEquals("Breaking Bad", r.displayTitle)
        assertEquals(2008, r.year)
    }

    @Test fun decodesMovieDetailsWithAppendedResponses() {
        val body = """{"id":27205,"title":"Inception","release_date":"2010-07-15","runtime":148,
            "genres":[{"id":28,"name":"Action"}],"vote_average":8.4,"vote_count":35000,"overview":"A thief…",
            "credits":{"cast":[{"name":"Leonardo DiCaprio","character":"Cobb","profile_path":"/x.jpg","order":0}],
                       "crew":[{"name":"Christopher Nolan","job":"Director"}]},
            "videos":{"results":[{"key":"YoHD9XEInc0","site":"YouTube","type":"Trailer","official":true}]},
            "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13","type":3}]}]}}"""
        val d = json.decodeFromString<DetailsDto>(body)
        assertEquals(148, d.runtime)
        assertEquals("Christopher Nolan", d.credits!!.crew.single { it.job == "Director" }.name)
        assertEquals("PG-13", d.releaseDates!!.results.single().releaseDates.single().certification)
        assertEquals("YoHD9XEInc0", d.videos!!.results.single().key)
    }

    @Test fun floodWaitMessageIsHumanReadable() {
        assertTrue(ErrorMessages.forTelegram(FloodWaitException(30)).contains("30 seconds"))
        assertTrue(ErrorMessages.floodWait(600).contains("10 minutes"))
    }

    @Test fun loginErrorsAreMapped() {
        assertTrue(ErrorMessages.forTelegram(TdException(400, "PHONE_CODE_INVALID")).startsWith("Wrong code"))
        assertTrue(ErrorMessages.forTelegram(TdException(400, "PASSWORD_HASH_INVALID")).contains("password"))
        assertTrue(ErrorMessages.forTelegram(TdException(400, "API_ID_INVALID")).contains("API ID"))
    }
}
