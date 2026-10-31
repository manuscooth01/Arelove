package com.arelove.app.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LogHelper
import com.arelove.app.PhotoUploadHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityAdminVerifyBinding
import com.arelove.app.databinding.ItemVerificationBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Panel admin: verificaciones de identidad pendientes.
 *
 * Lista los perfiles con selfie subida y verified = false. Para cada uno
 * firma la selfie (bucket verification, privado) y carga la primera foto
 * del bucket avatars. Aprobar marca verified = true (lo valida el trigger
 * trg_proteger_perfil); rechazar borra verification_photo_url.
 */
class AdminVerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminVerifyBinding
    private val verificaciones = mutableListOf<VerificacionItem>()
    private lateinit var adapter: VerificacionesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Doble comprobación de admin ANTES de montar la pantalla
        lifecycleScope.launch {
            if (!esAdminConfirmado()) {
                finish()
                return@launch
            }
            binding = ActivityAdminVerifyBinding.inflate(layoutInflater)
            setContentView(binding.root)
            adapter = VerificacionesAdapter()
            binding.rvVerifications.adapter = adapter
            configurarListeners()
            cargarVerificaciones()
        }
    }

    private suspend fun esAdminConfirmado(): Boolean = try {
        AdminChecker.esAdmin() &&
            SupabaseClient.client.auth.currentSessionOrNull() != null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LogHelper.e("AdminVerifyActivity", "No se pudo confirmar el rol de administrador")
        false
    }

    private fun configurarListeners() {
        binding.btnCloseAdminVerify.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseAdminVerify")) return@setOnClickListener
            finish()
        }
    }

    private fun mostrarEstadoVacio(vacio: Boolean) {
        binding.llEmpty.visibility = if (vacio) View.VISIBLE else View.GONE
        binding.rvVerifications.visibility = if (vacio) View.GONE else View.VISIBLE
    }

    /** Carga la lista pendiente con selfie firmada y foto de perfil. */
    private fun cargarVerificaciones() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            mostrarEstadoVacio(false)
            try {
                val pendientes = descargarPendientes()
                // Firma de selfies y fotos de perfil en paralelo
                val items = coroutineScope {
                    pendientes
                        .map { fila -> async(Dispatchers.IO) { completarItem(fila) } }
                        .awaitAll()
                }

                verificaciones.clear()
                verificaciones.addAll(items)
                adapter.notifyDataSetChanged()
                mostrarEstadoVacio(verificaciones.isEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.e("AdminVerifyActivity", "Error cargando las verificaciones pendientes")
                mostrarError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * SELECT id, nickname, age, verification_photo_url FROM profiles
     * WHERE verification_photo_url IS NOT NULL AND verified = false
     * ORDER BY created_at DESC
     */
    private suspend fun descargarPendientes(): List<VerificacionItem> {
        val filas = SupabaseClient.client.postgrest["profiles"].select(
            columns = Columns.list(
                "id",
                "nickname",
                "age",
                "verification_photo_url",
                "created_at"
            )
        ) {
            filter { filterNot("verification_photo_url", FilterOperator.IS, null) }
            filter { eq("verified", false) }
            order("created_at", Order.DESCENDING)
        }.decodeList<JsonObject>()

        return filas.mapNotNull { fila ->
            val id = (fila["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            VerificacionItem(
                id = id,
                nickname = (fila["nickname"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: "",
                edad = (fila["age"] as? JsonPrimitive)?.content?.toIntOrNull(),
                selfieUrl = (fila["verification_photo_url"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            )
        }
    }

    /** Firma la selfie (bucket privado) y carga la primera foto de perfil. */
    private suspend fun completarItem(item: VerificacionItem): VerificacionItem {
        val selfie = item.selfieUrl?.let { PhotoUploadHelper.urlFirmada("verification", pathDeVerification(it)) }
        return item.copy(
            selfieFirmada = selfie,
            fotoPerfilUrl = primeraFotoPerfil(item.id)
        )
    }

    /**
     * verification_photo_url guarda una URL pública; para firmarla solo
     * necesitamos el path dentro del bucket (lo que va tras /verification/).
     */
    private fun pathDeVerification(url: String): String {
        val marca = "/verification/"
        val indice = url.indexOf(marca)
        val path = if (indice >= 0) url.substring(indice + marca.length) else url
        return path.substringBefore('?')
    }

    /**
     * SELECT url FROM media WHERE user_id = ? AND type = 'profile'
     * ORDER BY created_at ASC LIMIT 1
     */
    private suspend fun primeraFotoPerfil(userId: String): String? = try {
        SupabaseClient.client.postgrest["media"].select(
            columns = Columns.list("url")
        ) {
            filter { eq("user_id", userId) }
            filter { eq("type", "profile") }
            order("created_at", Order.ASCENDING)
            limit(1)
        }.decodeList<JsonObject>()
            .firstOrNull()
            ?.let { (it["url"] as? JsonPrimitive)?.takeIf { foto -> foto.isString }?.content }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LogHelper.e("AdminVerifyActivity", "No se pudo cargar la foto de perfil")
        null
    }

    /** Aprobar (verified = true) o rechazar (borrar la selfie) y recargar. */
    private fun cambiarVerificacion(item: VerificacionItem, aprobar: Boolean) {
        lifecycleScope.launch {
            try {
                if (aprobar) {
                    // El trigger trg_proteger_perfil exige que quien cambia
                    // verified sea admin: aquí lo somos, así que pasa.
                    SupabaseClient.client.postgrest["profiles"].update(buildJsonObject {
                        put("verified", true)
                    }) {
                        filter { eq("id", item.id) }
                    }
                } else {
                    SupabaseClient.client.postgrest["profiles"].update(buildJsonObject {
                        put("verification_photo_url", JsonNull)
                    }) {
                        filter { eq("id", item.id) }
                    }
                }

                AreloveDialog.show(
                    this@AdminVerifyActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(if (aprobar) R.string.admin_approved_msg else R.string.admin_rejected_msg)
                )
                cargarVerificaciones()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.e("AdminVerifyActivity", "Error actualizando la verificación")
                mostrarError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            }
        }
    }

    private fun mostrarError(mensaje: String) {
        binding.progressBar.visibility = View.GONE
        AreloveDialog.show(
            this,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje
        )
    }

    /** Adaptador con selfie + foto de perfil y los botones Aprobar/Rechazar. */
    inner class VerificacionesAdapter :
        RecyclerView.Adapter<VerificacionesAdapter.VerificacionViewHolder>() {

        inner class VerificacionViewHolder(private val binding: ItemVerificationBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: VerificacionItem) {
                val edad = item.edad?.let { ", $it" } ?: ""
                binding.tvNameAge.text = "${item.nickname}$edad"

                if (item.selfieFirmada.isNullOrBlank()) {
                    binding.ivSelfie.setImageResource(R.drawable.ic_shield)
                } else {
                    binding.ivSelfie.load(item.selfieFirmada) {
                        placeholder(R.drawable.ic_shield)
                        error(R.drawable.ic_shield)
                    }
                }

                if (item.fotoPerfilUrl.isNullOrBlank()) {
                    binding.ivProfilePhoto.setImageResource(R.drawable.ic_nav_profile)
                } else {
                    binding.ivProfilePhoto.load(item.fotoPerfilUrl) {
                        placeholder(R.drawable.ic_nav_profile)
                        error(R.drawable.ic_nav_profile)
                    }
                }

                binding.btnAprobar.setOnClickListener {
                    if (AntiSpam.esDobleTap("btnAprobar")) return@setOnClickListener
                    cambiarVerificacion(item, aprobar = true)
                }
                binding.btnRechazar.setOnClickListener {
                    if (AntiSpam.esDobleTap("btnRechazar")) return@setOnClickListener
                    cambiarVerificacion(item, aprobar = false)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerificacionViewHolder {
            val binding = ItemVerificationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VerificacionViewHolder(binding)
        }

        override fun onBindViewHolder(holder: VerificacionViewHolder, position: Int) {
            holder.bind(verificaciones[position])
        }

        override fun getItemCount(): Int = verificaciones.size
    }

    /** Una fila pendiente de revisión. */
    data class VerificacionItem(
        val id: String,
        val nickname: String,
        val edad: Int?,
        val selfieUrl: String?,
        val selfieFirmada: String? = null,
        val fotoPerfilUrl: String? = null
    )

    companion object {
    }
}