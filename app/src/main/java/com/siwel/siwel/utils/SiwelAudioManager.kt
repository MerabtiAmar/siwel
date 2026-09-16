// Usage VOLONTAIRE d'API audio dépréciées en Android 12 (isSpeakerphoneOn, start/stopBluetoothSco) :
// leur remplacement (setCommunicationDevice) n'existe qu'à partir d'Android 12, or on supporte
// Android 7 (minSdk 24). On garde donc ces API pour la compatibilité.
@file:Suppress("DEPRECATION")

package com.siwel.siwel.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

class SiwelAudioManager(private val context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var scoReceiver: BroadcastReceiver? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    var speakerOn = false
        private set

    // L'utilisateur a-t-il basculé le routage manuellement ? Si oui, on ne ré-applique plus
    // le haut-parleur par défaut (on respecte son choix).
    private var userOverrode = false

    // ── Démarrage ─────────────────────────────────────────────────────────

    fun start() {
        userOverrode = false
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        registerScoReceiver()
        applyDefaultRoute()

        // CRITIQUE : le moteur audio WebRTC s'initialise juste APRÈS et réinitialise souvent
        // le routage vers l'écouteur (surtout côté répondeur). On ré-applique le haut-parleur
        // par défaut plusieurs fois pour gagner la course, pour TOUS (appelant ET répondeurs).
        handler.postDelayed({ applyDefaultRoute() }, 800)
        handler.postDelayed({ applyDefaultRoute() }, 2000)
    }

    private fun applyDefaultRoute() {
        if (userOverrode) return
        if (isBluetoothAvailable()) {
            am.startBluetoothSco()
            SLog.d("BT SCO → tentative connexion")
        } else {
            am.isSpeakerphoneOn = true
            speakerOn = true
            SLog.d("Speaker activé par défaut")
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun registerScoReceiver() {
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                )
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        am.isBluetoothScoOn = true
                        SLog.d("BT SCO connecté ✓")
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        am.isBluetoothScoOn = false
                        SLog.d("BT SCO déconnecté")
                    }
                }
            }
        }
        context.registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
    }

    // ── Contrôles ─────────────────────────────────────────────────────────

    fun isBluetoothAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoAvailableOffCall
        }

    /** Retourne true si le haut-parleur est maintenant activé */
    fun toggleSpeaker(): Boolean {
        userOverrode = true   // choix manuel → ne plus ré-appliquer le défaut
        speakerOn = !speakerOn
        if (speakerOn) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn  = false
            am.isSpeakerphoneOn  = true
        } else {
            am.isSpeakerphoneOn = false
            if (isBluetoothAvailable()) am.startBluetoothSco()
        }
        SLog.d("Speaker → $speakerOn")
        return speakerOn
    }

    // ── Arrêt ─────────────────────────────────────────────────────────────

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        am.isBluetoothScoOn = false
        am.isSpeakerphoneOn = false
        am.mode             = AudioManager.MODE_NORMAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }

        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        scoReceiver = null
    }
}