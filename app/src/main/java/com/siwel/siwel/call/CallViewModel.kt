package com.siwel.siwel.call

import android.app.Application
import com.siwel.siwel.utils.SLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.siwel.siwel.SiwelApplication
import com.siwel.siwel.firebase.SignalingRepository
import com.siwel.siwel.webrtc.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*

class CallViewModel(app: Application) : AndroidViewModel(app) {

    val webRtcManager = WebRtcManager(app)
    private val signaling = SignalingRepository()

    private val _callState    = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    // nom → VideoTrack (null = pas encore connecté)
    private val _remoteTracks = MutableStateFlow<Map<String, VideoTrack?>>(emptyMap())
    val remoteTracks: StateFlow<Map<String, VideoTrack?>> = _remoteTracks.asStateFlow()

    // Passe à true dès que l'appel devient "active" (quelqu'un a décroché).
    // Côté appelant : signal fiable pour couper la tonalité de retour (ringback).
    private val _peerAnswered = MutableStateFlow(false)
    val peerAnswered: StateFlow<Boolean> = _peerAnswered.asStateFlow()

    // Détection "qui parle" (niveau audio temps réel via getStats).
    private val _speakingPeers = MutableStateFlow<Set<String>>(emptySet())
    val speakingPeers: StateFlow<Set<String>> = _speakingPeers.asStateFlow()
    private val _localSpeaking = MutableStateFlow(false)
    val localSpeaking: StateFlow<Boolean> = _localSpeaking.asStateFlow()
    private var audioLevelJob: Job? = null
    private val speakingLock = Any()

    private var callId   = ""
    private var myName   = ""
    private var members  = emptyList<String>()
    private var released = false

    // Jobs coroutine par pair (observers SDP/ICE) → permet de détruire UNE connexion
    // proprement quand un pair quitte, et de la recréer quand il revient (BUG 1).
    private val peerJobs = mutableMapOf<String, MutableList<Job>>()

    // ── Init WebRTC ───────────────────────────────────────────────────────

    fun initWebRtc() = webRtcManager.init()

    // ── Démarrer un appel (initiateur) ────────────────────────────────────

    private var callerTimeoutJob: Job? = null
    fun startCall(callId: String, myName: String, members: List<String>) {
        this.callId  = callId
        this.myName  = myName
        this.members = members
        viewModelScope.launch {
            signaling.createCall(callId, myName, members)   // pose `present={initiateur}` atomiquement
            _callState.value = CallState.Active
            setupMesh(callId)
            observeStatus(callId)
            observeWhoLeft(callId)
            startCallerTimeout(callId)   // ← ajouter
            startAudioLevelPolling()
        }
    }

    private fun startCallerTimeout(callId: String) {
        callerTimeoutJob = viewModelScope.launch {
            delay(45_000)
            // Personne n'a décroché après 45s → terminer DÉFINITIVEMENT l'appel.
            // (Ne pas passer par endCall() qui proposerait à tort de "rejoindre" un
            //  appel que personne n'a pris, en le laissant "ringing" dans Firebase — BUG 3.)
            if (_remoteTracks.value.values.all { it == null }
                && _callState.value != CallState.Ended) {
                _callState.value = CallState.Ended
                SiwelApplication.appScope.launch {
                    try { terminateCall(callId) }
                    catch (e: Exception) { SLog.e("timeout end: ${e.message}") }
                }
            }
        }
    }

    // ── Rejoindre un appel (non-initiateur) ───────────────────────────────

    fun joinCall(callId: String, myName: String, members: List<String>) {
        this.callId  = callId
        this.myName  = myName
        this.members = members
        viewModelScope.launch {
            // Ne PAS purger les nœuds ici : on effacerait l'offre encore valide d'un autre pair
            // qui ne la renverrait pas (deadlock). C'est l'OFFERER qui purge SA propre paire dans
            // connectPeer, juste avant d'envoyer une offre fraîche (propriétaire déterministe).
            try { signaling.removeLeft(callId, myName) } catch (_: Exception) {}
            try { signaling.removeDeclined(callId, myName) } catch (_: Exception) {}
            try { signaling.addPresent(callId, myName) } catch (_: Exception) {}
            _callState.value = CallState.Active
            setupMesh(callId)
            observeStatus(callId)
            observeWhoLeft(callId)
            startAudioLevelPolling()
        }
    }

    // ── Détection "qui parle" ─────────────────────────────────────────────

    /**
     * Sonde périodiquement le niveau audio de chaque connexion (getStats) pour savoir qui parle :
     *  - `inbound-rtp` audio  → niveau du pair distant
     *  - `media-source` audio → niveau de mon propre micro (présent dans n'importe quel rapport).
     * Purement additif : si les stats ne fournissent pas de niveau, aucun halo ne s'affiche.
     */
    private fun startAudioLevelPolling() {
        if (audioLevelJob != null) return
        audioLevelJob = viewModelScope.launch {
            while (isActive) {
                delay(220)
                val remotes = members.filter { it != myName }
                remotes.forEach { name ->
                    val pc = webRtcManager.getPeerConnection(name) ?: return@forEach
                    pc.getStats { report ->
                        val remoteLevel = audioLevelOf(report, "inbound-rtp")
                        val localLevel  = audioLevelOf(report, "media-source")
                        synchronized(speakingLock) {
                            val speaking = remoteLevel > SPEAK_THRESHOLD
                            _speakingPeers.value =
                                if (speaking) _speakingPeers.value + name
                                else          _speakingPeers.value - name
                        }
                        if (localLevel > SPEAK_THRESHOLD != _localSpeaking.value) {
                            _localSpeaking.value = localLevel > SPEAK_THRESHOLD
                        }
                    }
                }
            }
        }
    }

    private fun audioLevelOf(report: RTCStatsReport, type: String): Double {
        var max = 0.0
        for (s in report.statsMap.values) {
            if (s.type != type) continue
            val kind = (s.members["kind"] ?: s.members["mediaType"]) as? String
            if (kind != "audio") continue
            (s.members["audioLevel"] as? Double)?.let { if (it > max) max = it }
        }
        return max
    }

    // ── Topologie mesh ────────────────────────────────────────────────────

    private fun setupMesh(callId: String) {
        val remotes = members.filter { it != myName }
        _remoteTracks.value = remotes.associateWith { null }
        remotes.forEach { connectPeer(callId, it) }
    }

    /**
     * Établit (ou ré-établit) la PeerConnection vers un pair. Idempotent : toute connexion
     * existante vers ce pair est d'abord détruite, ce qui permet une reconstruction propre
     * lors d'un rejoin sans fuite de PeerConnection ni d'observers (BUG 1).
     */
    private fun connectPeer(callId: String, remoteName: String) {
        teardownPeer(remoteName)

        val jobs = mutableListOf<Job>()
        peerJobs[remoteName] = jobs

        val key       = signaling.connKey(myName, remoteName)
        val amOfferer = signaling.isOfferer(myName, remoteName)

        // File d'attente ICE : évite d'ajouter les candidats avant setRemoteDescription
        val iceQueue  = mutableListOf<IceCandidate>()
        var remoteSet = false

        val pc = webRtcManager.createPeerConnection(
            remoteName = remoteName,
            observer   = object : PeerConnectionObserver() {

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    signaling.sendIce(callId, key, myName,
                        candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex)
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track() as? VideoTrack ?: return
                    callerTimeoutJob?.cancel()
                    // StateFlow.value est thread-safe ; le collector UI fait le handoff vers le main.
                    _remoteTracks.value = _remoteTracks.value + (remoteName to track)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    SLog.d("[$remoteName] ICE → $state")
                }
            }
        ) ?: run { peerJobs.remove(remoteName); return }

        // Observer les candidats ICE distants
        jobs += viewModelScope.launch {
            signaling.observeIce(callId, key, remoteName).collect { (sdp, mid, idx) ->
                val candidate = IceCandidate(mid, idx, sdp)
                if (remoteSet) pc.addIceCandidate(candidate)
                else           iceQueue.add(candidate)
            }
        }

        // Échange SDP
        if (amOfferer) {
            jobs += viewModelScope.launch {
                try {
                    // L'offerer est propriétaire de la paire : il purge l'ancien offer/answer/ice
                    // AVANT d'envoyer une offre fraîche. Garantit que l'answerer lit une offre
                    // récente et que l'offerer n'hérite pas d'une answer périmée (rejoin propre).
                    signaling.clearConnection(callId, key)

                    val offer = pc.createOfferSuspend()
                    pc.setLocalSuspend(offer)
                    signaling.sendOffer(callId, key, offer.description)

                    // Attendre la réponse
                    val answerSdp = signaling.observeAnswer(callId, key).first { it != null }!!
                    pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                    remoteSet = true
                    iceQueue.forEach { pc.addIceCandidate(it) }
                    iceQueue.clear()

                } catch (e: Exception) { SLog.e("Offer [$remoteName]: ${e.message}") }
            }
        } else {
            jobs += viewModelScope.launch {
                try {
                    // Attendre l'offre
                    val offerSdp = signaling.observeOffer(callId, key).first { it != null }!!
                    pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
                    remoteSet = true
                    iceQueue.forEach { pc.addIceCandidate(it) }
                    iceQueue.clear()

                    val answer = pc.createAnswerSuspend()
                    pc.setLocalSuspend(answer)
                    signaling.sendAnswer(callId, key, answer.description)

                } catch (e: Exception) { SLog.e("Answer [$remoteName]: ${e.message}") }
            }
        }
    }

    /** Détruit proprement la connexion vers un pair : annule ses observers, ferme la PC, vide sa tile. */
    private fun teardownPeer(remoteName: String) {
        peerJobs.remove(remoteName)?.forEach { it.cancel() }
        webRtcManager.closePeerConnection(remoteName)
        if (_remoteTracks.value.containsKey(remoteName)) {
            _remoteTracks.value = _remoteTracks.value + (remoteName to null)
        }
    }

    // ── Observer le statut ────────────────────────────────────────────────

    private fun observeStatus(callId: String) {
        viewModelScope.launch {
            signaling.observeStatus(callId).collect { status ->
                when (status) {
                    "active" -> _peerAnswered.value = true   // quelqu'un a décroché → couper le ringback
                    "ended"  -> if (_callState.value != CallState.Ended) {
                        _callState.value = CallState.Ended
                    }
                }
            }
        }
    }

    // ── Terminer l'appel ──────────────────────────────────────────────────

    fun endCall() {
        if (_callState.value == CallState.Ended) return
        callerTimeoutJob?.cancel()
        audioLevelJob?.cancel()
        _callState.value = CallState.Ended

        val callId  = this.callId
        val myName  = this.myName
        val members = this.members
        if (callId.isEmpty()) return

        // appScope (et non viewModelScope) : ces écritures DOIVENT aboutir même après que
        // l'activité finit et que le ViewModel est cleared. La fermeture des PeerConnection
        // est gérée par release()/WebRtcManager.release().
        SiwelApplication.appScope.launch {
            try {
                // Je me retire : `left` (mesh + bannière) et je quitte `present` (terminaison).
                signaling.recordLeft(callId, myName)
                signaling.removePresent(callId, myName)

                // L'appel se termine quand plus PERSONNE n'est présent (dernier parti M11/M12, ou
                // initiateur qui annule avant réponse : present={initiateur}→vide). On ajoute
                // `leftCount >= total` : robuste à la course des départs quasi-simultanés (M18) car
                // recordLeft est monotone → le dernier partant voit toujours "tout le monde parti".
                val presentCount = signaling.getPresentCount(callId)
                val leftCount    = signaling.getLeftCount(callId)
                if (presentCount == 0 || leftCount >= members.size) {
                    terminateCall(callId)
                }
            } catch (e: Exception) {
                SLog.e("endCall: ${e.message}")
            }
        }
    }

    /** Terminaison définitive d'un appel : statut "ended" + nettoyage Firebase. */
    private suspend fun terminateCall(callId: String) {
        signaling.setStatus(callId, "ended")
        signaling.cleanup(callId)
    }

    fun setCallActive(callId: String) {
        viewModelScope.launch {
            try { signaling.setStatus(callId, "active") }
            catch (e: Exception) { SLog.e("setActive: ${e.message}") }
        }
    }

    private val _leftMembers = MutableStateFlow<List<String>>(emptyList())
    val leftMembers: StateFlow<List<String>> = _leftMembers.asStateFlow()

    private fun observeWhoLeft(callId: String) {
        viewModelScope.launch {
            signaling.observeLeft(callId).collect { left ->
                _leftMembers.value = left
                if (_callState.value != CallState.Active) return@collect

                // Le set "left" pilote le cycle de vie de chaque PeerConnection :
                //  - pair qui quitte  → on détruit sa connexion (libère PC + observers)
                //  - pair qui revient → on reconstruit une connexion neuve (renégociation propre)
                members.filter { it != myName }.forEach { remote ->
                    val connected = peerJobs.containsKey(remote)
                    if (left.contains(remote)) {
                        if (connected) teardownPeer(remote)
                    } else {
                        if (!connected) connectPeer(callId, remote)
                    }
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun release() {
        if (released) return
        released = true
        endCall()
        webRtcManager.release()
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    companion object {
        // Seuil de niveau audio (0..1) au-delà duquel on considère que la personne parle.
        private const val SPEAK_THRESHOLD = 0.012
    }
}