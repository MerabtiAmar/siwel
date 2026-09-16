package com.siwel.siwel.auth

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.siwel.siwel.R
import com.siwel.siwel.databinding.ActivityLoginBinding
import com.siwel.siwel.home.HomeActivity
import com.siwel.siwel.push.PushTokenManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // Session existante → skip login direct
        if (sessionManager.isLoggedIn()) {
            signInAnonymouslyAndProceed()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        val names = sessionManager.getMembers().map { it.name }
        binding.spinnerName.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            names
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }

        binding.btnConnect.setOnClickListener {
            val name = binding.spinnerName.selectedItem.toString()
            val password = binding.etPassword.text.toString().trim()

            if (password.isEmpty()) {
                binding.etPassword.error = "Mot de passe requis"
                return@setOnClickListener
            }

            if (sessionManager.validateAndSave(name, password)) {
                binding.btnConnect.isEnabled = false
                binding.btnConnect.text = "Connexion..."
                signInAnonymouslyAndProceed()
            } else {
                binding.etPassword.setText("")
                Toast.makeText(this, "Mot de passe incorrect", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun signInAnonymouslyAndProceed() {
        // Token Firebase encore valide → pas besoin de re-signer
        if (auth.currentUser != null) {
            goToHome()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { goToHome() }
            .addOnFailureListener {
                sessionManager.logout()
                Toast.makeText(
                    applicationContext,
                    "Erreur réseau, réessaie",
                    Toast.LENGTH_SHORT
                ).show()
                recreate()
            }
    }

    private fun goToHome() {
        // Plus de service permanent : on publie juste le jeton FCM pour être joignable (push).
        PushTokenManager.register(this)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}