package com.arelove.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityHomeBinding
import com.arelove.app.ui.AreloveDialog
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

/**
 * Pantalla principal tras iniciar sesión (placeholder).
 * Muestra el email del usuario y permite cerrar la sesión.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvEmail.text = SupabaseClient.client.auth.currentUserOrNull()?.email.orEmpty()

        binding.btnLogout.setOnClickListener { cerrarSesion() }
    }

    private fun cerrarSesion() {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signOut()
                irAInicio()
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@HomeActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(this@HomeActivity, e.message),
                    onPositive = { irAInicio() }
                )
            }
        }
    }

    /** Vuelve a la pantalla de bienvenida. */
    private fun irAInicio() {
        val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }
}
