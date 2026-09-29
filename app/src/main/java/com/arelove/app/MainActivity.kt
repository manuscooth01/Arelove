package com.arelove.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityMainBinding
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

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

        lifecycleScope.launch {
            // Espera a que GoTrue termine de cargar la sesión guardada en disco
            SupabaseClient.client.auth.awaitInitialization()

            if (SupabaseClient.client.auth.currentSessionOrNull() != null) {
                // Hay sesión: comprobamos si el perfil ya está completo antes de entrar
                val destino = if (ProfileChecker.perfilCompleto()) {
                    HomeActivity::class.java
                } else {
                    ProfileSetupActivity::class.java
                }
                startActivity(Intent(this@MainActivity, destino))
                finish()
                return@launch
            }

            configurarBotones()
        }
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
