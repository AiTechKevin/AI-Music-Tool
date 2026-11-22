package com.aimusicstudio.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aimusicstudio.data.StemTrack
import java.io.File
import kotlin.math.pow

/**
 * Synchronized multi-track playback engine powered by Media3 ExoPlayer.
 * Supports individual stem volume, Mute, Solo, Instrument Removal,
 * real-time speed control (0.5x - 2.0x) and real-time pitch shifting (-12 to +12 semitones).
 */
class MultiTrackPlayerManager(private val context: Context) {

    private val players = linkedMapOf<String, ExoPlayer>()
    private var currentSpeed: Float = 1.0f
    private var currentPitchSemitones: Int = 0
    private var isPlayingState: Boolean = false

    fun loadTracks(stems: List<StemTrack>, onPlaybackEnded: () -> Unit = {}) {
        release()
        stems.forEachIndexed { index, stem ->
            val file = File(stem.filePath)
            if (!file.exists()) return@forEachIndexed
            val exo = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playbackParameters = buildPlaybackParams()
                if (index == 0) {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                isPlayingState = false
                                onPlaybackEnded()
                            }
                        }
                    })
                }
            }
            players[stem.id] = exo
        }
        applyMixState(stems)
    }

    fun applyMixState(stems: List<StemTrack>) {
        val anySolo = stems.any { it.isSolo && !it.isRemoved }
        stems.forEach { stem ->
            val player = players[stem.id] ?: return@forEach
            val effectiveVolume = when {
                stem.isRemoved -> 0f
                stem.isMuted -> 0f
                anySolo && !stem.isSolo -> 0f
                else -> stem.volume.coerceIn(0f, 1.5f)
            }
            player.volume = effectiveVolume
        }
    }

    fun setSpeedAndPitch(speed: Float, pitchSemitones: Int) {
        currentSpeed = speed.coerceIn(0.5f, 2.0f)
        currentPitchSemitones = pitchSemitones.coerceIn(-12, 12)
        val params = buildPlaybackParams()
        players.values.forEach { it.playbackParameters = params }
    }

    private fun buildPlaybackParams(): PlaybackParameters {
        val pitchFactor = 2.0.pow(currentPitchSemitones / 12.0).toFloat()
        return PlaybackParameters(currentSpeed, pitchFactor)
    }

    fun play() {
        val anchor = players.values.firstOrNull() ?: return
        val pos = anchor.currentPosition
        players.values.forEach {
            if (it.playbackState == Player.STATE_ENDED) {
                it.seekTo(0)
            } else {
                it.seekTo(pos)
            }
            it.play()
        }
        isPlayingState = true
    }

    fun pause() {
        players.values.forEach { it.pause() }
        isPlayingState = false
    }

    fun stop() {
        players.values.forEach {
            it.pause()
            it.seekTo(0)
        }
        isPlayingState = false
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        players.values.forEach { it.seekTo(safePos) }
    }

    fun getCurrentPositionMs(): Long {
        return players.values.firstOrNull()?.currentPosition ?: 0L
    }

    fun getDurationMs(): Long {
        val dur = players.values.firstOrNull()?.duration ?: 0L
        return if (dur > 0) dur else 0L
    }

    fun isPlaying(): Boolean = isPlayingState

    fun release() {
        players.values.forEach {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        players.clear()
        isPlayingState = false
    }
}
