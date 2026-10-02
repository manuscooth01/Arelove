package com.arelove.app.admin

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityAdminBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Panel de administración (solo profiles.is_admin = true).
 *
 * Muestra cuatro tarjetas de acceso: verificaciones pendientes, usuarios,
 * reportes y estadísticas. Los contadores se cargan en paralelo al abrir;
 * la tarjeta de estadísticas pliega/despliega las métricas (llStats).
 *
 * El rol se comprueba ANTES de montar la pantalla y además en cada
 * pantalla hija (doble comprobación), nunca se guarda en el dispositivo.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Doble comprobación de admin ANTES de setContentView: si no lo es,
        // la pantalla ni siquiera se llega a montar.
        lifecycleScope.launch {
            if (!esAdminConfirmado()) {
                finish()
                return@launch
            }
            montarPantalla()
        }
    }

    /**
     * is_admin + sesión activa. AdminChecker ya hace awaitInitialization()
     * y devuelve false ante cualquier error; aquí solo reforzamos el check.
     */
    private suspend fun esAdminConfirmado(): Boolean = try {
        AdminChecker.esAdmin() &&
            SupabaseClient.client.auth.currentSessionOrNull() != null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo confirmar el rol de administrador", e)
        false
    }

    private fun montarPantalla() {
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configurarListeners()
        mostrarContadores(0, 0, 0)
        cargarEstadisticas()
    }

    private fun configurarListeners() {
        binding.btnCloseAdmin.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseAdmin")) return@setOnClickListener
            finish()
        }
        binding.cardVerifications.setOnClickListener {
            if (AntiSpam.esDobleTap("cardVerifications")) return@setOnClickListener
            startActivity(Intent(this, AdminVerifyActivity::class.java))
        }
        binding.cardUsers.setOnClickListener {
            if (AntiSpam.esDobleTap("cardUsers")) return@setOnClickListener
            startActivity(Intent(this, AdminUsersActivity::class.java))
        }
        binding.cardReports.setOnClickListener {
            if (AntiSpam.esDobleTap("cardReports")) return@setOnClickListener
            startActivity(Intent(this, AdminReportsActivity::class.java))
        }
        // Por ahora la tarjeta de estadísticas expande/oculta las métricas
        binding.cardStats.setOnClickListener {
            if (AntiSpam.esDobleTap("cardStats")) return@setOnClickListener
            binding.llStats.visibility =
                if (binding.llStats.visibility == View.VISIBLE) View.GONE
                else View.VISIBLE
        }
    }

    /** Pinta los tres contadores de las tarjetas. */
    private fun mostrarContadores(pendientes: Long, usuarios: Long, reportes: Long) {
        binding.tvVerificationsCount.text =
            getString(R.string.admin_card_verifications_count, pendientes)
        binding.tvUsersCount.text =
            getString(R.string.admin_card_users_count, usuarios)
        binding.tvReportsCount.text =
            getString(R.string.admin_card_reports_count, reportes)
    }

    /**
     * Los 7 counts en UNA sola corrutina, cada uno en Dispatchers.IO y
     * lanzados todos antes de esperar (paralelos).
     *
     * Total/completados/verificados se leen de profiles_publicos: sobre
     * profiles la RLS solo deja ver la propia fila, así que ahí saldría 1.
     * Cada count tolera su propio error (tabla o policy que aún no exista)
     * y devuelve 0: el panel no debe romperse por una métrica.
     */
    private fun cargarEstadisticas() {
        lifecycleScope.launch {
            try {
                val stats = coroutineScope {
                    val pendientes = async(Dispatchers.IO) { contarPerfilesPendientes() }
                    val usuarios = async(Dispatchers.IO) { contarPublicos() }
                    val completados = async(Dispatchers.IO) { contarCompletados() }
                    val matches = async(Dispatchers.IO) { contar("matches") }
                    val mensajes = async(Dispatchers.IO) { contar("messages") }
                    val reportes = async(Dispatchers.IO) { contar("reports") }
                    val verificados = async(Dispatchers.IO) { contarVerificados() }

                    PanelStats(
                        pendientes = pendientes.await(),
                        usuarios = usuarios.await(),
                        completados = completados.await(),
                        matches = matches.await(),
                        mensajes = mensajes.await(),
                        reportes = reportes.await(),
                        verificados = verificados.await()
                    )
                }

                mostrarContadores(stats.pendientes, stats.usuarios, stats.reportes)
                binding.tvStatTotalUsers.text = stats.usuarios.toString()
                binding.tvStatCompletedProfiles.text = stats.completados.toString()
                binding.tvStatMatches.text = stats.matches.toString()
                binding.tvStatMessages.text = stats.mensajes.toString()
                binding.tvStatVerified.text = stats.verificados.toString()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "No se pudieron cargar las estadísticas", e)
                AreloveDialog.show(
                    this@AdminActivity,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    getString(R.string.admin_load_error)
                )
            }
        }
    }

    /** SELECT count(*) FROM profiles WHERE selfie pendiente de revisión. */
    private suspend fun contarPerfilesPendientes(): Long =
        contar("profiles") {
            filter { filterNot("verification_photo_url", FilterOperator.IS, null) }
            filter { eq("verified", false) }
        }

    /** SELECT count(*) FROM profiles_publicos (todos los perfiles). */
    private suspend fun contarPublicos(): Long = contar("profiles_publicos")

    /** SELECT count(*) FROM profiles_publicos WHERE profile_completed. */
    private suspend fun contarCompletados(): Long =
        contar("profiles_publicos") {
            filter { eq("profile_completed", true) }
        }

    /** SELECT count(*) FROM profiles_publicos WHERE verified. */
    private suspend fun contarVerificados(): Long =
        contar("profiles_publicos") {
            filter { eq("verified", true) }
        }

    /**
     * SELECT count(*) sobre [tabla]. Un error (tabla inexistente, RLS sin
     * policy...) se registra en logcat y devuelve 0.
     */
    private suspend fun contar(
        tabla: String,
        filtros: PostgrestRequestBuilder.() -> Unit = {}
    ): Long = try {
        SupabaseClient.client.postgrest[tabla]
            .select(head = true) {
                filtros()
                count(Count.EXACT)
            }
            .countOrNull() ?: 0L
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo contar $tabla", e)
        0L
    }

    /** Métricas del panel tras los counts. */
    private data class PanelStats(
        val pendientes: Long,
        val usuarios: Long,
        val completados: Long,
        val matches: Long,
        val mensajes: Long,
        val reportes: Long,
        val verificados: Long
    )

    companion object {
        private const val TAG = "AdminActivity"
    }
}
