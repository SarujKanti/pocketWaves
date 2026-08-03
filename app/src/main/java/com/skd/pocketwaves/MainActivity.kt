package com.skd.pocketwaves

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SearchView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.lang.ref.WeakReference
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var songsAdapter: SongsAdapter
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSongIndex = -1
    private lateinit var visualizerView: CustomVisualizerView
    private lateinit var playingCardView: CardView
    private lateinit var controlPanel: LinearLayout
    private lateinit var searchCard: CardView
    private lateinit var searchView: SearchView

    private lateinit var modeTabLayout: TabLayout
    private lateinit var onlineContainer: LinearLayout
    private lateinit var onlineSearchView: SearchView
    private lateinit var onlineRecyclerView: RecyclerView
    private lateinit var onlineProgressBar: ProgressBar
    private lateinit var onlineEmptyText: TextView
    private lateinit var onlineAdapter: SongsAdapter
    private var onlineResults: List<Song> = emptyList()
    private var isPlayingOnline = false
    private var isOnlineTabSelected = false
    private var searchDebounceRunnable: Runnable? = null

    private lateinit var youtubePlayerView: YouTubePlayerView
    private var youTubePlayer: YouTubePlayer? = null
    private var isPlayingYoutube = false
    private var isYoutubePlaying = false
    private var currentPlayingSong: Song? = null

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isShuffleOn = false
    private var isRepeatOneOn = false
    private var isRepeatAllOn = false
    // true = "All Songs" (recyclerView) is visible; false = "Now Playing" is visible
    private var isPlaylistVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge() is the official AndroidX helper (activity 1.8+).
        // It calls setDecorFitsSystemWindows(false) AND sets up correct status-bar
        // / nav-bar colors for all Android versions including 15+.
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        instance = WeakReference(this)
        playingCardView = findViewById(R.id.Playing_Song_Cardview)
        controlPanel    = findViewById(R.id.controlPanel)
        searchCard      = findViewById(R.id.searchCard)
        searchView      = findViewById(R.id.searchView)

        modeTabLayout     = findViewById(R.id.modeTabLayout)
        onlineContainer   = findViewById(R.id.onlineContainer)
        onlineSearchView  = findViewById(R.id.onlineSearchView)
        onlineRecyclerView = findViewById(R.id.onlineRecyclerView)
        onlineProgressBar = findViewById(R.id.onlineProgressBar)
        onlineEmptyText   = findViewById(R.id.onlineEmptyText)

        styleSearchView(searchView)
        styleSearchView(onlineSearchView)
        // Start the lifecycle service so onTaskRemoved() fires when user clears the app
        startService(Intent(this, AppLifecycleService::class.java))

        // fitsSystemWindows="true" on rootLayout handles all inset padding
        // automatically — no manual listener needed.

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "MusicPlayer::WakeLock"
        )
        wakeLock.acquire()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songsAdapter = SongsAdapter(emptyList()) { song -> playSong(song) }
        recyclerView.adapter = songsAdapter

        onlineRecyclerView.layoutManager = LinearLayoutManager(this)
        onlineAdapter = SongsAdapter(emptyList()) { song ->
            isPlayingOnline = true
            currentSongIndex = onlineResults.indexOf(song)
            playSong(song)
        }
        onlineRecyclerView.adapter = onlineAdapter

        modeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = switchTab(tab.position == 1)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        onlineSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchDebounceRunnable?.let { handler.removeCallbacks(it) }
                if (query.isNotBlank()) searchOnlineTracks(query.trim())
                onlineSearchView.clearFocus() // dismiss the keyboard so results/errors are visible
                return true
            }
            override fun onQueryTextChange(newText: String): Boolean {
                searchDebounceRunnable?.let { handler.removeCallbacks(it) }
                val trimmed = newText.trim()
                if (trimmed.length < 2) {
                    if (trimmed.isEmpty()) resetOnlineResults()
                    return true
                }
                val runnable = Runnable { searchOnlineTracks(trimmed) }
                searchDebounceRunnable = runnable
                handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
                return true
            }
        })

        seekBar = findViewById(R.id.seekBar)
        setupSeekBarListener()

        youtubePlayerView = findViewById(R.id.youtubePlayerView)
        lifecycle.addObserver(youtubePlayerView)
        youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                this@MainActivity.youTubePlayer = youTubePlayer
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                if (!isPlayingYoutube) return
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> {
                        isYoutubePlaying = true
                        findViewById<Button>(R.id.pauseResumeButton).setBackgroundResource(R.drawable.pause)
                        currentPlayingSong?.let { showNotification(it, true) }
                    }
                    PlayerConstants.PlayerState.PAUSED -> {
                        isYoutubePlaying = false
                        findViewById<Button>(R.id.pauseResumeButton).setBackgroundResource(R.drawable.play)
                        currentPlayingSong?.let { showNotification(it, false) }
                    }
                    PlayerConstants.PlayerState.ENDED -> {
                        isYoutubePlaying = false
                        playNextSong()
                    }
                    else -> {}
                }
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                if (!isPlayingYoutube) return
                val posMs = (second * 1000).toInt()
                seekBar.progress = posMs
                findViewById<TextView>(R.id.positive_playback_timer).text = formatTime(posMs)
                findViewById<TextView>(R.id.negative_playback_timer).text =
                    "-${formatTime((seekBar.max - posMs).coerceAtLeast(0))}"
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                seekBar.max = (duration * 1000).toInt()
            }
        })

        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener {
                it.start()
                seekBar.max = it.duration
                updateSeekBar()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Error playing song.", Toast.LENGTH_SHORT).show()
                false
            }
        }

        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        visualizerView = findViewById(R.id.visualizerView)

        if (hasRequiredPermissions()) {
            loadSongs()
            setupVisualizer()
        } else {
            showPermissionRationaleAndRequest()
        }
        
        // Playback controls
        findViewById<Button>(R.id.pauseResumeButton).setOnClickListener { togglePlaybackSafe() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { playPreviousSong() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNextSong() }
        findViewById<Button>(R.id.shuffleButton).setOnClickListener { toggleShuffle() }
        findViewById<Button>(R.id.reapet_button).setOnClickListener { toggleRepeat() }

        val playlistButton = findViewById<Button>(R.id.playlist_button)

        playlistButton.setOnClickListener { togglePlaylistView() }

        val searchButton = findViewById<Button>(R.id.search_button)

        searchButton.setOnClickListener {
            if (searchCard.visibility == View.VISIBLE) {
                searchCard.visibility = View.GONE
                if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchView.windowToken, 0)
            } else {
                searchCard.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE
                playingCardView.visibility = View.GONE
                visualizerView.visibility = View.GONE
                controlPanel.visibility = View.GONE
                searchView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = false
            override fun onQueryTextChange(newText: String): Boolean {
                val filtered = songsAdapter.getSongs().filter { song ->
                    song.title.contains(newText, ignoreCase = true) ||
                            song.artist.contains(newText, ignoreCase = true)
                }
                songsAdapter.submitList(filtered)
                return true
            }
        })

        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                searchView.setQuery("", false)
                loadSongs()
            }
        }

        // Back press → move app to background instead of destroying it,
        // so the MediaPlayer and notification stay alive.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private fun hasRequiredPermissions(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionRationaleAndRequest() {
        // On Android 12 and below the system describes READ_EXTERNAL_STORAGE as
        // "access photos and media" — show a friendly explanation first so users
        // understand it is only used to find audio files on their device.
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            "Pocket Waves needs permission to access audio files on your device to show your music library."
        else
            "Pocket Waves needs storage permission to find and play audio files saved on your device.\n\n" +
                    "Android describes this as \"access photos and media\" but the app only reads audio files."

        AlertDialog.Builder(this)
            .setTitle("Audio File Access")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> requestRequiredPermissions() }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    // The framework SearchView renders its magnifier/close icons and input text in
    // plain black by default, which clashes with the app's purple-accented theme.
    // Its internal child IDs (search_mag_icon etc.) aren't part of the public SDK
    // stubs on newer compileSdk versions, so restyle by walking the view tree instead.
    private fun styleSearchView(sv: SearchView) {
        fun styleRecursively(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    view.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
                is ImageView -> view.setColorFilter(
                    ContextCompat.getColor(this, R.color.text_secondary), PorterDuff.Mode.SRC_IN
                )
                is android.view.ViewGroup -> {
                    for (i in 0 until view.childCount) styleRecursively(view.getChildAt(i))
                }
            }
        }
        styleRecursively(sv)
    }

    private fun togglePlaybackSafe() {
        if (currentSongIndex == -1) {
            if (isOnlineTabSelected && onlineResults.isNotEmpty()) {
                isPlayingOnline = true
                currentSongIndex = 0
                playSong(onlineResults[0])
            } else if (!isOnlineTabSelected && songsAdapter.itemCount > 0) {
                isPlayingOnline = false
                currentSongIndex = 0
                playSong(songsAdapter.getSongs()[0])
            } else {
                Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            }
            return
        }
        togglePlayback()
    }

    // Toggles between the current tab's song list and the "Now Playing" screen.
    private fun togglePlaylistView() {
        val listContainer: View = if (isOnlineTabSelected) onlineContainer else recyclerView
        val listHeading = if (isOnlineTabSelected) "Online" else "All Songs"

        if (searchCard.visibility == View.VISIBLE) {
            searchCard.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchView.windowToken, 0)
        }

        val playlistButton = findViewById<Button>(R.id.playlist_button)
        val heading = findViewById<TextView>(R.id.heading)

        if (isPlaylistVisible) {
            if (isPlayingYoutube) {
                youtubePlayerView.visibility = View.VISIBLE
                playingCardView.visibility = View.GONE
            } else {
                playingCardView.visibility = View.VISIBLE
                youtubePlayerView.visibility = View.GONE
            }
            listContainer.visibility = View.GONE
            playlistButton.setBackgroundResource(R.drawable.playlist)
            heading.text = "Now Playing"
            visualizerView.visibility = if (currentSongIndex != -1 && !isPlayingYoutube) View.VISIBLE else View.GONE
        } else {
            playingCardView.visibility = View.GONE
            youtubePlayerView.visibility = View.GONE
            listContainer.visibility = View.VISIBLE
            playlistButton.setBackgroundResource(R.drawable.playing_button)
            heading.text = listHeading
            visualizerView.visibility = View.GONE
        }
        if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
        isPlaylistVisible = !isPlaylistVisible
    }

    // Switches between the Offline (local library) and Online (Jamendo search) tabs.
    private fun switchTab(online: Boolean) {
        isOnlineTabSelected = online

        if (searchCard.visibility == View.VISIBLE) {
            searchCard.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchView.windowToken, 0)
        }

        val searchButton = findViewById<Button>(R.id.search_button)
        val playlistButton = findViewById<Button>(R.id.playlist_button)
        val heading = findViewById<TextView>(R.id.heading)

        playingCardView.visibility = View.GONE
        youtubePlayerView.visibility = View.GONE
        visualizerView.visibility = View.GONE
        isPlaylistVisible = true

        if (online) {
            recyclerView.visibility = View.GONE
            onlineContainer.visibility = View.VISIBLE
            searchButton.visibility = View.GONE
            heading.text = "Online"
        } else {
            onlineContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            searchButton.visibility = View.VISIBLE
            heading.text = "All Songs"
        }
        playlistButton.setBackgroundResource(R.drawable.playing_button)
        if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
    }

    private fun searchOnlineTracks(query: String) {
        onlineProgressBar.visibility = View.VISIBLE
        onlineEmptyText.visibility = View.GONE
        onlineRecyclerView.visibility = View.GONE

        // Jamendo (independent/CC full tracks) and YouTube (covers mainstream/
        // commercial songs Jamendo can't legally offer) are searched in parallel
        // and merged, YouTube first since it has far broader coverage.
        var youtubeResult: List<Song>? = null
        var jamendoResult: List<Song>? = null
        var youtubeError: String? = null
        var jamendoError: String? = null

        fun tryFinish() {
            val yt = youtubeResult ?: return
            val jm = jamendoResult ?: return
            val combined = yt + jm
            when {
                combined.isNotEmpty() -> showOnlineResults(combined, query)
                youtubeError != null -> showOnlineError(youtubeError!!)
                jamendoError != null -> showOnlineError(jamendoError!!)
                else -> showOnlineResults(emptyList(), query)
            }
        }

        fetchYoutubeTracks(query) { tracks, error ->
            youtubeResult = tracks ?: emptyList()
            youtubeError = error
            tryFinish()
        }
        fetchJamendoCombined(query) { tracks, error ->
            jamendoResult = tracks
            jamendoError = error
            tryFinish()
        }
    }

    // Full-phrase Jamendo search, broadened to "any word" matching (merged, deduped)
    // if the phrase itself matches nothing — Jamendo's search is an AND across terms.
    private fun fetchJamendoCombined(query: String, callback: (List<Song>, String?) -> Unit) {
        fetchJamendoTracks(query) { tracks, error ->
            if (error != null) {
                callback(emptyList(), error)
                return@fetchJamendoTracks
            }
            if (tracks!!.isNotEmpty()) {
                callback(tracks, null)
                return@fetchJamendoTracks
            }
            val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.distinct()
            if (words.size <= 1) {
                callback(emptyList(), null)
                return@fetchJamendoTracks
            }
            fetchMergedByWords(words, callback)
        }
    }

    // Searches each word separately and merges the union of matches (deduped by track id).
    private fun fetchMergedByWords(words: List<String>, callback: (List<Song>, String?) -> Unit) {
        val merged = LinkedHashMap<Long, Song>()
        var remaining = words.size
        var lastError: String? = null

        words.forEach { word ->
            fetchJamendoTracks(word) { tracks, error ->
                remaining--
                if (error != null) lastError = error
                tracks?.forEach { merged.putIfAbsent(it.id, it) }
                if (remaining == 0) {
                    callback(merged.values.toList(), if (merged.isEmpty()) lastError else null)
                }
            }
        }
    }

    private fun fetchYoutubeTracks(query: String, callback: (List<Song>?, String?) -> Unit) {
        val apiKey = BuildConfig.YOUTUBE_API_KEY
        if (apiKey.isBlank()) {
            callback(emptyList(), null) // not configured — skip silently, Jamendo still works
            return
        }
        YoutubeClient.api.searchVideos(apiKey, query)
            .enqueue(object : Callback<YoutubeSearchResponse> {
                override fun onResponse(
                    call: Call<YoutubeSearchResponse>,
                    response: Response<YoutubeSearchResponse>
                ) {
                    if (isFinishing || isDestroyed) return
                    if (!response.isSuccessful) {
                        callback(null, "YouTube search failed (HTTP ${response.code()}). Check your API key/quota.")
                        return
                    }
                    val songs = response.body()?.items.orEmpty().mapNotNull { it.toSong() }
                    callback(songs, null)
                }

                override fun onFailure(call: Call<YoutubeSearchResponse>, t: Throwable) {
                    if (isFinishing || isDestroyed) return
                    callback(null, "Couldn't reach YouTube. Check your connection.")
                }
            })
    }

    private fun fetchJamendoTracks(term: String, callback: (List<Song>?, String?) -> Unit) {
        JamendoClient.api.searchTracks(BuildConfig.JAMENDO_CLIENT_ID, term)
            .enqueue(object : Callback<JamendoSearchResponse> {
                override fun onResponse(
                    call: Call<JamendoSearchResponse>,
                    response: Response<JamendoSearchResponse>
                ) {
                    if (isFinishing || isDestroyed) return
                    val payload = response.body()
                    if (!response.isSuccessful || payload == null) {
                        callback(null, "Search failed (HTTP ${response.code()}).")
                        return
                    }
                    if (payload.headers.status == "failed") {
                        callback(
                            null,
                            "Jamendo: ${payload.headers.error_message}. Add a free client ID to local.properties (JAMENDO_CLIENT_ID)."
                        )
                        return
                    }
                    callback(payload.results.map { it.toSong() }, null)
                }

                override fun onFailure(call: Call<JamendoSearchResponse>, t: Throwable) {
                    if (isFinishing || isDestroyed) return
                    callback(null, "Couldn't reach Jamendo. Check your connection.")
                }
            })
    }

    private fun showOnlineResults(tracks: List<Song>, query: String) {
        onlineProgressBar.visibility = View.GONE
        onlineResults = tracks
        onlineAdapter.submitList(tracks)
        if (tracks.isEmpty()) {
            onlineEmptyText.text = "No results for \"$query\""
            onlineEmptyText.visibility = View.VISIBLE
            onlineRecyclerView.visibility = View.GONE
        } else {
            onlineEmptyText.visibility = View.GONE
            onlineRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun showOnlineError(message: String) {
        onlineProgressBar.visibility = View.GONE
        onlineEmptyText.text = message
        onlineEmptyText.visibility = View.VISIBLE
        onlineRecyclerView.visibility = View.GONE
    }

    // Clears search results back to the initial prompt when the search box is emptied.
    private fun resetOnlineResults() {
        onlineResults = emptyList()
        onlineAdapter.submitList(emptyList())
        onlineProgressBar.visibility = View.GONE
        onlineEmptyText.text = "Search for songs to stream online"
        onlineEmptyText.visibility = View.VISIBLE
        onlineRecyclerView.visibility = View.GONE
    }

    private fun setupVisualizer() {
        if (!::visualizerView.isInitialized) return
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                visualizerView.setPlayer(mediaPlayer.audioSessionId)
            }
            else -> {
                // Request RECORD_AUDIO separately — kept out of first-launch request
                // so the initial dialog only asks to access audio files.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_REQUEST_CODE
                )
            }
        }
    }

    private fun loadSongs() {
        val songsList = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    cursor.getLong(albumIdCol)
                ).toString()
                songsList.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown",
                        path = cursor.getString(pathCol) ?: "",
                        albumArtUri = albumArtUri
                    )
                )
            }
        }
        songsList.reverse()
        songsAdapter = SongsAdapter(songsList) { song ->
            isPlayingOnline = false
            currentSongIndex = songsList.indexOf(song)
            playSong(song)
        }
        recyclerView.adapter = songsAdapter
        if (songsList.isNotEmpty()) recyclerView.scrollToPosition(0)
    }

    private fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        val btn = findViewById<Button>(R.id.shuffleButton)
        btn.setBackgroundResource(if (isShuffleOn) R.drawable.shuffle_on else R.drawable.shuffle_off)
        if (isPlayingOnline) return
        if (isShuffleOn) songsAdapter.shuffleSongs() else loadSongs()
    }

    private fun toggleRepeat() {
        val btn = findViewById<Button>(R.id.reapet_button)
        if (isRepeatAllOn) {
            isRepeatAllOn = false; isRepeatOneOn = false
            btn.setBackgroundResource(R.drawable.repeat)
        } else if (isRepeatOneOn) {
            isRepeatAllOn = true; isRepeatOneOn = false
            btn.setBackgroundResource(R.drawable.repeat_on)
        } else {
            isRepeatOneOn = true
            btn.setBackgroundResource(R.drawable.repeat_one)
        }
        if (isRepeatAllOn) isShuffleOn = false
    }

    internal fun playNextSong() {
        if (isPlayingOnline) {
            if (onlineResults.isEmpty()) return
            when {
                isShuffleOn -> {
                    currentSongIndex = onlineResults.indices.random()
                    playSong(onlineResults[currentSongIndex])
                }
                isRepeatOneOn -> playSong(onlineResults[currentSongIndex])
                currentSongIndex < onlineResults.size - 1 -> {
                    playSong(onlineResults[++currentSongIndex])
                }
                isRepeatAllOn -> {
                    currentSongIndex = 0
                    playSong(onlineResults[currentSongIndex])
                }
                else -> {
                    currentSongIndex = -1
                    if (isPlayingYoutube) youTubePlayer?.pause() else mediaPlayer.stop()
                    findViewById<Button>(R.id.pauseResumeButton).setBackgroundResource(R.drawable.play)
                }
            }
            return
        }
        if (songsAdapter.itemCount == 0) return
        when {
            isShuffleOn -> {
                currentSongIndex = (0 until songsAdapter.itemCount).random()
                playSong(songsAdapter.getSongs()[currentSongIndex])
            }
            isRepeatOneOn -> playSong(songsAdapter.getSongs()[currentSongIndex])
            currentSongIndex < songsAdapter.itemCount - 1 -> {
                playSong(songsAdapter.getSongs()[++currentSongIndex])
            }
            isRepeatAllOn -> {
                currentSongIndex = 0
                playSong(songsAdapter.getSongs()[currentSongIndex])
            }
            else -> {
                currentSongIndex = -1
                mediaPlayer.stop()
                findViewById<Button>(R.id.pauseResumeButton).setBackgroundResource(R.drawable.play)
            }
        }
    }

    internal fun playPreviousSong() {
        if (isPlayingOnline) {
            if (onlineResults.isEmpty()) return
            if (currentSongIndex - 1 >= 0) {
                playSong(onlineResults[--currentSongIndex])
            } else if (isRepeatAllOn) {
                currentSongIndex = onlineResults.size - 1
                playSong(onlineResults[currentSongIndex])
            }
            return
        }
        if (songsAdapter.itemCount == 0) return
        if (currentSongIndex - 1 >= 0) {
            playSong(songsAdapter.getSongs()[--currentSongIndex])
        } else if (isRepeatAllOn) {
            currentSongIndex = songsAdapter.itemCount - 1
            playSong(songsAdapter.getSongs()[currentSongIndex])
        }
    }

    private fun playSong(song: Song) {
        val pauseBtn = findViewById<Button>(R.id.pauseResumeButton)
        val albumImageView = findViewById<ImageView>(R.id.Playing_Song_Imageview)
        val artistView = findViewById<TextView>(R.id.song_artist)
        val heading = findViewById<TextView>(R.id.heading)
        val playlistBtn = findViewById<Button>(R.id.playlist_button)
        val titleView = findViewById<TextView>(R.id.song_title)

        try {
            val activeAdapter = if (song.isOnline) onlineAdapter else songsAdapter
            activeAdapter.getSongs().forEach { it.isPlaying = false }
            val index = activeAdapter.getSongs().indexOf(song)
            if (index != -1) {
                activeAdapter.getSongs()[index].isPlaying = true
                activeAdapter.notifyDataSetChanged()
            }

            currentPlayingSong = song
            isPlayingYoutube = song.isYoutube

            titleView.text = song.title
            titleView.isSelected = true  // enables marquee scrolling
            titleView.setOnClickListener {
                if (song.isOnline) onlineRecyclerView.smoothScrollToPosition(index)
                else recyclerView.smoothScrollToPosition(index)
            }
            artistView.text = song.artist

            if (song.isYoutube) {
                mediaPlayer.reset() // stop any local/Jamendo audio playback
                youtubePlayerView.visibility = View.VISIBLE
                playingCardView.visibility = View.GONE
                visualizerView.visibility = View.GONE
                youTubePlayer?.loadVideo(song.youtubeVideoId, 0f)
                pauseBtn.setBackgroundResource(R.drawable.pause)
            } else {
                youTubePlayer?.pause() // stop any previously loaded YouTube video
                youtubePlayerView.visibility = View.GONE
                playingCardView.visibility = View.VISIBLE

                mediaPlayer.reset()
                if (song.isOnline) {
                    mediaPlayer.setDataSource(song.streamUrl)
                } else {
                    val songUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                    )
                    mediaPlayer.setDataSource(applicationContext, songUri)
                }
                mediaPlayer.prepareAsync()

                mediaPlayer.setOnPreparedListener {
                    it.start()
                    seekBar.max = it.duration
                    updateSeekBar()
                    setupVisualizer()

                    pauseBtn.setBackgroundResource(R.drawable.pause)
                    if (song.isOnline) {
                        Glide.with(this@MainActivity)
                            .load(song.albumArtUri)
                            .placeholder(R.drawable.audioicon)
                            .error(R.drawable.audioicon)
                            .into(albumImageView)
                    } else {
                        albumImageView.setImageURI(Uri.parse(song.albumArtUri))
                        if (albumImageView.drawable == null) {
                            albumImageView.setImageResource(R.drawable.audioicon)
                        }
                    }
                }

                mediaPlayer.setOnCompletionListener { playNextSong() }
                visualizerView.visibility = View.VISIBLE
            }

            showNotification(song, true)
            if (index != -1) {
                if (song.isOnline) onlineRecyclerView.smoothScrollToPosition(index)
                else recyclerView.smoothScrollToPosition(index)
            }
            recyclerView.visibility = View.GONE
            onlineContainer.visibility = View.GONE
            heading.text = "Now Playing"
            playlistBtn.setBackgroundResource(R.drawable.playlist)
            isPlaylistVisible = false   // keep flag in sync with actual UI state
            findViewById<CardView>(R.id.searchCard).visibility = View.GONE
            controlPanel.visibility = View.VISIBLE

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error playing song.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    internal fun togglePlayback() {
        if (isPlayingYoutube) {
            // Icon + notification update via the onStateChange listener once the
            // player actually transitions, since that's the source of truth.
            if (isYoutubePlaying) youTubePlayer?.pause() else youTubePlayer?.play()
            return
        }
        val pauseBtn = findViewById<Button>(R.id.pauseResumeButton)
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            pauseBtn.setBackgroundResource(R.drawable.play)
            visualizerView.visibility = View.GONE
        } else {
            mediaPlayer.start()
            pauseBtn.setBackgroundResource(R.drawable.pause)
            visualizerView.visibility = View.VISIBLE
            updateSeekBar()
        }
        // Update notification to reflect new play/pause state
        currentPlayingSong?.let { showNotification(it, mediaPlayer.isPlaying) }
    }

    private fun updateSeekBar() {
        if (mediaPlayer.isPlaying) {
            val pos = mediaPlayer.currentPosition
            seekBar.progress = pos
            findViewById<TextView>(R.id.positive_playback_timer).text = formatTime(pos)
            findViewById<TextView>(R.id.negative_playback_timer).text =
                "-${formatTime(mediaPlayer.duration - pos)}"
            handler.postDelayed({ updateSeekBar() }, 1000)
        }
    }

    private fun setupSeekBarListener() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (isPlayingYoutube) youTubePlayer?.seekTo(progress / 1000f)
                else mediaPlayer.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { isUserSeeking = false }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification(song: Song, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Load album art bitmap (null → system uses small icon only)
        // Online tracks use an http(s) album art URL, which contentResolver can't open directly.
        val albumBitmap = try {
            if (song.isOnline) null
            else contentResolver.openInputStream(Uri.parse(song.albumArtUri))?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }

        // Tap notification → bring app to foreground
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Helper for broadcast PendingIntents to NotificationActionReceiver
        fun actionIntent(action: String, reqCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                this, reqCode,
                Intent(action, null, this, NotificationActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.audioicon)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(albumBitmap)
            .setContentIntent(openApp)
            .setOngoing(isPlaying)           // non-dismissible while playing
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // show on lock screen
            .setOnlyAlertOnce(true)
            .setSound(null)
            // Three action buttons: previous | play/pause | next
            .addAction(R.drawable.previous, "Previous",
                actionIntent(ACTION_PREVIOUS, 101))
            .addAction(
                if (isPlaying) R.drawable.pause else R.drawable.play,
                if (isPlaying) "Pause" else "Play",
                actionIntent(ACTION_TOGGLE, 102)
            )
            .addAction(R.drawable.next, "Next",
                actionIntent(ACTION_NEXT, 103))
            // MediaStyle shows actions in the collapsed notification row
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        mediaPlayer.release()
        notificationManager.cancel(NOTIFICATION_ID)
        visualizerView.releaseVisualizer()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
                val idx = permissions.indexOf(storagePermission)
                if (idx != -1 && grantResults[idx] == PackageManager.PERMISSION_GRANTED) {
                    loadSongs()
                    setupVisualizer()
                } else {
                    Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                }
            }
            RECORD_AUDIO_REQUEST_CODE -> {
                val idx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
                if (idx != -1 && grantResults[idx] == PackageManager.PERMISSION_GRANTED) {
                    setupVisualizer()
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private const val RECORD_AUDIO_REQUEST_CODE = 456
        const val CHANNEL_ID     = "MusicPlayerChannel"
        const val ACTION_TOGGLE  = "com.skd.audioplayer.ACTION_TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS = "com.skd.audioplayer.ACTION_PLAY_PREVIOUS"
        const val ACTION_NEXT    = "com.skd.audioplayer.ACTION_PLAY_NEXT"
        const val NOTIFICATION_ID = 1
        private const val SEARCH_DEBOUNCE_MS = 450L
        var instance: WeakReference<MainActivity>? = null
    }
}
