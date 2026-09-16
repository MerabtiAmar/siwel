package com.siwel.siwel.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.siwel.siwel.utils.SLog
import com.siwel.siwel.auth.SessionManager
import com.siwel.siwel.firebase.SignalingRepository
import com.siwel.siwel.utils.NotificationHelper
import com.siwel.siwel.utils.RingtonePlayer
import kotlinx.coroutines.*

/**
 * Service ÉPHÉMÈRE de phase de sonnerie. Démarré uniquement quand une push FCM signale un appel
 * entrant (cf. [com.siwel.siwel.push.SiwelMessagingService]) — il NE tourne PLUS en permanence.
 * Il affiche la notification d'appel (plein écran), sonne, gère le timeout 45 s / le refus, puis
 * s'arrête tout seul. Plus aucune notification "En ligne" ni drain batterie en veille.
 */
class CallListenerService : Service() {

    companion object {
        const val ACTION_INCOMING  = "com.siwel.siwel.INCOMING"
        const val ACTION_STOP_RING = "com.siwel.siwel.STOP_RING"
        const val ACTION_DECLINE   = "com.siwel.siwel.DECLINE"
        const val EXTRA_CALL_ID    = "call_id"
        const val EXTRA_INITIATOR  = "initiator"
        const val EXTRA_MEMBERS    = "members"
    }

    private val signalingRepo = SignalingRepository()
    private lateinit var ringtonePlayer: RingtonePlayer
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusJob: Job?  = null
    private var timeoutJob: Job? = null
    private var currentCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        ringtonePlayer = RingtonePlayer(this)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Vibration en boucle pendant la sonnerie — indispensable sur Android 7 (pas de canaux) et
     *  en mode vibreur. S'arrête avec [stopAlert]. */
    private fun startVibration() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 1000)   // vibre 800ms, pause 1000ms, recommence
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))   // repeat à l'index 0
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (e: Exception) { SLog.e("vibrate: ${e.message}") }
    }

    private fun stopAlert() {
        serviceScope.launch(Dispatchers.Main) { ringtonePlayer.stop() }
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING  -> handleIncoming(intent)
            ACTION_STOP_RING -> stopRingingAndSelf(cleanup = false, callId = null)
            ACTION_DECLINE   -> handleDecline(intent)
            else             -> stopSelf()   // jamais d'auto-redémarrage à vide
        }
        // NOT_STICKY : si le système nous tue, on ne redémarre pas avec un intent nul (rien à sonner).
        return START_NOT_STICKY
    }

    // ── Appel entrant ─────────────────────────────────────────────────────

    private fun handleIncoming(intent: Intent) {
        val callId    = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopSelf()
        val initiator = intent.getStringExtra(EXTRA_INITIATOR) ?: "Quelqu'un"
        val members   = intent.getStringArrayListExtra(EXTRA_MEMBERS)?.toList() ?: emptyList()
        val myName    = SessionManager(this).getLoggedName() ?: return stopSelf()

        if (callId == currentCallId) return     // déjà en train de sonner pour cet appel
        currentCallId = callId

        // Notification plein écran = notification de premier plan du service (obligatoire < 5 s).
        startForeground(
            NotificationHelper.NOTIF_CALL_ID,
            NotificationHelper.buildIncomingCallNotification(this, callId, initiator, members, myName)
        )

        serviceScope.launch(Dispatchers.Main) { ringtonePlayer.start() }
        startVibration()
        watchCall(callId, myName)
    }

    /**
     * Surveille l'appel pendant la sonnerie :
     *  - statut "ended"  → l'appel est annulé/terminé → on arrête de sonner.
     *  - statut "active" → quelqu'un d'autre a décroché : JE continue de sonner (je peux encore
     *    rejoindre) jusqu'à MES 45 s. À l'échéance : si toujours "ringing" → je termine l'appel ;
     *    si "active" → je me retire (`left`) pour voir la bannière "Rejoindre".
     */
    private fun watchCall(callId: String, myName: String) {
        statusJob?.cancel()
        timeoutJob?.cancel()

        statusJob = serviceScope.launch {
            signalingRepo.observeStatus(callId).collect { status ->
                if (status == "ended") stopRingingAndSelf(cleanup = true, callId = callId)
            }
        }

        timeoutJob = serviceScope.launch {
            delay(45_000)
            val status = try { signalingRepo.getCallStatus(callId) } catch (_: Exception) { null }
            when (status) {
                "ringing" -> {
                    try { signalingRepo.setStatus(callId, "ended") } catch (_: Exception) {}
                    stopRingingAndSelf(cleanup = true, callId = callId)
                }
                "active" -> {
                    try { signalingRepo.recordLeft(callId, myName) } catch (_: Exception) {}
                    stopRingingAndSelf(cleanup = false, callId = callId)
                }
                else -> stopRingingAndSelf(cleanup = false, callId = callId)
            }
        }
    }

    // ── Refus ─────────────────────────────────────────────────────────────

    private fun handleDecline(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val myName = SessionManager(this).getLoggedName()
        if (callId == null || myName == null) { stopRingingAndSelf(false, null); return }

        serviceScope.launch {
            try {
                signalingRepo.recordDecline(callId, myName)
                // Aussi "left" : le décliné est traité comme absent du mesh. S'il rejoint ensuite
                // (bannière), la reconnexion passe par le chemin quitter→rejoindre fiable.
                signalingRepo.recordLeft(callId, myName)

                val info = signalingRepo.getCallInfo(callId)
                if (info != null) {
                    val (initiator, members) = info
                    val nonInitiators = members.filter { it != initiator }
                    val declinedCount = signalingRepo.getDeclinedCount(callId)
                    val status        = signalingRepo.getCallStatus(callId)
                    // Tous les destinataires ont refusé ET personne n'a décroché → terminer.
                    if (status != "active" && declinedCount >= nonInitiators.size) {
                        signalingRepo.setStatus(callId, "ended")
                        signalingRepo.cleanup(callId)
                    }
                }
            } catch (e: Exception) {
                SLog.e("Decline: ${e.message}")
            }
        }
        stopRingingAndSelf(cleanup = false, callId = callId)
    }

    // ── Arrêt ─────────────────────────────────────────────────────────────

    private fun stopRingingAndSelf(cleanup: Boolean, callId: String?) {
        statusJob?.cancel()
        timeoutJob?.cancel()
        stopAlert()
        NotificationHelper.cancelIncomingCall(this)
        if (cleanup && callId != null) {
            serviceScope.launch { try { signalingRepo.cleanup(callId) } catch (_: Exception) {} }
        }
        currentCallId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ringtonePlayer.stop()
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
