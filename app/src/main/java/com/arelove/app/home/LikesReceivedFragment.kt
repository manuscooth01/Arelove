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
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentLikesReceivedBinding
import com.arelove.app.databinding.ItemLikeBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fragment de Likes recibidos - muestra quienes te dieron like.
 */
class LikesReceivedFragment : Fragment() {

    private var _binding: FragmentLikesReceivedBinding? = null
    private val binding get() = _binding!!

    private val likeItems = mutableListOf<LikeItem>()
    private lateinit var adapter: LikesAdapter
    private var loading = false
    private var currentUserId: String? = null

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
        configurarRecyclerView()
        cargarLikes()
    }

    override fun onResume() {
        super.onResume()
        if (!loading) {
            cargarLikes()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun configurarRecyclerView() {
        adapter = LikesAdapter()
        binding.rvLikes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLikes.adapter = adapter
    }

    private fun cargarLikes() {
        val b = _binding ?: return
        if (loading) return
        loading = true
        b.progressBar.visibility = View.VISIBLE
        b.llEmpty.visibility = View.GONE
        b.rvLikes.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }
                currentUserId = userId

                // SELECT id, from_user, created_at, expires_at FROM likes
                // WHERE to_user = currentUserId AND expires_at > now()
                // ORDER BY created_at DESC
                val filas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("id", "from_user", "created_at", "expires_at")
                ) {
                    filter { eq("to_user", userId) }
                    filter { gte("expires_at", Instant.now().toString()) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                sinVista.progressBar.visibility = View.GONE

                if (filas.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.rvLikes.visibility = View.GONE
                    return@launch
                }

                // Extraer IDs de usuarios que dieron like
                val fromUserIds = filas.mapNotNull { (it["from_user"] as? JsonPrimitive)?.content }.distinct()

                if (otrosUserIds.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.rvLikes.visibility = View.GONE
                    return@launch
                }

                // Cargar perfiles de los usuarios que dieron like
                val perfiles = mutableMapOf<String, Pair<String?, String?>>()
                val perfilesJobs = mutableListOf<kotlinx.coroutines.Job>()

                for (otroId in fromUserIds) {
                    val job = lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val filasPerfil = SupabaseClient.client.postgrest["profiles"].select(
                                columns = Columns.list("nickname", "age", "location")
                            ) {
                                filter { eq("id", otroId) }
                                limit(1)
                            }.decodeList<JsonObject>()

                            val fotoMedia = SupabaseClient.client.postgrest["media"].select(
                                columns = Columns.list("url")
                            ) {
                                filter { eq("user_id", otroId) }
                                filter { eq("type", "profile") }
                                order("created_at", Order.ASCENDING)
                                limit(1)
                            }.decodeList<JsonObject>()

                            val nickname = filasPerfil.firstOrNull()?.let { (it["nickname"] as? JsonPrimitive)?.content.orEmpty() } ?: "Usuario"
                            val age = filasPerfil.firstOrNull()?.let { (it["age"] as? JsonPrimitive)?.content?.toIntOrNull() }
                            val location = (filasPerfil.firstOrNull()?["location"] as? JsonPrimitive)?.content.orEmpty()
                            val fotoUrl = fotoMedia.firstOrNull()?.let { (it["url"] as? JsonPrimitive)?.content.orEmpty() }

                            withContext(Dispatchers.Main) {
                                perfiles[otroId] = nickname to fotoUrl
                            }
                        } catch (e: Exception) {
                            Log.e("LikesReceivedFragment", "Error cargando perfil $otroId", e)
                        }
                    }
                    perfilesJobs.add(job)
                }

                // Esperar a que terminen todas las cargas de perfiles
                perfilesJobs.forEach { it.join() }

                val sinVista = _binding ?: return@launch

                // Construir items para el adapter
                val itemsTemp = mutableListOf<LikeItem>()
                for (fila in filas) {
                    val likeId = (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                    val fromUserId = (fila["from_user"] as? JsonPrimitive)?.content.orEmpty()
                    val createdAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()
                    val expiresAt = (fila["expires_at"] as? JsonPrimitive)?.content.orEmpty()

                    val perfil = perfiles[fromUserId] ?: "Usuario" to null
                    val nickname = perfil.first ?: "Usuario"
                    val fotoUrl = perfil.second

                    // Para la edad, necesitamos cargarla del perfil
                    val age: Int? = null // TODO: cargar edad si se necesita

                    val item = LikeItem(
                        likeId = likeId,
                        fromUserId = fromUserId,
                        nickname = nickname,
                        age = age,
                        location = perfil.second?.let { "" } ?: "", // TODO: cargar location
                        fotoUrl = fotoUrl,
                        createdAt = createdAt,
                        expiresAt = expiresAt
                    )
                    itemsTemp.add(item)
                }

                // Ordenar por fecha descendente (más recientes primero)
                itemsTemp.sortWith(compareByDescending<LikeItem> { it.createdAt })

                val sinVista = _binding ?: return@launch
                likeItems.clear()
                likeItems.addAll(itemsTemp)
                adapter.notifyDataSetChanged()
                actualizarVistaVacia()

            } catch (e: Exception) {
                Log.e("LikesReceivedFragment", "Error cargando likes", e)
                val sinVista = _binding ?: return@launch
                sinVista.progressBar.visibility = View.GONE
                mostrarError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                loading = false
            }
        }
    }

    private fun actualizarVistaVacia() {
        val b = _binding ?: return
        if (likeItems.isEmpty()) {
            b.llEmpty.visibility = View.VISIBLE
            b.rvLikes.visibility = View.GONE
        } else {
            b.llEmpty.visibility = View.GONE
            b.rvLikes.visibility = View.VISIBLE
        }
    }

    private fun mostrarError(mensaje: String) {
        val contexto = context ?: return
        _binding?.progressBar?.visibility = View.GONE
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            onPositive = { cargarLikes() }
        )
    }

    /** Adaptador para la lista de likes recibidos. */
    inner class LikesAdapter : RecyclerView.Adapter<LikesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemLikeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLikeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = likeItems[position]
            val b = holder.binding

            b.tvName.text = buildNameString(item.nickname, item.age)
            b.tvLocation.text = item.location
            b.tvTimestamp.text = formatearFecha(item.createdAt)

            // Avatar
            if (item.fotoUrl?.isNotBlank() == true) {
                b.ivAvatar.load(item.fotoUrl!!) { crossfade(true) }
            } else {
                b.ivAvatar.setImageResource(R.color.primary)
            }

            // Click en Ver perfil
            b.btnViewProfile.setOnClickListener {
                if (AntiSpam.esDobleTap("view_profile_${item.fromUserId}")) return@setOnClickListener
                val intent = Intent(requireContext(), ProfileDetailActivity::class.java).apply {
                    putExtra("user_id", item.fromUserId)
                }
                startActivity(intent)
            }

            // Click en Dar like de vuelta
            b.btnLikeBack.setOnClickListener {
                if (AntiSpam.esDobleTap("like_back_${item.fromUserId}")) return@setOnClickListener
                darLikeDeVuelta(item.fromUserId, item.likeId)
            }
        }

        override fun getItemCount(): Int = likeItems.size
    }

    private fun buildNameString(nickname: String, age: Int?): String {
        return "$nickname${age?.let { ", $it" } ?: ""}"
    }

    private fun formatearFecha(fechaIso: String?): String {
        if (fechaIso?.isBlank() == true) return ""
        try {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
            val date = formatter.parse(fechaIso)
            val now = java.util.Date()
            val diffMs = now.time - date!!.time
            val diffMins = diffMs / (1000 * 60)
            val diffHours = diffMins / 60
            val diffDays = diffHours / 24

            return when {
                diffMins < 1 -> "Ahora"
                diffMins < 60 -> "${diffMins} min"
                diffHours < 24 -> "${diffHours} h"
                diffDays < 7 -> "${diffDays} d"
                else -> {
                    val outFormatter = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                    outFormatter.format(date!!)
                }
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun darLikeDeVuelta(fromUserId: String, originalLikeId: String) {
        val currentUserId = currentUserId ?: return
        val b = _binding ?: return

        lifecycleScope.launch {
            try {
                // INSERT en likes {from_user: currentUserId, to_user: fromUserId}
                SupabaseClient.client.postgrest["likes"].insert<JsonObject>(buildJsonObject {
                    put("from_user", currentUserId)
                    put("to_user", fromUserId)
                })

                // Verificar si hay match recíproco
                val match = verificarMatch(currentUserId, fromUserId)

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

                // Recargar la lista
                cargarLikes()
            } catch (e: Exception) {
                Log.e("LikesReceivedFragment", "Error dando like de vuelta", e)
                if (e.message?.contains("duplicate") == true || e.message?.contains("unique constraint") == true) {
                    // Ya existía el like, solo recargar
                    cargarLikes()
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
            val filas = SupabaseClient.client.postgrest["likes"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
                filter { gte("expires_at", Instant.now().toString()) }
            }.decodeList<JsonObject>()

            if (filas.isNotEmpty()) {
                crearMatch(fromUserId, toUserId)
                actualizarLikeMutuo(fromUserId, toUserId)
                return true
            }
        } catch (e: Exception) {
            Log.e("LikesReceivedFragment", "Error verificando match", e)
        }
        return false
    }

    /** Crea la fila en matches y en chats. */
    private suspend fun crearMatch(user1: String, user2: String) {
        try {
            val u1 = if (user1.compareTo(user2) < 0) user1 else user2
            val u2 = if (user1.compareTo(user2) < 0) user2 else user1
            SupabaseClient.client.postgrest["matches"].insert<JsonObject>(buildJsonObject {
                put("user1", u1)
                put("user2", u2)
            })

            val chatExiste1 = SupabaseClient.client.postgrest["chats"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("user1", u1); eq("user2", u2) }
                limit(1)
            }.decodeList<JsonObject>()

            val chatExiste2 = SupabaseClient.client.postgrest["chats"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("user1", u2); eq("user2", u1) }
                limit(1)
            }.decodeList<JsonObject>()

            val chatExiste = (chatExiste1 + chatExiste2).isNotEmpty()

            if (!chatExiste) {
                SupabaseClient.client.postgrest["chats"].insert<JsonObject>(buildJsonObject {
                    put("user1", u1)
                    put("user2", u2)
                })
            }
        } catch (e: Exception) {
            Log.e("LikesReceivedFragment", "Error creando match o chat", e)
        }
    }

    /** Actualiza el like mutuo poniendo expires_at = NULL. */
    private suspend fun actualizarLikeMutuo(fromUserId: String, toUserId: String) {
        try {
            SupabaseClient.client.postgrest["likes"].update<JsonObject>(buildJsonObject {
                put("expires_at", JsonNull)
            }) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
            }
        } catch (e: Exception) {
            Log.e("LikesReceivedFragment", "Error actualizando like mutuo", e)
        }
    }

    private fun formatearFecha(fechaIso: String?): String {
        if (fechaIso?.isBlank() == true) return ""
        try {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
            val date = formatter.parse(fechaIso)
            val now = java.util.Date()
            val diffMs = now.time - date!!.time
            val diffMins = diffMs / (1000 * 60)
            val diffHours = diffMins / 60
            val diffDays = diffHours / 24

            return when {
                diffMins < 1 -> "Ahora"
                diffMins < 60 -> "${diffMins} min"
                diffHours < 24 -> "${diffHours} h"
                diffDays < 7 -> "${diffDays} d"
                else -> {
                    val outFormatter = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                    outFormatter.format(date!!)
                }
            }
        } catch (e: Exception) {
            return ""
        }
    }
}