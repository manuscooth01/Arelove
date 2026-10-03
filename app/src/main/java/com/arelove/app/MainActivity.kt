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
import com.arelove.app.notifications.OneSignalManager
import com.arelove.app.notifications.PushRouter
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
 * También maneja deep links de notificaciones push (via PushRouter).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // true cuando un enlace de recuperación ya resolvió (sesión importada o
    // error mostrado): evita avisos y navegaciones duplicados
    private var enlaceResuelto = false

    // Track si ya procesamos push payload para no duplicar
    private var pushProcesado = false

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

        // Procesar posible push navigation al arrancar (cold start)
        procesarPushSiExiste(intent)

        // Verifica sesión inmediatamente (antes de mostrar botones)
        lifecycleScope.launch {
            try {
                // Espera a que GoTrue termine de cargar la sesión guardada en disco
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
                // Si falla la inicialización, mostramos los botones
                mostrarContenido()
                return@launch
            }

            val session = SupabaseClient.client.auth.currentSessionOrNull()
            if (session != null) {
                // Registra el player id de OneSignal en un coroutine aparte
                // para no retrasar la navegacion
                lifecycleScope.launch {
                    SupabaseClientExt.registrarOneSignalPlayerId()
                }
                try {
                    val destino = if (ProfileChecker.perfilCompleto()) {
                        HomeActivity::class.java
                    } else {
                        ProfileSetupActivity::class.java
                    }
                    startActivity(Intent(this@MainActivity, destino).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Reenviar extras de push si los había
                        intent.extras?.let { putExtras(it) }
                    })
                    finish()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error comprobando perfil", e)
                    // Si falla la comprobación, vamos a Home
                    startActivity(Intent(this@MainActivity, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.extras?.let { putExtras(it) }
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
     * Procesa posible navegación desde push (cold start: app cerrada).
     * Si el intent trae extras de push, los emite al PushRouter para que
     * HomeActivity/ChatActivity los recoja cuando se crea.
     */
    private fun procesarPushSiExiste(intent: Intent) {
        val extras = intent.extras ?: return
        val tipo = extras.getString("tipo") ?: return

        if (pushProcesado) return
        pushProcesado = true

        Log.d("MainActivity", "Cold start con push: tipo=$tipo")
        Log.d("MainActivity", "Intent extras: $extras")
        lifecycleScope.launch {
            PushRouter.enrutar(PushRouter.fromExtras(extras)!!)
        }
    }

    /**
     * Maneja nuevos intents cuando la actividad ya está viva (singleTop).
     * Recibe clicks de notificación push.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // importante: actualizar intent para getIntent() posterior

        val extras = intent.extras ?: return
        val tipo = extras.getString("tipo") ?: return

        Log.d("MainActivity", "onNewIntent push: tipo=$tipo")
        Log.d("MainActivity", "Intent extras: $extras")
        lifecycleScope.launch {
            PushRouter.enrutar(PushRouter.fromExtras(extras)!!)
        }

        // Si ya hay sesión, reenviar a HomeActivity existente
        val session = SupabaseClient.client.auth.currentSessionOrNull()
        if (session != null) {
            // Re-registrar player id por si cambió
            lifecycleScope.launch {
                SupabaseClientExt.registrarOneSignalPlayerId()
            }
            val homeIntent = Intent(this, HomeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtras(extras)
            }
            startActivity(homeIntent)
        }
    }

    /**
     * true si el Intent viene del deep link de recuperación de contraseña.
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
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "awaitInitialization falló", e)
            }

            SupabaseClient.client.handleDeeplinks(intent) { _ ->
                runOnUiThread {
                    if (enlaceResuelto) return@runOnUiThread
                    enlaceResuelto = true
                    abrirNuevaContrasena()
                }
            }
        }

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

        buttonRegister.setOnClickListener {
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