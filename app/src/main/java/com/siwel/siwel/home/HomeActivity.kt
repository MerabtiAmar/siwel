package com.siwel.siwel.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.siwel.siwel.R
import com.siwel.siwel.auth.LoginActivity
import com.siwel.siwel.auth.SessionManager
import com.siwel.siwel.call.CallActivity
import com.siwel.siwel.databinding.ActivityHomeBinding
import com.siwel.siwel.databinding.ItemMemberBinding
import com.siwel.siwel.firebase.MemberPresence
import kotlinx.coroutines.launch
import com.siwel.siwel.call.CallSession
import com.siwel.siwel.push.PushTokenManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.siwel.siwel.firebase.SignalingRepository

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var sessionManager: SessionManager
    private lateinit var currentUser: String

    private val signalingRepo = SignalingRepository()

    private val memberBindings = mutableMapOf<String, ItemMemberBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        currentUser = sessionManager.getLoggedName() ?: run { goToLogin(); return }

        binding.tvCurrentUser.text = currentUser
        setupMemberCards()
        setupCallButton()
        setupLogout()
        initPresence()
        observeJoinableCallBanner()
        cleanupStaleCalls()

        // Lever les restrictions batterie (une fois). La publication du jeton FCM, elle, se fait
        // dans onStart → à CHAQUE ouverture (auto-réparant si une 1ʳᵉ tentative a été tuée par MIUI).
        maybeRequestBatteryExemption()
    }

    private var tokenConfirmed = false
    private var tokenProblemShown = false

    override fun onStart() {
        super.onStart()
        // Rester joignable par push : republie le jeton à chaque passage au premier plan
        // (auto-réparant même après un kill MIUI au 1er lancement) ET affiche le résultat, pour
        // qu'un échec de réception d'appels soit VISIBLE (au lieu d'échouer en silence).
        registerPushToken()
    }

    private fun registerPushToken() {
        PushTokenManager.register(this) { ok, err ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    if (!tokenConfirmed) {
                        tokenConfirmed = true
                        Toast.makeText(this, "✅ Tu peux recevoir des appels", Toast.LENGTH_SHORT).show()
                    }
                } else if (!tokenProblemShown) {
                    tokenProblemShown = true
                    showTokenProblem(err)
                }
            }
        }
    }

    private fun showTokenProblem(reason: String?) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("⚠️ Réception d'appels inactive")
            .setMessage(
                "Ton téléphone n'a pas pu s'enregistrer pour recevoir les appels.\n\n" +
                "Raison : ${reason ?: "inconnue"}\n\n" +
                "Vérifie ta connexion internet, puis réessaie."
            )
            .setPositiveButton("Réessayer") { _, _ ->
                tokenProblemShown = false
                registerPushToken()
            }
            .setNegativeButton("Ignorer", null)
            .show()
    }

    /**
     * Demande UNE fois à être exempté de l'optimisation batterie. Sans ça, certains téléphones
     * (surtout anciens/agressifs) retardent ou bloquent la réception des push d'appel quand l'app
     * est fermée. Crucial pour la fiabilité sur les téléphones anciens.
     */
    private fun maybeRequestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("siwel_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("batt_prompt_shown", false)) return
        prefs.edit().putBoolean("batt_prompt_shown", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Recevoir les appels")
            .setMessage("Pour recevoir les appels même quand l'application est fermée, autorise Siwel à fonctionner sans restriction de batterie.")
            .setPositiveButton("Autoriser") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {}
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    /**
     * Bannière "Rejoindre" pilotée à 100% par l'état Firebase (source de vérité unique, fiable
     * cross-device) : il existe un appel joignable (ringing/active) dont JE me suis retiré
     * (quitté / refusé / pas répondu) ET je n'y participe pas actuellement (CallSession.inCallId).
     * Couvre M02, M05–M13 uniformément. repeatOnLifecycle(STARTED) réévalue à chaque retour sur Home.
     */
    private fun observeJoinableCallBanner() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                signalingRepo.observeJoinableCall(currentUser).collect { joinable ->
                    if (joinable != null && joinable.first != CallSession.inCallId) {
                        binding.bannerRejoin.visibility = View.VISIBLE
                        binding.btnRejoin.setOnClickListener {
                            binding.bannerRejoin.visibility = View.GONE
                            launchCallActivity(joinable.first, isInitiator = false, joinable.second)
                        }
                    } else {
                        binding.bannerRejoin.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** Balayage des appels résiduels (ended / tous partis) au démarrage de l'accueil. */
    private fun cleanupStaleCalls() {
        lifecycleScope.launch {
            try { signalingRepo.cleanupStaleCalls() } catch (_: Exception) {}
        }
    }

    private fun setupMemberCards() {
        sessionManager.getMembers().forEach { member ->
            val card = ItemMemberBinding.inflate(LayoutInflater.from(this), binding.containerMembers, true)
            card.tvMemberName.text = member.name
            card.tvAvatar.text = member.name.first().uppercaseChar().toString()
            memberBindings[member.name] = card
        }
    }

    private fun initPresence() {
        val allNames = sessionManager.getMembers().map { it.name }
        viewModel.init(currentUser, allNames)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.presences.collect { updateCards(it) }
            }
        }
    }

    private fun updateCards(presences: List<MemberPresence>) {
        presences.forEach { p ->
            val card = memberBindings[p.name] ?: return@forEach
            card.viewOnlineIndicator.setBackgroundResource(
                if (p.online) R.drawable.ic_status_online else R.drawable.ic_status_offline
            )
            card.tvStatus.text = if (p.online) "En ligne" else "Hors ligne"
            card.tvStatus.setTextColor(
                ContextCompat.getColor(this, if (p.online) R.color.accent_green else R.color.text_secondary)
            )
        }
        binding.btnCall.isEnabled = true
        binding.btnCall.alpha = 1f
    }

    private fun setupCallButton() {
        binding.btnCall.setOnClickListener {
            // Déjà dans un appel (app en arrière-plan) → ne pas en créer un second (M16).
            if (CallSession.inCallId != null) return@setOnClickListener

            binding.btnCall.isEnabled = false
            lifecycleScope.launch {
                val members  = sessionManager.getMembers().map { it.name }
                val existing = try { signalingRepo.getAnyJoinableCall() } catch (_: Exception) { null }
                if (existing != null) {
                    // Un appel est déjà en cours → le rejoindre au lieu d'en ouvrir un concurrent (M15).
                    launchCallActivity(existing.first, isInitiator = false, existing.second)
                } else {
                    launchCallActivity(System.currentTimeMillis().toString(), isInitiator = true, members)
                }
                binding.btnCall.isEnabled = true
            }
        }
    }


    private fun launchCallActivity(callId: String, isInitiator: Boolean, members: List<String>) {
        startActivity(Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID,      callId)
            putExtra(CallActivity.EXTRA_IS_INITIATOR, isInitiator)
            putExtra(CallActivity.EXTRA_MY_NAME,      currentUser)
            putStringArrayListExtra(CallActivity.EXTRA_MEMBERS, ArrayList(members))
        })
    }

    private fun setupLogout() {
        binding.tvLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Déconnexion")
                .setMessage("Se déconnecter de Siwel ?")
                .setPositiveButton("Oui") { _, _ ->
                    PushTokenManager.unregister(currentUser)   // ne plus recevoir de push
                    viewModel.logout()
                    sessionManager.logout()
                    goToLogin()
                }
                .setNegativeButton("Non", null)
                .show()
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}