package com.arelove.app

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityForgotPasswordBinding
import com.arelove.app.LogHelper
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

/**
 * Pantalla Recuperar contraseña (A-08).
 * Pide el correo y Supabase envía el enlace de restablecimiento, que abre la
 * app con el deep link com.arelove.app://auth/reset (ver MainActivity).
 */
class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseForgotPassword.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseForgotPassword")) return@setOnClickListener
            finish()
        }

        binding.btnBackFromReset.setOnClickListener {
            if (AntiSpam.esDobleTap("btnBackFromReset")) return@setOnClickListener
            finish()
        }

        binding.btnSendResetLink.setOnClickListener {
            if (AntiSpam.esDobleTap("btnSendResetLink")) return@setOnClickListener
            enviarEnlace()
        }

        binding.etEmail.doAfterTextChanged { binding.tilEmail.error = null }
    }

    private fun enviarEnlace() {
        val emailValue = binding.etEmail.text?.toString()?.trim().orEmpty()

        binding.tilEmail.error = null

        if (emailValue.isEmpty()) {
            binding.tilEmail.error = getString(R.string.validation_required_field)
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            binding.tilEmail.error = getString(R.string.validation_invalid_email)
            return
        }

        binding.progressBarForgot.visibility = View.VISIBLE
        binding.btnSendResetLink.isEnabled = false

        lifecycleScope.launch {
            try {
                // redirectUrl = deep link que MainActivity intercepta
                SupabaseClient.client.auth.resetPasswordForEmail(
                    emailValue,
                    SupabaseClient.REDIRECT_RECUPERACION
                )
                AreloveDialog.show(
                    this@ForgotPasswordActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.reset_link_sent_msg),
                    onPositive = { finish() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e("ForgotPasswordActivity", "Error enviando enlace de recuperación")
                AreloveDialog.show(
                    this@ForgotPasswordActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    getString(R.string.error_auth_failed)
                )
            } finally {
                binding.progressBarForgot.visibility = View.GONE
                binding.btnSendResetLink.isEnabled = true
            }
        }
    }
}
