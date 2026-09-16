package com.siwel.siwel.firebase

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class MemberPresence(val name: String, val online: Boolean)

class PresenceRepository {

    private val db = FirebaseDatabase.getInstance(FirebaseConfig.DATABASE_URL)
    private val presenceRef = db.reference.child("siwel").child("presence")

    // .info/connected = pattern robuste Firebase
    // Chaque fois que la connexion est (ré)établie → re-register onDisconnect + set online
    private var connectedListener: ValueEventListener? = null

    fun startPresence(name: String) {
        val connectedRef = db.getReference(".info/connected")
        connectedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    val userRef = presenceRef.child(name)
                    userRef.child("online").onDisconnect().setValue(false)
                    userRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                    userRef.child("online").setValue(true)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        connectedRef.addValueEventListener(connectedListener!!)
    }

    fun stopPresence(name: String) {
        presenceRef.child(name).child("online").setValue(false)
        connectedListener?.let {
            db.getReference(".info/connected").removeEventListener(it)
        }
        connectedListener = null
    }

    fun observeAllPresences(members: List<String>): Flow<List<MemberPresence>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val presences = members.map { name ->
                    val online = snapshot.child(name).child("online")
                        .getValue(Boolean::class.java) ?: false
                    MemberPresence(name, online)
                }
                trySend(presences)
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        presenceRef.addValueEventListener(listener)
        awaitClose { presenceRef.removeEventListener(listener) }
    }
}