package com.siwel.siwel.utils

import android.util.Log
import com.siwel.siwel.BuildConfig

/**
 * Logger unique de l'app. Tag cohérent ("Siwel") pour filtrer facilement le logcat.
 * Les logs de debug/info ne sortent qu'en build debug (BuildConfig.DEBUG) → aucune fuite de
 * journaux verbeux en production. Les erreurs sont toujours loguées.
 */
object SLog {
    private const val TAG = "Siwel"

    fun d(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }
    fun i(msg: String) { if (BuildConfig.DEBUG) Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}
