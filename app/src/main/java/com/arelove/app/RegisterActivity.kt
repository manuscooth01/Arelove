package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.admin.AdminChecker
import com.arelove.app.databinding.ActivityRegisterBinding
import com.arelove.app.notifications.OneSignalManager
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import com.google.android.material.textfield.TextInputEditText
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

/**
 * Pantalla de registro de Arelove.
 * Crea la cuenta con Supabase Auth (signUpWith Email).
 * El apodo se pide después, en el paso 1 del asistente de perfil.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var passwordVisible = false
    private var confirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
        configurarCampos()
    }

    private fun configurarBotones() {
        binding.btnRegister.setOnClickListener {
            if (AntiSpam.esDobleTap("btnRegister")) return@setOnClickListener
            crearCuenta()
        }

        binding.tvGoLogin.setOnClickListener {
            if (AntiSpam.esDobleTap("tvGoLogin")) return@setOnClickListener
            finish()
        }

        binding.ivTogglePassword.alpha = 0.4f
        binding.ivTogglePassword.setOnClickListener { alternarPassword() }

        binding.ivToggleConfirmPassword.alpha = 0.4f
        binding.ivToggleConfirmPassword.setOnClickListener { alternarConfirmPassword() }

        binding.cbTerms.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.cbTerms.error = null
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
        binding.etConfirmPassword.doAfterTextChanged { binding.tilConfirmPassword.error = null }
    }

    private fun alternarPassword() {
        passwordVisible = !passwordVisible
        aplicarVisibilidad(binding.etPassword, binding.ivTogglePassword, passwordVisible)
    }

    private fun alternarConfirmPassword() {
        confirmPasswordVisible = !confirmPasswordVisible
        aplicarVisibilidad(
            binding.etConfirmPassword,
            binding.ivToggleConfirmPassword,
            confirmPasswordVisible
        )
    }

    private fun aplicarVisibilidad(
        editText: TextInputEditText,
        icono: ImageView,
        visible: Boolean
    ) {
        editText.inputType = if (visible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        editText.transformationMethod = if (visible) {
            null
        } else {
            PasswordTransformationMethod.getInstance()
        }
        editText.setSelection(editText.text?.length ?: 0)
        icono.alpha = if (visible) 1.0f else 0.4f
    }

    /**
     * Abre el asistente de perfil y limpia la pila de pantallas,
     * para que al terminar el setup no se vuelva al registro.
     */
    private fun abrirProfileSetup() {
        // Cuenta nueva: nunca hereda el rol admin cacheado
        AdminChecker.invalidarCache()
        // TEMPORAL: Comentado por problema de compilación en OneSignalManager
        // lifecycleScope.launch {
        //     OneSignalManager.registrarPlayerId()
        // }
        val intent = Intent(this, ProfileSetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun crearCuenta() {
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()
        val passwordValue = binding.etPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
        binding.cbTerms.error = null

        if (emailValue.isEmpty()) {
            binding.tilEmail.error = getString(R.string.validation_required_field)
            return
        }

        if (passwordValue.isEmpty()) {
            binding.tilPassword.error = getString(R.string.validation_required_field)
            return
        }

        if (confirmPassword.isEmpty()) {
            binding.tilConfirmPassword.error = getString(R.string.validation_required_field)
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            binding.tilEmail.error = getString(R.string.validation_invalid_email)
            return
        }

        if (passwordValue.length < 8) {
            binding.tilPassword.error = getString(R.string.validation_min_password_length)
            return
        }

        if (passwordValue != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.error_passwords_dont_match)
            return
        }

        if (!binding.cbTerms.isChecked) {
            binding.cbTerms.error = getString(R.string.validation_accept_terms)
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signUpWith(Email) {
                    this.email = emailValue
                    this.password = passwordValue
                }
                AreloveDialog.show(
                    this@RegisterActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.dialog_register_success_msg),
                    onPositive = { abrirProfileSetup() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@RegisterActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(this@RegisterActivity, e.message)
                )
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }
}
