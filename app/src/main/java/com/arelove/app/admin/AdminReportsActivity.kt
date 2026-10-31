package com.arelove.app.admin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.LogHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityAdminReportsBinding
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Panel admin: reportes (placeholder).
 *
 * Cuenta las filas de reports para elegir el texto: 0 → "No hay reportes
 * pendientes"; con reportes → "Próximamente: gestión de reportes".
 */
class AdminReportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminReportsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Doble comprobación de admin ANTES de montar la pantalla
        lifecycleScope.launch {
            if (!esAdminConfirmado()) {
                finish()
                return@launch
            }
            binding = ActivityAdminReportsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            configurarListeners()
            consultarReportes()
        }
    }

    private suspend fun esAdminConfirmado(): Boolean = try {
        AdminChecker.esAdmin() &&
            SupabaseClient.client.auth.currentSessionOrNull() != null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LogHelper.e("AdminReportsActivity", "No se pudo confirmar el rol de administrador")
        false
    }

    private fun configurarListeners() {
        binding.btnCloseAdminReports.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseAdminReports")) return@setOnClickListener
            finish()
        }
    }

    /** SELECT count(*) FROM reports y elección del texto del estado vacío. */
    private fun consultarReportes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val total = try {
                SupabaseClient.client.postgrest["reports"]
                    .select(head = true) {
                        count(Count.EXACT)
                    }
                    .countOrNull() ?: 0L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.e("AdminReportsActivity", "No se pudieron contar los reportes")
                null
            }

            runOnUiThread {
                binding.tvReportsMessage.text = when (total) {
                    null -> getString(R.string.admin_load_error)
                    0L -> getString(R.string.admin_reports_empty)
                    else -> getString(R.string.admin_reports_coming_soon)
                }
            }
        }
    }

    companion object {
    }
}