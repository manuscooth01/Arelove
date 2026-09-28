package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityMainBinding
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.handleDeeplinks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

            // Deep link de vuelta desde OAuth (Google): com.arelove.app://auth/callback
            if (intent.data?.toString()?.startsWith("com.arelove.app://") == true) {
                if (procesarCallbackGoogle()) {
                    irAHome()
                    return@launch
                }
                Toast.makeText(
                    this@MainActivity,
                    R.string.error_google_login,
                    Toast.LENGTH_LONG
                ).show()
                // Si falló, se continúa con el flujo normal (mostrar accesos)
            }

            if (SupabaseClient.client.auth.currentSessionOrNull() != null) {
                irAHome()
                return@launch
            }

            configurarBotones()
        }
    }

    /**
     * Procesa el deep link devuelto por Supabase e importa la sesión.
     * Devuelve true si la sesión quedó autenticada.
     */
    private suspend fun procesarCallbackGoogle(): Boolean {
        val sesionRecibida = CompletableDeferred<Unit>()
        return try {
            // handleDeeplinks analiza el Intent y lanza la importación de la sesión
            SupabaseClient.client.handleDeeplinks(intent) { sesionRecibida.complete(Unit) }
            // La importación es asíncrona: esperamos la sesión con tope de 15 s
            withTimeoutOrNull(15_000L) {
                sesionRecibida.await()
                SupabaseClient.client.auth.sessionStatus.first {
                    it is SessionStatus.Authenticated
                }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Abre Home limpiando la pila: el deep link llega apilado sobre
     * LoginActivity y no queremos que "Atrás" muestre Login ya logueado.
     */
    private fun irAHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
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
