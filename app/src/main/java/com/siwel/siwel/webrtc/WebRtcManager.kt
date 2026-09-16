package com.siwel.siwel.webrtc

import android.content.Context
import com.siwel.siwel.BuildConfig
import com.siwel.siwel.utils.SLog
import org.webrtc.*

class WebRtcManager(private val context: Context) {

    companion object {
        private const val VIDEO_TRACK_ID  = "siwel_video"
        private const val AUDIO_TRACK_ID  = "siwel_audio"
        private const val LOCAL_STREAM_ID = "siwel_stream"
        const val VIDEO_WIDTH  = 640
        const val VIDEO_HEIGHT = 480
        const val VIDEO_FPS    = 30

        // Serveur TURN (coturn auto-hébergé) : indispensable quand les pairs sont sur des réseaux
        // différents (NAT symétrique, CGNAT). Sans relais, aucun candidat "relay" n'est généré et
        // l'appel reste sans image ni son. Les valeurs sont lues dans local.properties
        // (siwel.turn.host / siwel.turn.user / siwel.turn.pass) et injectées via BuildConfig.
        private val TURN_HOST = BuildConfig.TURN_HOST
        private val TURN_USER = BuildConfig.TURN_USER
        private val TURN_PASS = BuildConfig.TURN_PASS
    }

    // EglBase partagé : rendu vidéo OpenGL ES
    val eglBase: EglBase = EglBase.create()

    private var initialized = false

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null

    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set

    // ICE servers : STUN (découverte d'adresse publique) + TURN (relais multi-réseaux).
    // Notre coturn écoute sur 3478 en clair (UDP + TCP). Le transport TCP sert de repli
    // pour les réseaux qui filtrent l'UDP. Pas de TLS (turns:) : le serveur n'a qu'une IP,
    // pas de domaine → pas de certificat valide ; inutile pour de la data mobile classique.
    private val iceServers = listOf(
        PeerConnection.IceServer.builder(
            listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
        ).createIceServer(),
        PeerConnection.IceServer.builder(
            listOf(
                "turn:$TURN_HOST:3478?transport=udp",
                "turn:$TURN_HOST:3478?transport=tcp"
            )
        ).setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer()
    )

    // Mesh : une PeerConnection par membre distant
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    // ─────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────

    fun init() {
        initFactory()
        createLocalTracks()
        initialized = true   // ← ajouter
    }

    private fun initFactory() {

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()
    }

    private fun createLocalTracks() {
        // Audio : meilleure qualité + traitements
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation",  "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression",  "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl",   "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter",    "true"))
        }
        localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack  = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack?.setEnabled(true)

        // Vidéo : caméra frontale, 640×480 @ 30fps
        val capturer = buildCameraCapturer(frontCamera = true) ?: return
        videoCapturer = capturer

        localVideoSource = peerConnectionFactory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBase.eglBaseContext
        )
        capturer.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
        localVideoTrack?.setEnabled(true)
    }

    private fun buildCameraCapturer(frontCamera: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        if (enumerator.deviceNames.isEmpty()) return null
        return enumerator.deviceNames
            .firstOrNull { name ->
                if (frontCamera) enumerator.isFrontFacing(name)
                else             enumerator.isBackFacing(name)
            }
            ?.let { enumerator.createCapturer(it, cameraEventsHandler) }
            ?: enumerator.createCapturer(enumerator.deviceNames.first(), cameraEventsHandler)
    }

    // Si la caméra échoue à s'ouvrir (souvent : encore détenue par l'appel précédent qui se
    // libère en tâche de fond), on retente UNE fois après un court délai. Rend le 2ᵉ appel fiable.
    private var captureRetried = false
    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            SLog.e("Camera error: $errorDescription")
            retryStartCaptureOnce()
        }
        override fun onCameraDisconnected() {}
        override fun onCameraFreezed(errorDescription: String?) {}
        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() { captureRetried = false }
        override fun onCameraClosed() {}
    }

    private fun retryStartCaptureOnce() {
        if (captureRetried) return
        captureRetried = true
        val capturer = videoCapturer ?: return
        Thread {
            try { Thread.sleep(600) } catch (_: InterruptedException) { return@Thread }
            try { capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS) }
            catch (e: Exception) { SLog.e("retry startCapture: ${e.message}") }
        }.start()
    }

    // ─────────────────────────────────────────
    // Renderers
    // ─────────────────────────────────────────

    fun initRenderer(renderer: SurfaceViewRenderer, mirror: Boolean = false) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(mirror)
        renderer.setEnableHardwareScaler(true)
    }

    fun attachLocalVideo(renderer: SurfaceViewRenderer) {
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalVideo(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
    }

    fun attachRemoteVideo(renderer: SurfaceViewRenderer, track: VideoTrack) {
        track.addSink(renderer)
    }

    fun detachRemoteVideo(renderer: SurfaceViewRenderer, track: VideoTrack) {
        track.removeSink(renderer)
    }

    // ─────────────────────────────────────────
    // Contrôles pendant l'appel
    // ─────────────────────────────────────────

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    // ─────────────────────────────────────────
    // PeerConnections — utilisé à l'étape 5
    // ─────────────────────────────────────────

    fun createPeerConnection(
        remoteName: String,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics              = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy  = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Pré-collecte des candidats (dont relay TURN) → connexion plus rapide et fiable.
            iceCandidatePoolSize      = 1
            bundlePolicy              = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy             = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Autoriser les candidats TURN/TCP (réseaux qui bloquent l'UDP).
            tcpCandidatePolicy        = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val pc = peerConnectionFactory.createPeerConnection(config, observer) ?: return null

        // Ajout des tracks locaux à cette connexion
        localAudioTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }

        peerConnections[remoteName] = pc
        return pc
    }

    fun getPeerConnection(name: String): PeerConnection? = peerConnections[name]

    fun closePeerConnection(name: String) {
        peerConnections.remove(name)?.close()
    }

    fun closeAllPeerConnections() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
    }

    // ─────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────

    fun release() {
        closeAllPeerConnections()
        if (!initialized) {
            eglBase.release()
            return
        }
        initialized = false

        // Capturer les références localement avant de les nullifier
        val capturer    = videoCapturer
        val helper      = surfaceTextureHelper
        val videoSource = localVideoSource
        val audioSource = localAudioSource
        val videoTrack  = localVideoTrack
        val audioTrack  = localAudioTrack
        val factory     = peerConnectionFactory

        videoCapturer        = null
        surfaceTextureHelper = null
        localVideoSource     = null
        localAudioSource     = null
        localVideoTrack      = null
        localAudioTrack      = null

        // Tout le teardown natif se fait sur UN SEUL thread dédié, dans le bon ordre.
        // CRITIQUE : eglBase.release() doit venir EN DERNIER, après factory.dispose().
        // L'ancien code libérait eglBase sur le thread principal pendant que la factory
        // (qui dépend du contexte EGL) était encore en cours de dispose() → corruption GPU
        // qui cassait l'appel suivant (2ᵉ appel : pas de vidéo/son). stopCapture() est
        // bloquant → ce thread évite aussi l'ANR.
        Thread {
            try { capturer?.stopCapture() } catch (_: InterruptedException) {}
            capturer?.dispose()
            helper?.dispose()
            videoTrack?.dispose()
            audioTrack?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            factory.dispose()
            eglBase.release()
        }.start()
    }
}