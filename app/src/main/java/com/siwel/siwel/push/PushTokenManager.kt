package com.siwel.siwel.push

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.siwel.siwel.SiwelApplication
import com.siwel.siwel.auth.SessionManager
import com.siwel.siwel.firebase.SignalingRepository
import com.siwel.siwel.utils.SLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Publie le jeton FCM de l'utilisateur dans Firebase (siwel/tokens/{nom}) pour qu'il puisse
 * RECEVOIR les appels. À appeler à CHAQUE ouverture (HomeActivity.onStart) → auto-réparant.
 *
 * AGRESSIF + DIAGNOSTIC : chaque étape (auth, récupération du jeton, écriture) est réessayée
 * plusieurs fois, et le résultat (succès / raison de l'échec) est remonté via [onResult] pour
 * pouvoir AFFICHER l'erreur à l'écran — indispensable pour diagnostiquer les téléphones
 * récalcitrants (Xiaomi/MIUI, Google Play Services absent ou bridé, etc.).
 */
object PushTokenManager {

    private const val MAX_RETRIES = 4
    private const val RETRY_DELAY_MS = 2500L

    /** onResult(success, errorReason) — appelé sur un thread de fond ; reposter sur le main si UI. */
    fun register(context: Context, onResult: ((Boolean, String?) -> Unit)? = null) {
        val name = SessionManager(context).getLoggedName()
        if (name == null) { onResult?.invoke(false, "non connecté"); return }

        SiwelApplication.appScope.launch {
            val result = runCatching {
                ensureAuth()
                val token = fetchToken()
                if (!writeWithRetry(name, token)) throw IllegalStateException("écriture Firebase échouée")
                true
            }
            // Remonte la VRAIE erreur (classe + message) pour un diagnostic précis côté écran.
            val reason = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            onResult?.invoke(result.getOrDefault(false), reason)
        }
    }

    /** Republie un jeton déjà connu (cas onNewToken). */
    fun save(context: Context, token: String) {
        val name = SessionManager(context).getLoggedName() ?: return
        SiwelApplication.appScope.launch {
            try {
                ensureAuth()
                writeWithRetry(name, token)
            } catch (e: Exception) {
                SLog.e("save token: ${e.message}")
            }
        }
    }

    /** Retire mon jeton (déconnexion). */
    fun unregister(name: String) {
        SiwelApplication.appScope.launch {
            try { SignalingRepository().removeToken(name) }
            catch (e: Exception) { SLog.e("removeToken: ${e.message}") }
        }
    }

    private suspend fun ensureAuth() {
        val auth = FirebaseAuth.getInstance()
        repeat(MAX_RETRIES) { attempt ->
            if (auth.currentUser != null) return
            try { auth.signInAnonymously().await(); return }
            catch (e: Exception) { SLog.e("auth tentative ${attempt + 1}: ${e.message}"); delay(RETRY_DELAY_MS) }
        }
        if (auth.currentUser == null) throw IllegalStateException("auth Firebase impossible")
    }

    private suspend fun fetchToken(): String {
        var last: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try { return FirebaseMessaging.getInstance().token.await() }
            catch (e: Exception) {
                last = e
                SLog.e("récup jeton tentative ${attempt + 1}: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
        throw last ?: IllegalStateException("jeton FCM indisponible")
    }

    private suspend fun writeWithRetry(name: String, token: String): Boolean {
        repeat(MAX_RETRIES) { attempt ->
            try {
                SignalingRepository().saveToken(name, token)
                SLog.d("Jeton FCM publié pour $name")
                return true
            } catch (e: Exception) {
                SLog.e("écriture jeton tentative ${attempt + 1}: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
        return false
    }
}
