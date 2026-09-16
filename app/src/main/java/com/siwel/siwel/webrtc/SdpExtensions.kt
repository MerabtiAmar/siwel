package com.siwel.siwel.webrtc

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { c -> createOffer(createObserver(c), MediaConstraints()) }

suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
    suspendCancellableCoroutine { c -> createAnswer(createObserver(c), MediaConstraints()) }

suspend fun PeerConnection.setLocalSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { c -> setLocalDescription(setObserver(c), sdp) }

suspend fun PeerConnection.setRemoteSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { c -> setRemoteDescription(setObserver(c), sdp) }

private fun createObserver(c: CancellableContinuation<SessionDescription>) =
    object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { c.resume(sdp) }
        override fun onCreateFailure(err: String) { c.resumeWithException(Exception(err)) }
        override fun onSetSuccess() {}
        override fun onSetFailure(err: String) {}
    }

private fun setObserver(c: CancellableContinuation<Unit>) =
    object : SdpObserver {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(err: String) { c.resumeWithException(Exception(err)) }
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onCreateFailure(err: String) {}
    }