package com.siwel.siwel.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.siwel.siwel.model.Member
import org.json.JSONArray

class SessionManager(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "siwel_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getMembers(): List<Member> {
        val json = context.assets.open("members.json")
            .bufferedReader()
            .use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Member(obj.getString("name"), obj.getString("password"))
        }
    }

    fun validateAndSave(name: String, password: String): Boolean {
        val member = getMembers().find { it.name == name && it.password == password }
        return if (member != null) {
            prefs.edit().putString(KEY_NAME, name).apply()
            true
        } else false
    }

    fun getLoggedName(): String? = prefs.getString(KEY_NAME, null)

    fun isLoggedIn(): Boolean = getLoggedName() != null

    fun logout() {
        prefs.edit().remove(KEY_NAME).apply()
    }

    companion object {
        private const val KEY_NAME = "logged_name"
    }
}