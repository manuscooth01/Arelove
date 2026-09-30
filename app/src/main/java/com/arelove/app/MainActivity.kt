package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityMainBinding
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.Dispatchers
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

        // Los listeners no dependen de la sesión: se instalan ya para que los
        // botones respondan desde el primer frame (antes de cualquier red)
        configurarBotones()

        // Precarga el cliente Supabase fuera del hilo principal
        lifecycleScope.launch(Dispatchers.IO) {
            SupabaseClient.client // fuerza la inicialización lazy
        }

        lifecycleScope.launch {
            try {
                // Espera a que GoTrue termine de cargar la sesión guardada en disco
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
                // Si falla la inicialización, mostramos la pantalla de bienvenida
                // El usuario podrá hacer login manualmente
                return@launch
            }

            val session = SupabaseClient.client.auth.currentSessionOrNull()
            if (session != null) {
                try {
                    val destino = if (ProfileChecker.perfilCompleto()) {
                        HomeActivity::class.java
                    } else {
                        ProfileSetupActivity::class.java
                    }
                    startActivity(Intent(this@MainActivity, destino).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error comprobando perfil", e)
                    // Si falla la comprobación, vamos a Home para que el usuario
                    // decida; ProfileChecker ya loguea internamente
                    startActivity(Intent(this@MainActivity, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
                }
            }
        }
    }

    private fun configurarBotones() {
        binding.buttonLogin.setOnClickListener {
            if (AntiSpam.esDobleTap("buttonLogin")) return@setOnClickListener
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.buttonRegister.setOnClickListener {
            if (AntiSpam.esDobleTap("buttonRegister")) return@setOnClickListener
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}