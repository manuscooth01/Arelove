package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.admin.AdminChecker
import com.arelove.app.databinding.ActivityResetPasswordBinding
import com.arelove.app.LogHelper
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan-tennert.supabase.gotrue.auth
import kotlinx.coroutines.launch

/**
 * Pantalla Nueva contraseña (A-08).
 * Se abre desde MainActivity cuando llega el deep link de recuperación, con la
 * sesión de recovery ya importada; guarda la contraseña nueva con Supabase Auth
 * y vuelve al inicio de sesión.
 */
class ResetPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResetPasswordBinding
    private var nuevaVisible = false
    private var confirmacionVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseNewPassword.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseNewPassword")) return@setOnClickListener
            finish()
        }

        binding.ivToggleNewPassword.alpha = 0.4f
        binding.ivToggleNewPassword.setOnClickListener {
            nuevaVisible = !nuevaVisible
            alternarPassword(binding.etNewPassword, binding.ivToggleNewPassword, nuevaVisible)
        }

        binding.ivToggleConfirmPassword.alpha = 0.4f
        binding.ivToggleConfirmPassword.setOnClickListener {
            confirmacionVisible = !confirmacionVisible
            alternarPassword(
                binding.etConfirmPassword,
                binding.ivToggleConfirmPassword,
                confirmacionVisible
            )
        }

        binding.btnSavePassword.setOnClickListener {
            if (AntiSpam.esDobleTap("btnSavePassword")) return@setOnClickListener
            guardarContrasena()
        }

        binding.etNewPassword.doAfterTextChanged { binding.tilNewPassword.error = null }
        binding.etConfirmPassword.doAfterTextChanged { binding.tilConfirmPassword.error = null }

        comprobarSesionRecuperacion()
    }

    /**
     * Sin sesión de recovery no se puede cambiar la contraseña: se avisa y se
     * vuelve al login (puede pasar si el usuario abre la pantalla a medias).
     */
    private fun comprobarSesionRecuperacion() {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.awaitInitialization()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                return@launch
            }

            if (SupabaseClient.client.auth.currentSessionOrNull() == null) {
                AreloveDialog.show(
                    this@ResetPasswordActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    getString(R.string.error_reset_session),
                    onPositive = { irALogin() }
                )
            }
        }
    }

    private fun guardarContrasena() {
        val nueva = binding.etNewPassword.text?.toString().orEmpty()
        val confirmacion = binding.etConfirmPassword.text?.toString().orEmpty()

        binding.tilNewPassword.error = null
        binding.tilConfirmPassword.error = null

        if (nueva.isEmpty()) {
            binding.tilNewPassword.error = getString(R.string.validation_required_field)
            return
        }

        if (nueva.length < 8) {
            binding.tilNewPassword.error = getString(R.string.validation_min_password_length)
            return
        }

        if (nueva != confirmacion) {
            binding.tilConfirmPassword.error = getString(R.string.error_passwords_dont_match)
            return
        }

        binding.progressBarReset.visibility = View.VISIBLE
        binding.btnSavePassword.isEnabled = false

        lifecycleScope.launch {
            try {
                // updateUser no existe en supabase-kt 2.1.0: el API es modifyUser
                SupabaseClient.client.auth.modifyUser {
                    password = nueva
                }
                AreloveDialog.show(
                    this@ResetPasswordActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.reset_password_success_msg),
                    onPositive = { irALogin() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e("ResetPasswordActivity", "Error guardando nueva contraseña")
                AreloveDialog.show(
                    this@ResetPasswordActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    getString(R.string.error_auth_failed)
                )
            } finally {
                binding.progressBarReset.visibility = View.GONE
                binding.btnSavePassword.isEnabled = true
            }
        }
    }

    /** Muestra u oculta la contraseña del campo indicado y actualiza el ojo. */
    private fun alternarPassword(campo: EditText, icono: ImageView, visible: Boolean) {
        campo.inputType = if (visible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        campo.transformationMethod = if (visible) {
            null
        } else {
            PasswordTransformationMethod.getInstance()
        }
        campo.setSelection(campo.text?.length ?: 0)
        icono.alpha = if (visible) 1.0f else 0.4f
    }

    /**
     * Vuelve al inicio de sesión sin dejar esta pantalla en el historial.
     * La sesión cambió (recuperación), así que se olvida el rol admin cacheado.
     */
    private fun irALogin() {
        AdminChecker.invalidarCache()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }
}
