package com.arelove.app

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityRegisterBinding
import com.arelove.app.ui.AreloveDialog
import com.google.android.material.textfield.TextInputEditText
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pantalla de registro de Arelove.
 * Crea la cuenta con Supabase Auth (signUpWith Email + nickname en user_metadata).
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
        binding.btnRegister.setOnClickListener { crearCuenta() }

        binding.tvGoLogin.setOnClickListener { finish() }

        binding.ivTogglePassword.alpha = 0.4f
        binding.ivTogglePassword.setOnClickListener { alternarPassword() }

        binding.ivToggleConfirmPassword.alpha = 0.4f
        binding.ivToggleConfirmPassword.setOnClickListener { alternarConfirmPassword() }

        binding.cbTerms.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.cbTerms.error = null
        }
    }

    private fun configurarCampos() {
        binding.etNickname.doAfterTextChanged { binding.tilNickname.error = null }
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

    private fun crearCuenta() {
        val nickname = binding.etNickname.text?.toString()?.trim().orEmpty()
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()
        val passwordValue = binding.etPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        binding.tilNickname.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
        binding.cbTerms.error = null

        if (nickname.isEmpty()) {
            binding.tilNickname.error = "Campo obligatorio"
            return
        }

        if (emailValue.isEmpty()) {
            binding.tilEmail.error = "Campo obligatorio"
            return
        }

        if (passwordValue.isEmpty()) {
            binding.tilPassword.error = "Campo obligatorio"
            return
        }

        if (confirmPassword.isEmpty()) {
            binding.tilConfirmPassword.error = "Campo obligatorio"
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            binding.tilEmail.error = "Correo electrónico inválido"
            return
        }

        if (passwordValue.length < 8) {
            binding.tilPassword.error = "Mínimo 8 caracteres"
            return
        }

        if (passwordValue != confirmPassword) {
            binding.tilConfirmPassword.error = "Las contraseñas no coinciden"
            return
        }

        if (!binding.cbTerms.isChecked) {
            binding.cbTerms.error = "Debes aceptar los términos"
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
                AreloveDialog.show(
                    this@RegisterActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.dialog_register_success_msg),
                    onPositive = { finish() }
                )
            } catch (e: Exception) {
                AreloveDialog.show(
                    this@RegisterActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    AuthErrorMapper.traducir(e.message)
                )
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }
}
