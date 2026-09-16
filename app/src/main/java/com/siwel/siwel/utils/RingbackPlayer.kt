package com.siwel.siwel.utils

import android.media.AudioManager
import android.media.ToneGenerator

class RingbackPlayer {

    private var toneGenerator: ToneGenerator? = null
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        try {
            toneGenerator = ToneGenerator(
                AudioManager.STREAM_VOICE_CALL, 80
            )
        } catch (e: Exception) {
            SLog.e("ringback init: ${e.message}")
            return
        }
        // ToneGenerator ne boucle pas automatiquement → thread dédié
        thread = Thread {
            while (running) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500)
                    Thread.sleep(3000) // 1.5s sonnerie + 1.5s silence
                } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
    }
}