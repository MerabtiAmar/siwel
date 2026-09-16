package com.siwel.siwel.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import com.siwel.siwel.R

/**
 * Joue la sonnerie d'appel entrant EN BOUCLE, de façon FIABLE sur tous les téléphones (testé pour
 * corriger l'absence de son sur LG/Android 7) :
 *  1. On joue une sonnerie EMBARQUÉE dans l'app (res/raw) → indépendant de la sonnerie système du
 *     téléphone (sur certains LG, l'URI par défaut n'est pas lisible par MediaPlayer → silence).
 *  2. Si jamais ça échoue, repli sur la sonnerie système.
 *  3. On demande le focus audio ET on remonte le volume de sonnerie s'il est trop bas — UNIQUEMENT
 *     en mode sonnerie normal (on respecte le mode silencieux/vibreur). Volume restauré à l'arrêt.
 */
class RingtonePlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedRingVolume = -1

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start() {
        requestFocus()
        boostRingVolumeIfNormal()
        if (!playBundled()) playSystem()
    }

    fun stop() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop() }
            mediaPlayer?.release()
        } catch (e: Exception) {
            SLog.e("stop(): ${e.message}")
        } finally {
            mediaPlayer = null
            restoreRingVolume()
            abandonFocus()
        }
    }

    /** Sonnerie embarquée (déterministe, identique sur tous les téléphones). */
    private fun playBundled(): Boolean = try {
        val afd = context.resources.openRawResourceFd(R.raw.siwel_ringtone)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            isLooping = true
            setOnErrorListener { _, _, _ -> true }
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        SLog.e("playBundled(): ${e.message}")
        false
    }

    /** Repli : sonnerie système par défaut. */
    private fun playSystem(): Boolean = try {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(context, uri)
            isLooping = true
            setOnErrorListener { _, _, _ -> true }
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        SLog.e("playSystem(): ${e.message}")
        false
    }

    // ── Volume / focus ──────────────────────────────────────────────────────

    private fun boostRingVolumeIfNormal() {
        // On ne touche au volume QUE si le téléphone est en mode sonnerie (pas silencieux/vibreur).
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val cur = am.getStreamVolume(AudioManager.STREAM_RING)
            val target = (max * 0.85).toInt().coerceAtLeast(1)
            if (cur < target) {
                savedRingVolume = cur
                am.setStreamVolume(AudioManager.STREAM_RING, target, 0)
            }
        } catch (e: Exception) {
            SLog.e("boostVolume(): ${e.message}")
        }
    }

    private fun restoreRingVolume() {
        if (savedRingVolume < 0) return
        try { am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0) }
        catch (_: Exception) {}
        savedRingVolume = -1
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
