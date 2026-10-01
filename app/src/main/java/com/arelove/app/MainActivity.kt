package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import com.arelove.app.databinding.ActivityMainBinding
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.result.ActivityResultLauncher

/**
 * Pantalla de bienvenida de Arelove.
 * Muestra splash mientras verifica sesión; si hay sesión navega a Home/ProfileSetup,
 * si no hay sesión muestra botones Login/Register.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val launcherPermisoNotificaciones =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { concedido ->
            // No bloquear si rechaza, solo loguear
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-calentar CredentialManager para que la primera llamada a 
        // Google Sign-In no falle
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                androidx.credentials.CredentialManager.create(this@MainActivity)
            } catch (_: Exception) {
                // Ignorar, solo queremos que el módulo se cargue
            }
        }

        // Pedir permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { concedido ->
                    // No bloquear si rechaza, solo loguear
                }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

        // Precarga el cliente Supabase fuera del hilo principal
        lifecycleScope.launch(Dispatchers.IO) {
            SupabaseClient.client // fuerza la inicialización lazy
        }

        // Verifica sesión inmediatamente (antes de mostrar botones)
        lifecycleScope.launch {
            try {
                // Espera a que GoTrue termine de cargar la sesión guardada en disco
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
                // Si falla la inicialización, mostramos los botones
                mostrarContenido()
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
                    // Si falla la comprobación, vamos a Home
                    startActivity(Intent(this@MainActivity, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
                }
            } else {
                // Sin sesión: mostrar botones
                mostrarContenido()
            }
        }
    }

    private fun mostrarContenido() {
        binding.splashLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
        configurarBotones()
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