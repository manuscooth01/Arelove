package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LogHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentMatchesListBinding
import com.arelove.app.databinding.ItemMatchBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import java.time.Instant

/**
 * Fragment que muestra los matches (usuarios con los que tengo match mutuo).
 */
class MatchesListFragment : Fragment() {

    private var _binding: FragmentMatchesListBinding? = null
    private val binding get() = _binding!!

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MatchesAdapter

    private var currentUserId: String? = null
    private val matchesList = mutableListOf<MatchItem>()
    private val profilesCache = hashMapOf<String, ProfileWithPhoto>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMatchesListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = binding.matchesRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = MatchesAdapter()
        recyclerView.adapter = adapter

        showEmptyState(true)
        cargarMatches()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyStateLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.matchesRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun cargarMatches() {
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

                val matchesFilas1 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user1", userId) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val matchesFilas2 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user2", userId) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val allMatches = (matchesFilas1 + matchesFilas2).distinctBy { fila ->
                    (fila["id"] as? JsonPrimitive)?.content
                }

                matchesList.clear()
                val otherUserIds = mutableSetOf<String>()

                allMatches.forEach { fila ->
                    val matchId = (fila["id"] as? JsonPrimitive)?.content ?: return@forEach
                    val user1 = (fila["user1"] as? JsonPrimitive)?.content ?: return@forEach
                    val user2 = (fila["user2"] as? JsonPrimitive)?.content ?: return@forEach
                    val createdAt = (fila["created_at"] as? JsonPrimitive)?.content ?: return@forEach

                    val otherUserId = if (user1 == userId) user2 else user1
                    otherUserIds.add(otherUserId)

                    matchesList.add(
                        MatchItem(
                            matchId = matchId,
                            user1 = user1,
                            user2 = user2,
                            createdAt = createdAt,
                            otherUserId = otherUserId
                        )
                    )
                }

                if (otherUserIds.isNotEmpty()) {
                    cargarPerfilesIndividuales(otherUserIds)
                } else {
                    b.progressBar.visibility = View.GONE
                    showEmptyState(matchesList.isEmpty())
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
} catch (e: Exception) {
                b.progressBar.visibility = View.GONE
                LogHelper.e("MatchesListFragment", "Error cargando matches")
                mostrarError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
                throw e
            } catch (e: Exception) {
                LogHelper.e("MatchesListFragment", "Error cargando perfiles")
            }
        }
    }

    private fun cargarPerfilesIndividuales(userIds: Set<String>) {
        lifecycleScope.launch {
            try {
                profilesCache.clear()
                userIds.forEach { uid ->
                    // Perfil ajeno: vista segura sin lat/lng/birthdate (VUL-01)
                    val fila = SupabaseClient.client.postgrest["profiles_publicos"].select(
                        columns = Columns.list("id", "nickname", "age", "location", "verified")
                    ) {
                        filter { eq("id", uid) }
                        filter { eq("profile_completed", true) }
                        limit(1)
                    }.decodeSingleOrNull<JsonObject>() ?: return@forEach

                    val id = textoDe(fila, "id") ?: return@forEach
                    val nickname = textoDe(fila, "nickname") ?: return@forEach
                    val age = fila["age"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                    val location = textoDe(fila, "location")
                    val verified = fila["verified"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

                    // Primera foto del perfil
                    val fotoFila = SupabaseClient.client.postgrest["media"].select(
                        columns = Columns.list("url")
                    ) {
                        filter { eq("user_id", uid) }
                        filter { eq("type", "profile") }
                        order("created_at", Order.ASCENDING)
                        limit(1)
                    }.decodeList<JsonObject>().firstOrNull()
                    val fotoUrl = fotoFila?.let { textoDe(it, "url") }

                    profilesCache[id] = ProfileWithPhoto(
                        userId = id,
                        nickname = nickname,
                        age = age,
                        location = location,
                        verified = verified,
                        fotoUrl = fotoUrl
                    )
                }

                val b = _binding ?: return@launch
                b.progressBar.visibility = View.GONE
                adapter.notifyDataSetChanged()
                showEmptyState(matchesList.isEmpty())

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e("MatchesListFragment", "Error cargando perfiles")
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

    private fun mostrarError(mensaje: String) {
        val contexto = context ?: return
        _binding?.progressBar?.visibility = View.GONE
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            onPositive = { cargarMatches() }
        )
    }

    inner class MatchesAdapter :
        RecyclerView.Adapter<MatchesAdapter.MatchesViewHolder>() {

        inner class MatchesViewHolder(private val binding: ItemMatchBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(item: MatchItem, profilesCache: HashMap<String, ProfileWithPhoto>) {
                val profile = profilesCache[item.otherUserId]
                if (profile == null) {
                    binding.tvName.text = item.otherUserId.takeLast(4)
                    binding.tvMatchText.text = getString(R.string.likes_match_label)
                    binding.ivAvatar.setImageResource(R.drawable.ic_nav_profile)
                    return
                }

                cargarAvatar(profile.fotoUrl, binding.ivAvatar)
                binding.tvName.text = profile.nickname
                binding.tvMatchText.text = getString(R.string.likes_match_label)

                binding.btnOpenChat.setOnClickListener {
                    if (AntiSpam.esDobleTap("btnOpenChat")) return@setOnClickListener
                    abrirChat(item.user1, item.user2)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchesViewHolder {
            val binding = ItemMatchBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return MatchesViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MatchesViewHolder, position: Int) {
            holder.bind(matchesList[position], profilesCache)
        }

        override fun getItemCount(): Int = matchesList.size

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

    private fun abrirChat(user1: String, user2: String) {
        lifecycleScope.launch {
            try {
                val u1 = if (user1.compareTo(user2) < 0) user1 else user2
                val u2 = if (user1.compareTo(user2) < 0) user2 else user1

                val chatFilas = SupabaseClient.client.postgrest["chats"].select(
                    columns = Columns.list("id")
                ) {
                    filter { eq("user1", u1) }
                    filter { eq("user2", u2) }
                    limit(1)
                }.decodeList<JsonObject>()

                val chatId = (chatFilas.firstOrNull()?.get("id") as? JsonPrimitive)?.content
                if (chatId.isNullOrBlank()) {
                    mostrarError(getString(R.string.chat_error_not_found))
                    return@launch
                }

                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("chat_id", chatId)
                    putExtra("user1", u1)
                    putExtra("user2", u2)
                }
                startActivity(intent)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e("MatchesListFragment", "Error abriendo chat")
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

    data class MatchItem(
        val matchId: String,
        val user1: String,
        val user2: String,
        val createdAt: String,
        val otherUserId: String
    )

    data class ProfileWithPhoto(
        val userId: String,
        val nickname: String,
        val age: Int?,
        val location: String?,
        val verified: Boolean,
        val fotoUrl: String?
    )

    private fun textoDe(fila: JsonObject, clave: String): String? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.trim().ifEmpty { null }
    }
}