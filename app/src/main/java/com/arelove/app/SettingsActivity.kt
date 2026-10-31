package com.arelove.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.databinding.ActivitySettingsBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan-tennert.supabase.gotrue.auth

/**
 * Pantalla de Ajustes de Arelove: cuenta (correo) y eliminación de cuenta.
 * La eliminación se gestiona por correo (soporte@arelove.app) porque la SDK
 * de Supabase no permite borrar usuarios desde el cliente. Lo legal
 * (términos, privacidad, comunidad, FAQ) vive solo en el perfil.
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
}
