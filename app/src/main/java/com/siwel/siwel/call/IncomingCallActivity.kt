package com.siwel.siwel.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.siwel.siwel.SiwelApplication
import com.siwel.siwel.auth.SessionManager
import com.siwel.siwel.databinding.ActivityIncomingCallBinding
import com.siwel.siwel.firebase.SignalingRepository
import com.siwel.siwel.utils.NotificationHelper
import kotlinx.coroutines.launch

class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID   = "call_id"
        const val EXTRA_INITIATOR = "initiator"
        const val EXTRA_MEMBERS   = "members"
    }

    private lateinit var binding: ActivityIncomingCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allumer l'écran et afficher par-dessus le verrouillage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val callId    = intent.getStringExtra(EXTRA_CALL_ID)   ?: run { finish(); return }
        val initiator = intent.getStringExtra(EXTRA_INITIATOR) ?: "Quelqu'un"
        val members   = intent.getStringArrayListExtra(EXTRA_MEMBERS)?.toList() ?: emptyList()
        val myName    = SessionManager(this).getLoggedName()   ?: run { finish(); return }

        binding.tvCallerName.text = initiator
        binding.tvAvatar.text     = initiator.first().uppercaseChar().toString()

        binding.btnAnswer.setOnClickListener {
            stopRingAndNotif(callId)

            // Passer l'appel "active" immédiatement → coupe la tonalité de l'appelant au plus vite.
            // appScope (durée de vie processus) : l'écriture aboutit même si l'activité finit aussitôt.
            SiwelApplication.appScope.launch {
                try { SignalingRepository().setStatus(callId, "active") } catch (_: Exception) {}
            }

            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID,      callId)
                putExtra(CallActivity.EXTRA_IS_INITIATOR, false)
                putExtra(CallActivity.EXTRA_MY_NAME,      myName)
                putStringArrayListExtra(CallActivity.EXTRA_MEMBERS, ArrayList(members))
            })
            finish()
        }
    }

    private fun stopRingAndNotif(callId: String) {
        startService(Intent(this, CallListenerService::class.java).apply {
            action = CallListenerService.ACTION_STOP_RING
            putExtra(CallListenerService.EXTRA_CALL_ID, callId)
        })
        NotificationHelper.cancelIncomingCall(this)
    }
}