package com.skd.pocketwaves

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class JamendoTrack(
    val id: String,
    val name: String,
    val artist_name: String,
    val album_image: String,
    val audio: String,
    val duration: Int
)

data class JamendoHeaders(
    val status: String,
    val code: Int,
    val error_message: String
)

data class JamendoSearchResponse(
    val headers: JamendoHeaders,
    val results: List<JamendoTrack>
)

interface JamendoApiService {
    @GET("tracks/")
    fun searchTracks(
        @Query("client_id") clientId: String,
        @Query("search") search: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 30
    ): Call<JamendoSearchResponse>
}

object JamendoClient {
    private const val BASE_URL = "https://api.jamendo.com/v3.0/"

    val api: JamendoApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JamendoApiService::class.java)
    }
}

fun JamendoTrack.toSong(): Song = Song(
    id = id.toLongOrNull() ?: id.hashCode().toLong(),
    title = name,
    artist = artist_name,
    path = "",
    albumArtUri = album_image,
    isOnline = true,
    streamUrl = audio
)
