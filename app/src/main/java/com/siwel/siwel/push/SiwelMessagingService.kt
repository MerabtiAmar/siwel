package com.siwel.siwel.push

import androidx.core.content.ContextCompat
import android.content.Intent
import com.siwel.siwel.utils.SLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.siwel.siwel.auth.SessionManager
import com.siwel.siwel.call.CallListenerService
import com.siwel.siwel.call.CallSession

/**
 * Reçoit les notifications push FCM. Remplace l'ancienne écoute Firebase 24h/24 : plus aucun
 * service permanent ni notification "En ligne". Quand un appel arrive, le démon serveur envoie un
 * message DATA haute priorité → Android réveille ce service (même app fermée) → on lance la phase
 * de sonnerie (service éphémère qui s'arrête tout seul).
 */
class SiwelMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Le jeton a changé → le republier pour rester joignable.
        PushTokenManager.save(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "incoming_call") return

        val callId    = data["callId"] ?: return
        val initiator = data["initiator"] ?: return
        val members   = data["members"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        val myName = SessionManager(this).getLoggedName() ?: return
        if (initiator == myName) return            // je suis l'appelant
        if (CallSession.inCallId != null) return   // déjà en appel

        SLog.d("Push appel entrant: $callId de $initiator")

        // Lancer la phase de sonnerie (service éphémère). Autorisé en arrière-plan car déclenché
        // par un message FCM haute priorité.
        val svc = Intent(this, CallListenerService::class.java).apply {
            action = CallListenerService.ACTION_INCOMING
            putExtra(CallListenerService.EXTRA_CALL_ID, callId)
            putExtra(CallListenerService.EXTRA_INITIATOR, initiator)
            putStringArrayListExtra(CallListenerService.EXTRA_MEMBERS, ArrayList(members))
        }
        ContextCompat.startForegroundService(this, svc)
    }
}
