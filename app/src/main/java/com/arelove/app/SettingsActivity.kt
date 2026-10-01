package com.arelove.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.databinding.ActivitySettingsBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth

/**
 * Pantalla de Ajustes de Arelove: cuenta (correo y eliminación) y ayuda.
 * La eliminación de cuenta se gestiona por correo (soporte@arelove.app)
 * porque la SDK de Supabase no permite borrar usuarios desde el cliente.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Correo de la cuenta (solo lectura)
        binding.tvSettingsEmail.text =
            SupabaseClient.client.auth.currentSessionOrNull()?.user?.email.orEmpty()

        // Pie con la versión de la app
        binding.tvSettingsVersion.text = getString(
            R.string.settings_version
        ) + " " + getString(R.string.settings_version_value)

        binding.btnCloseSettings.setOnClickListener { finish() }

        binding.cardSettingsPrivacy.setOnClickListener {
            if (AntiSpam.esDobleTap("cardSettingsPrivacy")) return@setOnClickListener
            abrirLegal("privacidad")
        }

        binding.cardSettingsFaq.setOnClickListener {
            if (AntiSpam.esDobleTap("cardSettingsFaq")) return@setOnClickListener
            abrirLegal("faq")
        }

        binding.cardDeleteAccount.setOnClickListener {
            if (AntiSpam.esDobleTap("cardDeleteAccount")) return@setOnClickListener
            AreloveDialog.show(
                this,
                AreloveDialog.Tipo.WARNING,
                getString(R.string.settings_delete_account),
                getString(R.string.settings_delete_account_msg)
            )
        }
    }

    /** Abre la pantalla legal con el tipo indicado. */
    private fun abrirLegal(tipo: String) {
        startActivity(
            Intent(this, LegalActivity::class.java).apply {
                putExtra(LegalActivity.EXTRA_TIPO, tipo)
            }
        )
    }
}
