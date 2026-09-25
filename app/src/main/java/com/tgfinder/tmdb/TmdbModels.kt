package com.tgfinder.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(val results: List<SearchItem> = emptyList())

@Serializable
data class SearchItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
) {
    val displayTitle: String get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

@Serializable
data class DetailsDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val genres: List<Genre> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val credits: Credits? = null,
    val videos: Videos? = null,
    @SerialName("release_dates") val releaseDates: ReleaseDates? = null,
    @SerialName("content_ratings") val contentRatings: ContentRatings? = null,
    @SerialName("created_by") val createdBy: List<Crew> = emptyList(),
)

@Serializable data class Genre(val id: Int = 0, val name: String = "")
@Serializable data class Credits(val cast: List<Cast> = emptyList(), val crew: List<Crew> = emptyList())
@Serializable data class Cast(
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 999,
)
@Serializable data class Crew(
    val name: String = "",
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)
@Serializable data class Videos(val results: List<VideoDto> = emptyList())
@Serializable data class VideoDto(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    val name: String = "",
)
@Serializable data class ReleaseDates(val results: List<ReleaseDateCountry> = emptyList())
@Serializable data class ReleaseDateCountry(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<ReleaseDate> = emptyList(),
)
@Serializable data class ReleaseDate(val certification: String = "", val type: Int = 0)
@Serializable data class ContentRatings(val results: List<ContentRating> = emptyList())
@Serializable data class ContentRating(@SerialName("iso_3166_1") val country: String = "", val rating: String = "")

/** Everything the detail screen shows, flattened from movie or TV details. */
data class TitleDetails(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val runtimeMinutes: Int?,
    val certification: String?,
    val genres: List<String>,
    val rating: Double,
    val voteCount: Int,
    val overview: String,
    val tagline: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val directors: List<String>,
    val cast: List<CastMember>,
    val trailerYoutubeKey: String?,
    val seasons: Int?,
)

data class CastMember(val name: String, val character: String?, val profilePath: String?)

object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String?) = path?.let { BASE + "w342" + it }
    fun posterLarge(path: String?) = path?.let { BASE + "w500" + it }
    fun backdrop(path: String?) = path?.let { BASE + "w780" + it }
    fun profile(path: String?) = path?.let { BASE + "w185" + it }
}
