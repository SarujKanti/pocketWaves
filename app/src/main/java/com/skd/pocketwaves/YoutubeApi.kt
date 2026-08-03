package com.skd.pocketwaves

import android.text.Html
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class YoutubeVideoId(
    val videoId: String?
)

data class YoutubeThumbnail(
    val url: String
)

data class YoutubeThumbnails(
    val default: YoutubeThumbnail?,
    val medium: YoutubeThumbnail?
)

data class YoutubeSnippet(
    val title: String,
    val channelTitle: String,
    val thumbnails: YoutubeThumbnails
)

data class YoutubeSearchItem(
    val id: YoutubeVideoId,
    val snippet: YoutubeSnippet
)

data class YoutubeSearchResponse(
    val items: List<YoutubeSearchItem>
)

interface YoutubeApiService {
    @GET("search")
    fun searchVideos(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("videoCategoryId") videoCategoryId: String = "10",
        @Query("videoEmbeddable") videoEmbeddable: String = "true",
        @Query("maxResults") maxResults: Int = 15
    ): Call<YoutubeSearchResponse>
}

object YoutubeClient {
    private const val BASE_URL = "https://www.googleapis.com/youtube/v3/"

    val api: YoutubeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YoutubeApiService::class.java)
    }
}

@Suppress("DEPRECATION")
private fun String.unescapeHtml(): String = Html.fromHtml(this).toString()

fun YoutubeSearchItem.toSong(): Song? {
    val videoId = id.videoId ?: return null
    return Song(
        id = videoId.hashCode().toLong(),
        title = snippet.title.unescapeHtml(),
        artist = snippet.channelTitle.unescapeHtml(),
        path = "",
        albumArtUri = snippet.thumbnails.medium?.url ?: snippet.thumbnails.default?.url ?: "",
        isOnline = true,
        streamUrl = "",
        youtubeVideoId = videoId
    )
}
