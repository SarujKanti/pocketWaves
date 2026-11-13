package com.skd.pocketwaves

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class SongsAdapter(
    private var songs: List<Song>,
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    fun getSongs(): List<Song> = songs

    fun shuffleSongs() {
        songs = songs.shuffled()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.song_item, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song)
        holder.itemView.setOnClickListener { onItemClick(song) }

        // Highlight currently playing song with a rounded, theme-adaptive card
        if (song.isPlaying) {
            holder.itemView.setBackgroundResource(R.drawable.bg_song_item_playing)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun getItemCount(): Int = songs.size

    fun submitList(newList: List<Song>) {
        songs = newList
        notifyDataSetChanged()
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.artistTextView)
        private val albumImageView: ImageView = itemView.findViewById(R.id.albumImageView)
        private val playingIndicator: ImageView = itemView.findViewById(R.id.playingIndicator)

        fun bind(song: Song) {
            titleTextView.text = song.title
            artistTextView.text = song.artist
            playingIndicator.visibility = if (song.isPlaying) View.VISIBLE else View.GONE
            // Use theme-adaptive text colors from color resources
            titleTextView.setTextColor(
                ContextCompat.getColor(itemView.context, R.color.text_primary)
            )
            artistTextView.setTextColor(
                ContextCompat.getColor(itemView.context, R.color.text_secondary)
            )
            Glide.with(itemView.context)
                .load(song.albumArtUri)
                .placeholder(R.drawable.audioicon)
                .error(R.drawable.audioicon)
                .into(albumImageView)
        }
    }
}
