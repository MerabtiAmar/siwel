package com.siwel.siwel.firebase

import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class SignalingRepository {

    val db = FirebaseDatabase.getInstance(FirebaseConfig.DATABASE_URL)
    private val calls = db.reference.child("siwel").child("calls")
    private val tokens = db.reference.child("siwel").child("tokens")

    // ── Jetons FCM (push d'appel entrant) ─────────────────────────────────
    // Chaque membre publie son jeton FCM sous siwel/tokens/{nom}. Le démon sur le serveur Oracle
    // les lit pour envoyer la notification push qui réveille le téléphone lors d'un appel.

    suspend fun saveToken(name: String, token: String) {
        tokens.child(name).setValue(token).await()
    }

    suspend fun removeToken(name: String) {
        tokens.child(name).removeValue().await()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Clé de connexion : nom alphabétiquement plus petit en premier */
    fun connKey(a: String, b: String) = if (a < b) "${a}_${b}" else "${b}_${a}"

    /** Suis-je l'offerer dans cette paire ? */
    fun isOfferer(me: String, remote: String) = me < remote

    // ── Cycle de vie d'un appel ───────────────────────────────────────────

    suspend fun createCall(callId: String, initiator: String, members: List<String>) {
        // `present` posé ATOMIQUEMENT avec la création : un appel vivant a toujours ≥1 présent.
        // Donc "present vide" ⇒ appel fantôme (à supprimer), sans fenêtre de course.
        calls.child(callId).setValue(
            mapOf(
                "initiator" to initiator,
                "status"    to "ringing",
                "members"   to members,
                "present"   to mapOf(initiator to true)
            )
        ).await()
    }

    suspend fun setStatus(callId: String, status: String) {
        calls.child(callId).child("status").setValue(status).await()
    }

    fun observeStatus(callId: String): Flow<String> = callbackFlow {
        val ref = calls.child(callId).child("status")
        val l = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                s.getValue(String::class.java)?.let { trySend(it) }
            }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        awaitClose { ref.removeEventListener(l) }
    }

    fun cleanup(callId: String) {
        calls.child(callId).removeValue()
    }

    /**
     * Purge le nœud de signaling d'une paire (offer/answer/ice périmés).
     * Indispensable avant un rejoin : sinon l'offerer relit l'ancienne answer
     * et le pair restant ne renégocie jamais proprement (BUG 1).
     */
    suspend fun clearConnection(callId: String, key: String) {
        calls.child(callId).child("connections").child(key).removeValue().await()
    }

    /**
     * Balaye et supprime les appels résiduels (anti-accumulation + anti-fausse-bannière) :
     *  - statut "ended", ou
     *  - tous les membres sont dans "left", ou
     *  - AUCUN présent (appel fantôme : plus personne dedans, ou ancien appel sans `present`).
     * `present` étant posé atomiquement à la création, "0 présent" ne supprime jamais un appel vivant.
     */
    suspend fun cleanupStaleCalls() {
        val snap = calls.get().await()
        snap.children.forEach { child ->
            val status       = child.child("status").getValue(String::class.java)
            val members      = child.child("members").children.mapNotNull { it.getValue(String::class.java) }
            val left         = child.child("left").children.mapNotNull { it.key }
            val presentCount = child.child("present").childrenCount
            val allLeft      = members.isNotEmpty() && members.all { left.contains(it) }
            if (status == "ended" || allLeft || presentCount == 0L) {
                child.ref.removeValue()
            }
        }
    }
    // ── SDP ───────────────────────────────────────────────────────────────

    suspend fun sendOffer(callId: String, key: String, sdp: String) {
        calls.child(callId).child("connections").child(key).child("offer")
            .setValue(mapOf("sdp" to sdp)).await()
    }

    suspend fun sendAnswer(callId: String, key: String, sdp: String) {
        calls.child(callId).child("connections").child(key).child("answer")
            .setValue(mapOf("sdp" to sdp)).await()
    }

    fun observeOffer(callId: String, key: String)  = sdpFlow(callId, key, "offer")
    fun observeAnswer(callId: String, key: String) = sdpFlow(callId, key, "answer")

    private fun sdpFlow(callId: String, key: String, node: String): Flow<String?> = callbackFlow {
        val ref = calls.child(callId).child("connections").child(key).child(node)
        val l = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trySend(s.child("sdp").getValue(String::class.java))
            }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        awaitClose { ref.removeEventListener(l) }
    }

    // ── ICE ───────────────────────────────────────────────────────────────

    fun sendIce(callId: String, key: String, sender: String,
                sdp: String, mid: String, idx: Int) {
        calls.child(callId).child("connections").child(key)
            .child("ice").child(sender).push()
            .setValue(mapOf("sdp" to sdp, "mid" to mid, "idx" to idx))
    }

    fun observeIce(callId: String, key: String, sender: String)
            : Flow<Triple<String, String, Int>> = callbackFlow {
        val ref = calls.child(callId).child("connections").child(key)
            .child("ice").child(sender)
        val l = ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val sdp = s.child("sdp").getValue(String::class.java) ?: return
                val mid = s.child("mid").getValue(String::class.java) ?: ""
                val idx = s.child("idx").getValue(Int::class.java) ?: 0
                trySend(Triple(sdp, mid, idx))
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        awaitClose { ref.removeEventListener(l) }
    }

    suspend fun recordDecline(callId: String, memberName: String) {
        calls.child(callId).child("declined").child(memberName).setValue(true).await()
    }

    /** Premier appel joignable (ringing/active) existant, ou null. Sert à éviter de créer un
     *  appel concurrent : le bouton "Appeler" rejoint l'appel en cours plutôt que d'en ouvrir un
     *  second (M15/M16). */
    suspend fun getAnyJoinableCall(): Pair<String, List<String>>? {
        val snap = calls.get().await()
        val found = snap.children.firstOrNull {
            val s = it.child("status").getValue(String::class.java)
            s == "ringing" || s == "active"
        } ?: return null
        val id = found.key ?: return null
        val members = found.child("members").children.mapNotNull { it.getValue(String::class.java) }
        return if (members.isEmpty()) null else Pair(id, members)
    }

    suspend fun getCallInfo(callId: String): Pair<String, List<String>>? {
        val snap = calls.child(callId).get().await()
        val initiator = snap.child("initiator").getValue(String::class.java) ?: return null
        val members = snap.child("members").children.mapNotNull { it.getValue(String::class.java) }
        return Pair(initiator, members)
    }

    /**
     * Émet l'appel "rejoignable" pour [myName] : un appel joignable (ringing OU active) dont je me
     * suis retiré (présent dans `left` ou `declined`). C'est la source de vérité de la bannière
     * "Rejoindre" — elle couvre uniformément : j'ai quitté, j'ai refusé, je n'ai pas répondu (45s).
     * N'émet PAS pour un destinataire qui sonne encore (il n'est ni dans left ni dans declined) :
     * lui voit l'écran d'appel entrant, pas la bannière. null si rien à rejoindre.
     */
    fun observeJoinableCall(myName: String): Flow<Pair<String, List<String>>?> = callbackFlow {
        val l = calls.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val found = s.children.firstOrNull { c ->
                    val status   = c.child("status").getValue(String::class.java)
                    val joinable = status == "ringing" || status == "active"
                    val optedOut = c.child("left").hasChild(myName) ||
                                   c.child("declined").hasChild(myName)
                    joinable && optedOut
                }
                if (found == null) { trySend(null); return }

                val id = found.key ?: run { trySend(null); return }
                val members = found.child("members").children
                    .mapNotNull { it.getValue(String::class.java) }
                if (members.isEmpty()) trySend(null) else trySend(Pair(id, members))
            }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        awaitClose { calls.removeEventListener(l) }
    }

    suspend fun recordLeft(callId: String, memberName: String) {
        calls.child(callId).child("left").child(memberName).setValue(true).await()
    }

    suspend fun getLeftCount(callId: String): Int =
        calls.child(callId).child("left").get().await().childrenCount.toInt()

    fun observeLeft(callId: String): Flow<List<String>> = callbackFlow {
        val ref = calls.child(callId).child("left")
        val l = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val left = s.children.mapNotNull { it.key }
                trySend(left)
            }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        awaitClose { ref.removeEventListener(l) }
    }

    suspend fun removeLeft(callId: String, memberName: String) {
        calls.child(callId).child("left").child(memberName).removeValue().await()
    }

    suspend fun getDeclinedCount(callId: String): Int =
        calls.child(callId).child("declined").get().await().childrenCount.toInt()

    suspend fun removeDeclined(callId: String, memberName: String) {
        calls.child(callId).child("declined").child(memberName).removeValue().await()
    }

    // ── Présence dans l'appel (drive la terminaison) ──────────────────────
    // `present` = membres actuellement DANS l'appel. L'appel se termine quand `present` est vide
    // (dernier parti, ou initiateur qui annule avant toute réponse). Distinct de `left` (qui pilote
    // le mesh et la bannière) : un membre jamais entré n'est ni présent ni "left".

    suspend fun addPresent(callId: String, memberName: String) {
        calls.child(callId).child("present").child(memberName).setValue(true).await()
    }

    suspend fun removePresent(callId: String, memberName: String) {
        calls.child(callId).child("present").child(memberName).removeValue().await()
    }

    suspend fun getPresentCount(callId: String): Int =
        calls.child(callId).child("present").get().await().childrenCount.toInt()

    suspend fun getCallStatus(callId: String): String? =
        calls.child(callId).child("status").get().await()
            .getValue(String::class.java)
}