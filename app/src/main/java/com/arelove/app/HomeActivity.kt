package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityHomeBinding
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
            } catch (e: Exception) {
                Toast.makeText(
                    this@HomeActivity,
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
            }
            val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        }
    }
}
