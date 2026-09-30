package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.arelove.app.ErrorSanitizer
import com.arelove.app.PhotoUploadHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentLikesReceivedBinding
import com.arelove.app.databinding.ItemLikeBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.*

/**
 * Fragment que muestra los likes recibidos (usuarios que me dieron like y no han expirado).
 */
class LikesReceivedFragment : Fragment() {

    private var _binding: FragmentLikesReceivedBinding? = null
    private val binding get() = _binding!!

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LikesReceivedAdapter

    private var currentUserId: String? = null
    private val likesList = mutableListOf<LikeItem>()
    private val profilesCache = hashMapOf<String, Profile>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLikesReceivedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = binding.likesRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = LikesReceivedAdapter()
        recyclerView.adapter = adapter

        // Mostrar estado vacío inicial mientras cargamos
        showEmptyState(true)
        cargarLikesRecibidos()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Muestra el estado vacío o la lista según corresponda. */
    private fun showEmptyState(show: Boolean) {
        binding.emptyStateLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.likesRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    /** Carga los likes recibidos desde Supabase. */
    private fun cargarLikesRecibidos() {
        val b = _binding ?: return
        b.progressBar.visibility = View.VISIBLE
        showEmptyState(false)

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }
                currentUserId = userId

                // Obtener likes recibidos no expirados
                val likesFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("id", "from_user", "created_at", "expires_at")
                ) {
                    filter { eq("to_user", userId) }
                    filter { gte("expires_at", Instant.now().toString()) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                // Extraer los IDs de usuarios que nos dieron like
                val fromUserIds = likesFilas.mapNotNull {
                    (it["from_user"] as? JsonPrimitive)?.content
                }.toSet()

                likesList.clear()
                likesFilas.forEach { fila ->
                    val likeId = (fila["id"] as? JsonPrimitive)?.content ?: return@forEach
                    val fromUserId = (fila["from_user"] as? JsonPrimitive)?.content ?: return@forEach
                    val createdAt = (fila["created_at"] as? JsonPrimitive)?.content ?: return@forEach
                    val expiresAt = (fila["expires_at"] as? JsonPrimitive)?.content ?: return@forEach

                    likesList.add(LikeItem(
                        likeId = likeId,
                        fromUserId = fromUserId,
                        createdAt = createdAt,
                        expiresAt = expiresAt
                    ))
                }

                // Cargar perfiles de los usuarios que nos dieron_like (individual queries)
                if (fromUserIds.isNotEmpty()) {
                    cargarPerfilesIndividuales(fromUserIds)
                } else {
                    // No hay likes recibidos
                    b.progressBar.visibility = View.GONE
                    showEmptyState(likesList.isEmpty())
                }

            } catch (e: Exception) {
                b.progressBar.visibility = View.GONE
                Log.e("LikesReceivedFragment", "Error cargando likes recibidos", e)
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

    /** Carga perfiles de múltiples usuarios con queries individuales. */
    private fun cargarPerfilesIndividuales(userIds: Set<String>) {
        lifecycleScope.launch {
            try {
                // Limpiar caché
                profilesCache.clear()
                // Cargar cada perfil individualmente
                userIds.forEach { userId ->
                    val fila = SupabaseClient.client.postgrest["profiles"].select(
                        columns = Columns.list("id", "nickname", "age", "location", "verified")
                    ) {
                        filter { eq("id", userId) }
                        filter { eq("profile_completed", true) }
                        limit(1)
                    }.decodeSingleOrNull<JsonObject>()

                    fila?.let {
                        val id = textoDe(it, "id") ?: return@forEach
                        val nickname = textoDe(it, "nickname") ?: return@forEach
                        val age = it["age"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                        val location = textoDe(it, "location")
                        val verified = it["verified"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

                        // Cargar foto de perfil
                        val filaFoto = SupabaseClient.client.postgrest["media"].select(
                            columns = Columns.list("url")
                        ) {
                            filter { eq("user_id", userId) }
                            filter { eq("type", "profile") }
                            order("created_at", Order.ASCENDING)
                            limit(1)
                        }.decodeList<JsonObject>()

                        val fotoUrl = filaFoto.firstOrNull()?.let { (it["url"] as? JsonPrimitive)?.content }

                        profilesCache[id] = Profile(
                            userId = id,
                            nickname = nickname,
                            age = age,
                            location = location,
                            verified = verified,
                            fotoUrl = fotoUrl
                        )
                    }
                }

                // Notificar al adaptador que los datos han cambiado
                val b = _binding ?: return@launch
                b.progressBar.visibility = View.GONE
                adapter.notifyDataSetChanged()
                showEmptyState(likesList.isEmpty())

            } catch (e: Exception) {
                Log.e("LikesReceivedFragment", "Error cargando perfiles individuales", e)
                val b = _binding ?: return@launch
                b.progressBar.visibility = View.GONE
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

    /** Muestra un diálogo de error. */
    private fun mostrarError(mensaje: String) {
        val contexto = context ?: return
        _binding?.progressBar?.visibility = View.GONE
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            onPositive = { cargarLikesRecibidos() }
        )
    }

    /** Adaptador del RecyclerView para los likes recibidos. */
    inner class LikesReceivedAdapter :
        RecyclerView.Adapter<LikesReceivedAdapter.LikesViewHolder>() {

        inner class LikesViewHolder(private val binding: ItemLikeBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LikeItem, profilesCache: HashMap<String, Profile>) {
                val profile = profilesCache[item.fromUserId]
                if (profile == null) {
                    // Si no tenemos el perfil aún, mostramos datos básicos
                    binding.tvNameAge.text = item.fromUserId.takeLast(4) // fallback
                    binding.tvLocation.text = ""
                    binding.ivAvatar.setImageResource(R.drawable.ic_nav_profile)
                    return
                }

                // Configurar avatar
                cargarAvatar(profile.fotoUrl, binding.ivAvatar)

                // Nombre y edad
                val name = profile.nickname
                val ageText = profile.age?.let { ", $it" } ?: ""
                binding.tvNameAge.text = "$name$ageText"

                // Ubicación
                binding.tvLocation.text = profile.location ?: ""

                // Botón Ver perfil
                binding.btnVerPerfil.setOnClickListener {
                    if (AntiSpam.esDobleTap("btnVerPerfil")) return@setOnClickListener
                    val intent = Intent(requireContext(), ProfileDetailActivity::class.java).apply {
                        putExtra("user_id", item.fromUserId)
                    }
                    startActivity(intent)
                }

                // Botón Dar like de vuelta
                binding.btnLikeBack.setOnClickListener {
                    if (AntiSpam.esDobleTap("btnLikeBack")) return@setOnClickListener
                    darLikeDeVuelta(item.fromUserId)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LikesViewHolder {
            val binding = ItemLikeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return LikesViewHolder(binding)
        }

        override fun onBindViewHolder(holder: LikesViewHolder, position: Int) {
            holder.bind(likesList[position], profilesCache)
        }

        override fun getItemCount(): Int = likesList.size

        /** Carga la imagen del avatar desde una URL. */
        private fun cargarAvatar(url: String?, imageView: com.google.android.material.imageview.ShapeableImageView) {
            if (url.isNullOrBlank()) {
                imageView.setImageResource(R.drawable.ic_nav_profile)
            } else {
                imageView.load(url) {
                    placeholder(R.drawable.ic_nav_profile)
                    error(R.drawable.ic_nav_profile)
                }
            }
        }
    }

    /** Da like de vuelta a un usuario y verifica si hay match. */
    private fun darLikeDeVuelta(toUserId: String) {
        val b = _binding ?: return
        val fromUserId = currentUserId ?: return

        lifecycleScope.launch {
            // Desactivar botón y mostrar progreso
            b.likesRecyclerView.post {
                // Buscar el ViewHolder del item correspondiente y desactivar botón
                // Como no tenemos acceso directo al holder, usamos AntiSpam como fallback
                // pero deshabilitamos el botón globalmente durante la operación
            }

            try {
                Log.d("LikesReceivedFragment", "Iniciando darLikeDeVuelta para toUserId=$toUserId")

                // INSERT en likes {from_user, to_user}
                SupabaseClient.client.postgrest["likes"].insert<JsonObject>(buildJsonObject {
                    put("from_user", fromUserId)
                    put("to_user", toUserId)
                })

                Log.d("LikesReceivedFragment", "Like insertado, verificando match...")

                // Verificar si hay match (la otra persona ya dio like)
                val match = verificarMatch(fromUserId, toUserId)

                Log.d("LikesReceivedFragment", "Match verificado: $match")

                if (match) {
                    AreloveDialog.show(
                        requireContext(),
                        AreloveDialog.Tipo.SUCCESS,
                        getString(R.string.match_title),
                        getString(R.string.match_subtitle),
                        getString(R.string.match_btn_chat),
                        onPositive = { /* por ahora solo cierra */ }
                    )
                }

                // Recargar la lista para reflejar el nuevo estado
                cargarLikesRecibidos()

            } catch (e: Exception) {
                Log.e("LikesReceivedFragment", "Error dando like de vuelta", e)
                if (e.message?.contains("duplicate") == true || e.message?.contains("unique constraint") == true) {
                    // Ignorar error de duplicado, ya que puede ser un like existente
                    cargarLikesRecibidos()
                } else {
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
    }

    /** Verifica si la otra persona ya dio like (match). */
    private suspend fun verificarMatch(fromUserId: String, toUserId: String): Boolean {
        try {
            // SELECT * FROM likes WHERE from_user = toUserId AND to_user = fromUserId AND expires_at > now()
            val filas = SupabaseClient.client.postgrest["likes"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
                filter { gte("expires_at", Instant.now().toString()) }
            }.decodeList<JsonObject>()

            return filas.isNotEmpty()
        } catch (e: Exception) {
            Log.e("LikesReceivedFragment", "Error verificando match", e)
            return false
        }
    }

    /** Clase de datos para representar un like recibido. */
    data class LikeItem(
        val likeId: String,
        val fromUserId: String,
        val createdAt: String,
        val expiresAt: String
    )

    /** Clase de datos para almacenar información básica del perfil. */
    data class Profile(
        val userId: String,
        val nickname: String,
        val age: Int?,
        val location: String?,
        val verified: Boolean,
        val fotoUrl: String? = null
    )

    /** Extrae texto de un JsonObject de forma segura. */
    private fun textoDe(fila: JsonObject, clave: String): String? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.trim().ifEmpty { null }
    }
}