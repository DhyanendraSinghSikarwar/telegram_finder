package com.tgfinder.tmdb

import com.tgfinder.data.SecurePrefs
import com.tgfinder.data.db.TmdbDao
import com.tgfinder.data.db.TmdbDetailEntity
import com.tgfinder.data.db.TmdbMatchEntity
import com.tgfinder.search.FileNameParser
import com.tgfinder.search.ParsedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class TmdbKeyMissingException : Exception("TMDB API key missing")
class TmdbAuthException : Exception("The TMDB API key was rejected. Check it in Settings.")

/**
 * Matches cleaned titles to TMDB and loads details. Every result (including "not found") is cached
 * in Room so repeat searches do not call the API again.
 */
class TmdbRepository(
    private val http: OkHttpClient,
    private val dao: TmdbDao,
    private val prefs: SecurePrefs,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(4)
    private val inFlight = ConcurrentHashMap<String, Deferred<TmdbMatchEntity?>>()

    val hasKey: Boolean get() = prefs.config.value.hasTmdb

    fun matchKey(parsed: ParsedName): String =
        "${if (parsed.isTv) "tv" else "m"}|${FileNameParser.normalizedKey(parsed.title)}|${parsed.year ?: ""}"

    /** Returns the TMDB match for a parsed file name, or null when TMDB has nothing. */
    suspend fun match(parsed: ParsedName): TmdbMatchEntity? {
        if (!hasKey) throw TmdbKeyMissingException()
        if (parsed.title.isBlank()) return null
        val key = matchKey(parsed)
        dao.match(key)?.let { cached ->
            val ttl = if (cached.tmdbId == 0) NOT_FOUND_TTL else FOUND_TTL
            if (System.currentTimeMillis() - cached.fetchedAt < ttl) return cached.takeIf { it.tmdbId != 0 }
        }
        val deferred = inFlight.computeIfAbsent(key) {
            scope.async {
                try {
                    limiter.withPermit { lookup(parsed, key) }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun lookup(parsed: ParsedName, key: String): TmdbMatchEntity? {
        val title = parsed.title
        val year = parsed.year
        val (item, type) = if (parsed.isTv) {
            (search("tv", title, year) ?: search("tv", title, null))?.let { it to "tv" }
                ?: search("movie", title, year)?.let { it to "movie" }
        } else {
            (search("movie", title, year) ?: (if (year != null) search("movie", title, null) else null))?.let { it to "movie" }
                ?: search("tv", title, year)?.let { it to "tv" }
        } ?: (null to "movie")

        val entity = if (item == null) {
            TmdbMatchEntity(key, 0, type, title, year, null, null, 0.0, 0, System.currentTimeMillis())
        } else {
            TmdbMatchEntity(
                queryKey = key, tmdbId = item.id, mediaType = type, title = item.displayTitle, year = item.year,
                posterPath = item.posterPath, backdropPath = item.backdropPath, rating = item.voteAverage,
                voteCount = item.voteCount, fetchedAt = System.currentTimeMillis(),
            )
        }
        dao.putMatch(entity)
        return entity.takeIf { it.tmdbId != 0 }
    }

    private suspend fun search(type: String, query: String, year: Int?): SearchItem? {
        val url = baseUrl("search/$type").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("include_adult", "false")
            .apply {
                if (year != null) addQueryParameter(if (type == "movie") "year" else "first_air_date_year", year.toString())
            }
            .build()
        val results = json.decodeFromString<SearchResponse>(get(url)).results
        if (results.isEmpty()) return null
        return pickBest(results, query, year)
    }

    private fun pickBest(results: List<SearchItem>, query: String, year: Int?): SearchItem {
        val q = FileNameParser.normalizedKey(query)
        return results.take(10).maxByOrNull { item ->
            val t = FileNameParser.normalizedKey(item.displayTitle)
            val o = FileNameParser.normalizedKey(item.originalTitle ?: item.originalName ?: "")
            var score = 0.0
            if (t == q || o == q) score += 5 else if (t.contains(q) || q.contains(t)) score += 2
            if (year != null && item.year != null) {
                val diff = abs(item.year!! - year)
                score += when (diff) { 0 -> 3.0; 1 -> 1.5; else -> -1.0 }
            }
            score += minOf(item.voteCount, 5000) / 5000.0 // prefer well-known titles on ties
            score
        } ?: results.first()
    }

    /** Full details for the detail screen (cached for a week). */
    suspend fun details(match: TmdbMatchEntity): TitleDetails {
        if (!hasKey) throw TmdbKeyMissingException()
        val key = "${match.mediaType}:${match.tmdbId}"
        val cached = dao.detail(key)
        val body = if (cached != null && System.currentTimeMillis() - cached.fetchedAt < DETAIL_TTL) {
            cached.json
        } else {
            val append = if (match.mediaType == "tv") "credits,videos,content_ratings" else "credits,videos,release_dates"
            val url = baseUrl("${match.mediaType}/${match.tmdbId}").newBuilder()
                .addQueryParameter("append_to_response", append)
                .build()
            try {
                get(url).also { dao.putDetail(TmdbDetailEntity(key, it, System.currentTimeMillis())) }
            } catch (e: IOException) {
                cached?.json ?: throw e // stale cache beats nothing when offline
            }
        }
        return toDetails(json.decodeFromString<DetailsDto>(body), match.mediaType)
    }

    private fun toDetails(d: DetailsDto, type: String): TitleDetails {
        val country = Locale.getDefault().country.ifBlank { "US" }
        val certification = if (type == "tv") {
            val ratings = d.contentRatings?.results.orEmpty()
            (ratings.firstOrNull { it.country == country } ?: ratings.firstOrNull { it.country == "US" })?.rating
        } else {
            val countries = d.releaseDates?.results.orEmpty()
            listOfNotNull(countries.firstOrNull { it.country == country }, countries.firstOrNull { it.country == "US" })
                .firstNotNullOfOrNull { c -> c.releaseDates.firstOrNull { it.certification.isNotBlank() }?.certification }
        }?.takeIf { it.isNotBlank() }

        val trailer = d.videos?.results.orEmpty()
            .filter { it.site.equals("YouTube", true) && it.key.isNotBlank() }
            .sortedWith(compareByDescending<VideoDto> { it.type == "Trailer" }.thenByDescending { it.official })
            .firstOrNull { it.type == "Trailer" || it.type == "Teaser" }

        val directors = if (type == "tv") d.createdBy.map { it.name }
        else d.credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name }

        return TitleDetails(
            tmdbId = d.id, mediaType = type,
            title = d.title ?: d.name ?: "",
            year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull(),
            runtimeMinutes = d.runtime?.takeIf { it > 0 } ?: d.episodeRunTime.firstOrNull(),
            certification = certification,
            genres = d.genres.map { it.name },
            rating = d.voteAverage, voteCount = d.voteCount,
            overview = d.overview.orEmpty(), tagline = d.tagline?.takeIf { it.isNotBlank() },
            posterPath = d.posterPath, backdropPath = d.backdropPath,
            directors = directors.distinct(),
            cast = d.credits?.cast.orEmpty().sortedBy { it.order }.take(20).map { CastMember(it.name, it.character, it.profilePath) },
            trailerYoutubeKey = trailer?.key,
            seasons = d.numberOfSeasons,
        )
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private val apiKey: String get() = prefs.config.value.tmdbKey

    /** Supports both the v3 "API key" and the v4 "read access token" (a JWT starting with eyJ). */
    private fun isBearer(key: String) = key.startsWith("eyJ") && key.length > 60

    private fun baseUrl(path: String): HttpUrl {
        val builder = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("language", Locale.getDefault().toLanguageTag().ifBlank { "en-US" })
        if (!isBearer(apiKey)) builder.addQueryParameter("api_key", apiKey)
        return builder.build()
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (isBearer(apiKey)) header("Authorization", "Bearer $apiKey")
        }.build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw TmdbAuthException()
                !response.isSuccessful -> throw IOException("TMDB returned HTTP ${response.code}")
                else -> response.body.string()
            }
        }
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private const val FOUND_TTL = 30 * DAY
        private const val NOT_FOUND_TTL = 3 * DAY
        private const val DETAIL_TTL = 7 * DAY
    }
}
