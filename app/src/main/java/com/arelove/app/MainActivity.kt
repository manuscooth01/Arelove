package com.arelove.app

import android.content.Intent
import android.net.Uri
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
import com.arelove.app.notifications.FcmTokenManager
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.handleDeeplinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.result.ActivityResultLauncher

/**
 * Pantalla de bienvenida de Arelove.
 * Muestra splash mientras verifica sesión; si hay sesión navega a Home/ProfileSetup,
 * si no hay sesión muestra botones Login/Register.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // true cuando un enlace de recuperación ya resolvió (sesión importada o
    // error mostrado): evita avisos y navegaciones duplicadas
    private var enlaceResuelto = false

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

        // Enlace de recuperación de contraseña (com.arelove.app://auth/reset):
        // se procesa aquí y no se continúa con la pantalla de bienvenida.
        if (esEnlaceRecuperacion()) {
            procesarEnlaceRecuperacion()
            return
        }

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
                // Si falla la inicialización, mostramos los botones
                mostrarContenido()
                return@launch
            }

            val session = SupabaseClient.client.auth.currentSessionOrNull()
            if (session != null) {
                // Registra el token FCM en un coroutine aparte para no
                // retrasar la navegacion
                lifecycleScope.launch {
                    FcmTokenManager.registrarToken()
                }
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelacion normal (navegacion del usuario): se relanza
                    // y nunca se traga (regla de corrutinas de Kotlin).
                    throw e
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

    /**
     * true si el Intent viene del deep link de recuperación de contraseña.
     * El scheme y el host se comprueban contra REDIRECT_RECUPERACION, que es
     * justo lo que el SDK compara con la configuración de Auth.
     */
    private fun esEnlaceRecuperacion(): Boolean {
        val datos = intent.data ?: return false
        val esperado = Uri.parse(SupabaseClient.REDIRECT_RECUPERACION)
        return datos.scheme == esperado.scheme && datos.host == esperado.host
    }

    /**
     * Importa la sesión de recovery que trae el enlace y abre la pantalla de
     * nueva contraseña. Si el enlace no trae tokens (caducado, ya usado o con
     * error) se avisa y se va al login.
     */
    private fun procesarEnlaceRecuperacion() {
        val fragmento = intent.data?.fragment
        if (fragmento.isNullOrBlank() || fragmento.contains("error") ||
            !fragmento.contains("type=recovery")
        ) {
            avisoEnlaceInvalido()
            return
        }

        lifecycleScope.launch {
            try {
                // Espera a que GoTrue termine de cargar la sesión guardada en disco
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
            }

            // El SDK lee el fragmento, importa la sesión y llama a esto
            SupabaseClient.client.handleDeeplinks(intent) { _ ->
                runOnUiThread {
                    if (enlaceResuelto) return@runOnUiThread
                    enlaceResuelto = true
                    abrirNuevaContrasena()
                }
            }
        }

        // Si el SDK no devuelve sesión (enlace mal formado o red caída) no
        // dejamos la splash colgada
        lifecycleScope.launch {
            delay(10_000)
            avisoEnlaceInvalido()
        }
    }

    private fun avisoEnlaceInvalido() {
        if (enlaceResuelto || isFinishing) return
        enlaceResuelto = true
        AreloveDialog.show(
            this,
            AreloveDialog.Tipo.WARNING,
            getString(R.string.dialog_warning_title),
            getString(R.string.error_reset_link),
            onPositive = { irALogin() }
        )
    }

    /** Abre la pantalla de nueva contraseña y limpia la pila de la bienvenida. */
    private fun abrirNuevaContrasena() {
        startActivity(Intent(this, ResetPasswordActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    /** Va al login sin dejar la bienvenida en el historial. */
    private fun irALogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
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

        binding.tvTerminos.setOnClickListener {
            abrirLegal("terminos")
        }

        binding.tvPrivacidad.setOnClickListener {
            abrirLegal("privacidad")
        }
    }

    /** Abre la pantalla legal con el tipo indicado ("terminos", "privacidad", etc.). */
    private fun abrirLegal(tipo: String) {
        startActivity(Intent(this, LegalActivity::class.java).apply {
            putExtra("tipo", tipo)
        })
    }
}