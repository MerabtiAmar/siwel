package com.siwel.siwel.call

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.siwel.siwel.R
import com.siwel.siwel.databinding.ActivityCallBinding
import com.siwel.siwel.utils.NotificationHelper
import com.siwel.siwel.utils.PermissionHelper
import com.siwel.siwel.utils.RingbackPlayer
import com.siwel.siwel.utils.SiwelAudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID      = "call_id"
        const val EXTRA_IS_INITIATOR = "is_initiator"
        const val EXTRA_MY_NAME      = "my_name"
        const val EXTRA_MEMBERS      = "members"
    }

    private lateinit var binding: ActivityCallBinding
    private val viewModel: CallViewModel by viewModels()
    private lateinit var audioManager: SiwelAudioManager
    private val ringbackPlayer = RingbackPlayer()

    private var isInitiatorParam = true
    private var callIdParam      = ""
    private var myName           = ""
    private var allMembersParam  = emptyList<String>()
    private lateinit var remoteNames: List<String>

    private var micEnabled    = true
    private var cameraEnabled = true
    private var callStarted   = false
    private var durationStarted = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) initCall()
        else {
            Toast.makeText(this, "Permissions caméra et micro requises", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Garder l'écran allumé pendant l'appel
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isInitiatorParam = intent.getBooleanExtra(EXTRA_IS_INITIATOR, true)
        callIdParam      = intent.getStringExtra(EXTRA_CALL_ID) ?: System.currentTimeMillis().toString()
        myName           = intent.getStringExtra(EXTRA_MY_NAME) ?: ""
        allMembersParam  = intent.getStringArrayListExtra(EXTRA_MEMBERS)?.toList() ?: emptyList()
        remoteNames      = allMembersParam.filter { it != myName }

        // Marquer qu'on est DANS cet appel → empêche la bannière "Rejoindre" de s'afficher
        // pour l'appel auquel on participe déjà (cf. HomeActivity + CallSession).
        CallSession.inCallId = callIdParam

        audioManager = SiwelAudioManager(applicationContext)

        setupNameLabels()
        setupControls()
        setupDraggablePreview()
        observeCallState()
        observeRemoteTracks()
        observeLeftMembers()
        observePeerAnswered()
        observeSpeaking()

        if (PermissionHelper.hasCallPermissions(this)) initCall()
        else permLauncher.launch(PermissionHelper.CALL_PERMISSIONS)
    }

    /**
     * Le bouton Retour ne raccroche PAS l'appel : il met l'app en arrière-plan en GARDANT l'appel
     * actif. Avant ce correctif, Back faisait finish() → endCall() → on quittait l'appel sans le
     * vouloir (l'autre perdait notre audio/vidéo). Pour raccrocher : le bouton rouge.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /**
     * Libère le marqueur "je suis dans cet appel" dès que je le quitte (raccrochage / fin d'appel),
     * pas seulement dans onDestroy : sinon HomeActivity.onResume (qui s'exécute AVANT onDestroy)
     * réévalue la bannière alors que inCallId est encore positionné → bannière manquante quand on
     * quitte. Gardé par callId pour ne pas écraser un éventuel nouvel appel.
     */
    private fun clearCallSession() {
        if (CallSession.inCallId == callIdParam) CallSession.inCallId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        clearCallSession()
        ringbackPlayer.stop()
        if (callStarted) {
            // Détacher TOUS les sinks (local + distants) avant de release les renderers, pour
            // qu'aucune frame n'arrive sur un renderer libéré (crash) — cycle de vie propre.
            viewModel.webRtcManager.detachLocalVideo(binding.localRenderer)
            val tracks = viewModel.remoteTracks.value
            remoteNames.getOrNull(0)?.let { tracks[it]?.removeSink(binding.remoteRenderer1) }
            remoteNames.getOrNull(1)?.let { tracks[it]?.removeSink(binding.remoteRenderer2) }
            binding.localRenderer.release()
            binding.remoteRenderer1.release()
            binding.remoteRenderer2.release()
        }
        if (::audioManager.isInitialized) audioManager.stop()
        viewModel.release()
    }

    // ── Init ──────────────────────────────────────────────────────────────

    private fun initCall() {
        callStarted = true
        audioManager.start()

        if (!isInitiatorParam) {
            startService(Intent(this, CallListenerService::class.java).apply {
                action = CallListenerService.ACTION_STOP_RING
                putExtra(CallListenerService.EXTRA_CALL_ID, callIdParam)
            })
            NotificationHelper.cancelIncomingCall(this)
            viewModel.setCallActive(callIdParam)
        } else {
            ringbackPlayer.start()
        }

        viewModel.initWebRtc()

        // L'aperçu local doit s'afficher AU-DESSUS des vidéos distantes (deux SurfaceView qui se
        // superposent) → media overlay. À appeler avant init() qui crée la surface.
        binding.localRenderer.setZOrderMediaOverlay(true)

        viewModel.webRtcManager.initRenderer(binding.localRenderer,   mirror = true)
        viewModel.webRtcManager.initRenderer(binding.remoteRenderer1, mirror = false)
        viewModel.webRtcManager.initRenderer(binding.remoteRenderer2, mirror = false)
        viewModel.webRtcManager.attachLocalVideo(binding.localRenderer)

        if (isInitiatorParam) viewModel.startCall(callIdParam, myName, allMembersParam)
        else                  viewModel.joinCall(callIdParam, myName, allMembersParam)
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun setupNameLabels() {
        binding.tvRemoteName1.text = remoteNames.getOrNull(0) ?: ""
        binding.tvRemoteName2.text = remoteNames.getOrNull(1) ?: ""
        // Masquer la 2ᵉ tuile s'il n'y a qu'un seul correspondant (appel à 2).
        if (remoteNames.size < 2) binding.containerRemote2.visibility = View.GONE
    }

    private fun setupControls() {

        binding.btnHangup.setOnClickListener {
            clearCallSession()
            viewModel.endCall()
            finish()
        }

        binding.btnMic.setOnClickListener {
            micEnabled = !micEnabled
            viewModel.webRtcManager.setMicEnabled(micEnabled)
            binding.btnMic.setImageResource(
                if (micEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
            )
            setButtonActive(binding.btnMic, micEnabled)
            // Badge "micro coupé" sur l'aperçu local
            binding.ivLocalMute.visibility = if (micEnabled) View.GONE else View.VISIBLE
        }

        binding.btnCamera.setOnClickListener {
            cameraEnabled = !cameraEnabled
            viewModel.webRtcManager.setCameraEnabled(cameraEnabled)
            binding.btnCamera.setImageResource(
                if (cameraEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off
            )
            setButtonActive(binding.btnCamera, cameraEnabled)
        }

        binding.btnFlipCamera.setOnClickListener {
            viewModel.webRtcManager.switchCamera()
        }

        binding.btnSpeaker.setOnClickListener {
            val isOn = audioManager.toggleSpeaker()
            binding.btnSpeaker.background = ContextCompat.getDrawable(
                this,
                if (isOn) R.drawable.bg_btn_highlight else R.drawable.bg_btn_normal
            )
        }
    }

    /**
     * Aperçu local déplaçable : on glisse la tuile, elle s'aimante au coin gauche/droit le plus
     * proche au relâchement (et reste dans les bornes verticales de l'écran).
     */
    private fun setupDraggablePreview() {
        val v = binding.localPreviewContainer
        var dX = 0f
        var dY = 0f
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - e.rawX
                    dY = view.y - e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.x = e.rawX + dX
                    view.y = e.rawY + dY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    snapToCorner(view)
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToCorner(view: View) {
        val parent = view.parent as View
        val margin = 14f * resources.displayMetrics.density
        val maxX = parent.width - view.width - margin
        val maxY = parent.height - view.height - margin
        if (maxX <= margin || maxY <= margin) return
        val targetX = if (view.x + view.width / 2f < parent.width / 2f) margin else maxX
        val targetY = view.y.coerceIn(margin, maxY)
        view.animate().x(targetX).y(targetY).setDuration(180).start()
    }

    /** active=true → bouton gris normal | active=false → bouton rouge */
    private fun setButtonActive(button: ImageButton, active: Boolean) {
        button.background = ContextCompat.getDrawable(
            this,
            if (active) R.drawable.bg_btn_normal else R.drawable.bg_btn_danger
        )
    }

    // ── Chrono ────────────────────────────────────────────────────────────

    private fun startDurationTimer() {
        if (durationStarted) return
        durationStarted = true
        val startMs = SystemClock.elapsedRealtime()
        binding.tvDuration.visibility = View.VISIBLE
        lifecycleScope.launch {
            while (isActive) {
                val s = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt()
                binding.tvDuration.text = String.format("%02d:%02d", s / 60, s % 60)
                delay(1000)
            }
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────

    private fun observeCallState() {
        lifecycleScope.launch {
            viewModel.callState.collect {
                if (it == CallState.Ended) { clearCallSession(); finish() }
            }
        }
    }

    /** Couper la tonalité de retour dès que quelqu'un décroche (statut "active"). */
    private fun observePeerAnswered() {
        lifecycleScope.launch {
            viewModel.peerAnswered.collect { answered ->
                if (answered) { ringbackPlayer.stop(); startDurationTimer() }
            }
        }
    }

    /** Halos "qui parle" : distant (anneau autour de la tuile) + local (bord de l'aperçu). */
    private fun observeSpeaking() {
        lifecycleScope.launch {
            viewModel.speakingPeers.collect { speakers ->
                setRingSpeaking(binding.ringSpeaking1, remoteNames.getOrNull(0)?.let { speakers.contains(it) } == true)
                setRingSpeaking(binding.ringSpeaking2, remoteNames.getOrNull(1)?.let { speakers.contains(it) } == true)
            }
        }
        lifecycleScope.launch {
            viewModel.localSpeaking.collect { speaking ->
                binding.localPreviewContainer.setBackgroundResource(
                    if (speaking) R.drawable.bg_local_preview_speaking else R.drawable.bg_local_preview
                )
            }
        }
    }

    private fun setRingSpeaking(ring: View, speaking: Boolean) {
        val want = if (speaking) R.drawable.ring_speaking else R.drawable.ring_idle
        if (ring.tag == want) return
        ring.tag = want
        ring.setBackgroundResource(want)
    }

    private fun observeRemoteTracks() {
        lifecycleScope.launch {
            viewModel.remoteTracks.collect { tracks ->

                // Arrêter la tonalité + démarrer le chrono dès qu'un pair est connecté
                if (tracks.values.any { it != null }) {
                    ringbackPlayer.stop()
                    startDurationTimer()
                }

                bindRemote(
                    track        = remoteNames.getOrNull(0)?.let { tracks[it] },
                    renderer     = binding.remoteRenderer1,
                    waitingGroup = binding.waitingGroup1
                )
                bindRemote(
                    track        = remoteNames.getOrNull(1)?.let { tracks[it] },
                    renderer     = binding.remoteRenderer2,
                    waitingGroup = binding.waitingGroup2
                )
            }
        }
    }

    private fun observeLeftMembers() {
        lifecycleScope.launch {
            viewModel.leftMembers.collect { left ->
                remoteNames.forEachIndexed { index, name ->
                    val renderer     = if (index == 0) binding.remoteRenderer1 else binding.remoteRenderer2
                    val waitingGroup = if (index == 0) binding.waitingGroup1   else binding.waitingGroup2
                    val tvLeft       = if (index == 0) binding.tvLeft1         else binding.tvLeft2

                    if (left.contains(name)) {
                        // Parti → détacher vidéo + afficher "A quitté" en fondu
                        viewModel.remoteTracks.value[name]?.removeSink(renderer)
                        fadeOut(waitingGroup)
                        fadeIn(tvLeft)
                    } else {
                        // Revenu → cacher "A quitté", la vidéo se rebranche via observeRemoteTracks
                        fadeOut(tvLeft)
                    }
                }
            }
        }
    }

    private fun bindRemote(
        track: VideoTrack?,
        renderer: SurfaceViewRenderer,
        waitingGroup: View
    ) {
        if (track != null) {
            track.addSink(renderer)
            fadeOut(waitingGroup)
        } else {
            fadeIn(waitingGroup)
        }
    }

    // ── Petites transitions en fondu ────────────────────────────────────────

    private fun fadeIn(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(220).start()
    }

    private fun fadeOut(view: View) {
        if (view.visibility == View.GONE) return
        view.animate().alpha(0f).setDuration(180).withEndAction {
            view.visibility = View.GONE
        }.start()
    }
}
