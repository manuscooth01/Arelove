package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityLoginBinding
import com.arelove.app.ui.AreloveDialog
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

/**
 * Pantalla de inicio de sesión de Arelove.
 * Valida los campos (errores inline) y autentica con Supabase Auth.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
        configurarCampos()
    }

    private fun configurarBotones() {
        binding.btnLogin.setOnClickListener { iniciarSesion() }

        binding.btnGoogle.setOnClickListener { iniciarSesionConGoogle() }

        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.ivTogglePassword.alpha = 0.4f
        binding.ivTogglePassword.setOnClickListener { alternarPassword() }
    }

    private fun configurarCampos() {
        binding.etEmail.doAfterTextChanged { binding.tilEmail.error = null }
        binding.etPassword.doAfterTextChanged { binding.tilPassword.error = null }
    }

    private fun alternarPassword() {
        passwordVisible = !passwordVisible
        binding.etPassword.inputType = if (passwordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        binding.etPassword.transformationMethod = if (passwordVisible) {
            null
        } else {
            PasswordTransformationMethod.getInstance()
        }
        binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
        binding.ivTogglePassword.alpha = if (passwordVisible) 1.0f else 0.4f
    }

    /**
     * Abre el navegador con el OAuth de Google.
     * La sesión vuelve por deep link y la procesa MainActivity.
     */
    private fun iniciarSesionConGoogle() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnGoogle.isEnabled = false
        Toast.makeText(this, R.string.login_google_loading, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signInWith(
                    Google,
                    redirectUrl = "com.arelove.app://auth/callback"
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    AuthErrorMapper.traducir(e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnGoogle.isEnabled = true
            }
        }
    }

    private fun iniciarSesion() {
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()
        val passwordValue = binding.etPassword.text?.toString().orEmpty()

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        if (emailValue.isEmpty()) {
            binding.tilEmail.error = "Campo obligatorio"
            return
        }

        if (passwordValue.isEmpty()) {
            binding.tilPassword.error = "Campo obligatorio"
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
                AreloveDialog.show(
                    this@LoginActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.dialog_login_success_msg),
                    onPositive = {
                        startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                        finish()
                    }
                )
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@LoginActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(e.message)
                )
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }
}
