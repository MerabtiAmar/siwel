package com.siwel.siwel

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.webrtc.PeerConnectionFactory

class SiwelApplication : Application() {

    companion object {
        /**
         * Scope à durée de vie processus pour les écritures Firebase critiques de fin d'appel
         * (recordLeft, cleanup, setStatus). Elles ne doivent PAS dépendre du viewModelScope,
         * annulé dès que CallActivity.finish() déclenche onCleared() — sinon elles n'aboutissent
         * pas et l'appel reste "fantôme" dans Firebase.
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
    }
}