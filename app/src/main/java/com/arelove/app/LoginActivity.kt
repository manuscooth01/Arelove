package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.admin.AdminChecker
import com.arelove.app.databinding.ActivityLoginBinding
import com.arelove.app.notifications.OneSignalManager
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
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
        binding.btnLogin.setOnClickListener {
            if (AntiSpam.esDobleTap("btnLogin")) return@setOnClickListener
            iniciarSesion()
        }

        binding.btnGoogle.setOnClickListener {
            if (AntiSpam.esDobleTap("btnGoogle")) return@setOnClickListener
            iniciarSesionConGoogle()
        }

        binding.tvGoRegister.setOnClickListener {
            if (AntiSpam.esDobleTap("tvGoRegister")) return@setOnClickListener
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.ivTogglePassword.alpha = 0.4f
        binding.ivTogglePassword.setOnClickListener { alternarPassword() }

        binding.tvForgotPassword.setOnClickListener {
            if (AntiSpam.esDobleTap("tvForgotPassword")) return@setOnClickListener
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        binding.tvTerminos.setOnClickListener {
            startActivity(Intent(this, LegalActivity::class.java).apply {
                putExtra("tipo", "terminos")
            })
        }

        binding.tvPrivacidad.setOnClickListener {
            startActivity(Intent(this, LegalActivity::class.java).apply {
                putExtra("tipo", "privacidad")
            })
        }
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
     * Inicia sesión con Google usando Credential Manager (ventana nativa).
     * Se obtiene el ID Token y Supabase crea la sesión con él.
     */
    private fun iniciarSesionConGoogle() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnGoogle.isEnabled = false

        lifecycleScope.launch {
            try {
                val idToken = GoogleAuthHelper.obtenerIdToken(this@LoginActivity)

                if (idToken == null) {
                    AreloveDialog.show(
                        this@LoginActivity,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        getString(R.string.error_google_token_missing)
                    )
                    return@launch
                }

                SupabaseClient.client.auth.signInWith(IDToken) {
                    this.idToken = idToken
                    this.provider = Google
                }

                AreloveDialog.show(
                    this@LoginActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.dialog_login_success_msg),
                    onPositive = { navegarAMain() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@LoginActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(this@LoginActivity, e.message)
                )
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
            binding.tilEmail.error = getString(R.string.validation_required_field)
            return
        }

        if (passwordValue.isEmpty()) {
            binding.tilPassword.error = getString(R.string.validation_required_field)
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
                    onPositive = { navegarAMain() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@LoginActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(this@LoginActivity, e.message)
                )
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    /**
     * Abre MainActivity tras un inicio de sesión exitoso y limpia la pila para
     * no dejar el Login en el historial. MainActivity es la única que decide
     * entre HomeActivity y ProfileSetupActivity (comprueba el perfil), así que
     * aquí no se hace ninguna llamada de red.
     */
    private fun navegarAMain() {
        // Nueva sesión: olvidamos el rol admin cacheado (caché por usuario)
        AdminChecker.invalidarCache()
        // Vincula el player id de OneSignal al usuario recién autenticado
        lifecycleScope.launch {
            OneSignalManager.registrarPlayerId()
        }
        val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}
