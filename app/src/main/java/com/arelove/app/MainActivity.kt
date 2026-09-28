package com.arelove.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.databinding.ActivityMainBinding
import io.github.jan.supabase.gotrue.auth

/**
 * Pantalla de bienvenida de Arelove.
 * Muestra el logo y los accesos a iniciar sesión / registrarse.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (SupabaseClient.client.auth.currentSessionOrNull() != null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        configurarBotones()
    }

    private fun configurarBotones() {
        binding.buttonLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.buttonRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}
