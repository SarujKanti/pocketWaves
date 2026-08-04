package com.skd.pocketwaves

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val albumArtUri: String,
    var isPlaying: Boolean = false,
    val isOnline: Boolean = false,
    val streamUrl: String = "",
    val youtubeVideoId: String = ""
) {
    // Jamendo tracks stream a direct audio URL via MediaPlayer; YouTube tracks
    // play through the official embedded player instead (no direct audio URL).
    val isYoutube: Boolean get() = youtubeVideoId.isNotEmpty()
}
