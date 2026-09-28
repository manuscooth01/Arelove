package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityLoginBinding
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

/**
 * Pantalla de inicio de sesión de Arelove.
 * Valida los campos y autentica con Supabase Auth (signInWith Email).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
    }

    private fun configurarBotones() {
        binding.btnLogin.setOnClickListener { iniciarSesion() }

        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun iniciarSesion() {
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()
        val passwordValue = binding.etPassword.text?.toString().orEmpty()

        if (emailValue.isEmpty() || passwordValue.isEmpty()) {
            Toast.makeText(this, R.string.required_field, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signInWith(Email) {
                    this.email = emailValue
                    this.password = passwordValue
                }
                Toast.makeText(this@LoginActivity, R.string.login_welcome, Toast.LENGTH_SHORT)
                    .show()
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }
}
