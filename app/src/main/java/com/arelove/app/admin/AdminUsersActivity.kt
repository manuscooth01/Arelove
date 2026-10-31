package com.arelove.app.admin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.LogHelper
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityAdminUsersBinding
import com.arelove.app.util.AntiSpam
import io.github.jan-tennert.supabase.gotrue.auth
import kotlinx.coroutines.launch

/**
 * Panel admin: listado de usuarios (placeholder).
 *
 * El layout ya pinta el estado vacío "Próximamente"; la gestión real de
 * usuarios llegará en una iteración futura.
 */
class AdminUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminUsersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Doble comprobación de admin ANTES de montar la pantalla
        lifecycleScope.launch {
            if (!esAdminConfirmado()) {
                finish()
                return@launch
            }
            binding = ActivityAdminUsersBinding.inflate(layoutInflater)
            setContentView(binding.root)
            configurarListeners()
        }
    }

    private suspend fun esAdminConfirmado(): Boolean = try {
        AdminChecker.esAdmin() &&
            SupabaseClient.client.auth.currentSessionOrNull() != null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LogHelper.e("AdminUsersActivity", "No se pudo confirmar el rol de administrador")
        false
    }

    private fun configurarListeners() {
        binding.btnCloseAdminUsers.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseAdminUsers")) return@setOnClickListener
            finish()
        }
    }

    companion object {
    }
}