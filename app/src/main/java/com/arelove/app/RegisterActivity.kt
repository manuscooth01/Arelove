package com.arelove.app

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityRegisterBinding
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pantalla de registro de Arelove.
 * Crea la cuenta con Supabase Auth (signUpWith Email + datos del perfil).
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
    }

    private fun configurarBotones() {
        binding.btnRegister.setOnClickListener { crearCuenta() }

        binding.tvGoLogin.setOnClickListener { finish() }
    }

    private fun crearCuenta() {
        val nickname = binding.etNickname.text?.toString()?.trim().orEmpty()
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()
        val passwordValue = binding.etPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        if (nickname.isEmpty() || emailValue.isEmpty() ||
            passwordValue.isEmpty() || confirmPassword.isEmpty()
        ) {
            Toast.makeText(this, R.string.required_field, Toast.LENGTH_SHORT).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            Toast.makeText(this, R.string.error_invalid_email, Toast.LENGTH_SHORT).show()
            return
        }

        if (passwordValue.length < 8) {
            Toast.makeText(this, R.string.error_short_password, Toast.LENGTH_SHORT).show()
            return
        }

        if (passwordValue != confirmPassword) {
            Toast.makeText(this, R.string.error_passwords_dont_match, Toast.LENGTH_SHORT).show()
            return
        }

        if (!binding.cbTerms.isChecked) {
            Toast.makeText(this, R.string.error_accept_terms, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signUpWith(Email) {
                    this.email = emailValue
                    this.password = passwordValue
                    data = buildJsonObject { put("nickname", nickname) }
                }
                Toast.makeText(
                    this@RegisterActivity,
                    R.string.register_success,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@RegisterActivity,
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }
}
