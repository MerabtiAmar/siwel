package com.siwel.siwel.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.siwel.siwel.R
import com.siwel.siwel.call.CallActivity
import com.siwel.siwel.call.CallListenerService
import com.siwel.siwel.call.IncomingCallActivity

object NotificationHelper {

    const val CHANNEL_CALL     = "siwel_incoming_call"
    const val NOTIF_CALL_ID    = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CALL, "Appels entrants",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    // Son ET vibration gérés manuellement par le service (RingtonePlayer + Vibrator)
                    // → fiable sur toutes versions, y compris Android 7. On désactive ceux du canal
                    // pour éviter une double vibration / double son.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        initiator: String,
        members: List<String>,
        myName: String
    ) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_CALL_ID, buildIncomingCallNotification(context, callId, initiator, members, myName))
    }

    /**
     * Construit la notification d'appel entrant (plein écran + style appel). Renvoyée telle quelle
     * pour servir de notification de premier plan au service éphémère de sonnerie (startForeground).
     */
    fun buildIncomingCallNotification(
        context: Context,
        callId: String,
        initiator: String,
        members: List<String>,
        myName: String
    ): Notification {

        // Bouton ANSWER → CallActivity directement
        val directAnswerPi = PendingIntent.getActivity(
            context, 2,
            Intent(context, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID,      callId)
                putExtra(CallActivity.EXTRA_IS_INITIATOR, false)
                putExtra(CallActivity.EXTRA_MY_NAME,      myName)
                putStringArrayListExtra(CallActivity.EXTRA_MEMBERS, ArrayList(members))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Plein écran (écran verrouillé) → IncomingCallActivity
        val fullScreenPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_ID,   callId)
                putExtra(IncomingCallActivity.EXTRA_INITIATOR, initiator)
                putStringArrayListExtra(IncomingCallActivity.EXTRA_MEMBERS, ArrayList(members))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Bouton DECLINE → service
        val declinePi = PendingIntent.getService(
            context, 1,
            Intent(context, CallListenerService::class.java).apply {
                action = CallListenerService.ACTION_DECLINE
                putExtra(CallListenerService.EXTRA_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val caller = Person.Builder()
            .setName(initiator)
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, directAnswerPi))
            .setSmallIcon(R.drawable.ic_videocam)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPi, true)   // ← plein écran vers IncomingCallActivity

        return builder.build()
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL_ID)
    }
}