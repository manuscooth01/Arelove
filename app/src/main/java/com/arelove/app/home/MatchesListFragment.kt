package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arelove.app.ErrorSanitizer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Fragment de Matches - lista de matches mutuos del usuario.
 */
class MatchesListFragment : Fragment() {

    private var _binding: FragmentMatchesListBinding? = null
    private val binding get() = _binding!!

    private val matchItems = mutableListOf<MatchItem>()
    private lateinit var adapter: MatchesAdapter
    private var loading = false
    private var currentUserId: String? = null

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
        configurarRecyclerView()
        cargarMatches()
    }

    override fun onResume() {
        super.onResume()
        if (!loading) {
            cargarMatches()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun configurarRecyclerView() {
        adapter = MatchesAdapter()
        binding.rvMatches.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMatches.adapter = adapter
    }

    private fun cargarMatches() {
        val b = _binding ?: return
        if (loading) return
        loading = true
        b.progressBar.visibility = View.VISIBLE
        b.llEmpty.visibility = View.GONE
        b.rvMatches.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                currentUserId = session?.user?.id
                if (currentUserId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }

                // SELECT id, user1, user2, created_at FROM matches
                // WHERE user1 = currentUserId OR user2 = currentUserId
                // ORDER BY created_at DESC
                val matchesFilas1 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user1", currentUserId!!) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val matchesFilas2 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user2", currentUserId!!) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val filasChats = (matchesFilas1 + matchesFilas2)
                    .distinctBy { (it["id"] as? JsonPrimitive)?.content.orEmpty() }
                    .sortedByDescending { (it["created_at"] as? JsonPrimitive)?.content.orEmpty() }

                val sinVista = _binding ?: return@launch
                sinVista.progressBar.visibility = View.GONE

                if (filasChats.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.rvMatches.visibility = View.GONE
                    return@launch
                }

                // Extraer IDs de los otros usuarios
                val otrosUserIds = filasChats.map { fila ->
                    val user1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                    val user2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                    if (user1 == currentUserId) user2 else user1
                }.distinct()

                if (otrosUserIds.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.rvMatches.visibility = View.GONE
                    return@launch
                }

                // Cargar perfiles de los otros usuarios en paralelo
                val perfiles = mutableMapOf<String, Pair<String?, String?>>()
                val perfilesJobs = mutableListOf<kotlinx.coroutines.Job>()

                for (otroId in otrosUserIds) {
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
                            Log.e("MatchesListFragment", "Error cargando perfil $otroId", e)
                        }
                    }
                    perfilesJobs.add(job)
                }

                // Esperar a que terminen todas las cargas de perfiles
                perfilesJobs.forEach { it.join() }

                val sinVista = _binding ?: return@launch

                // Ahora construir items para el adapter
                val matchItemsTemp = mutableListOf<MatchItem>()

                for (fila in filasChats) {
                    val matchId = (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                    val user1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                    val user2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                    val matchCreatedAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()

                    val otroId = if (user1 == currentUserId) user2 else user1
                    val perfil = perfiles[otroId] ?: "Usuario" to null
                    val nickname = perfil.first ?: "Usuario"
                    val fotoUrl = perfil.second

                    val item = MatchItem(
                        matchId = matchId,
                        otroUserId = otroId,
                        otroNickname = nickname,
                        otroFotoUrl = fotoUrl,
                        matchCreatedAt = matchCreatedAt
                    )
                    matchItemsTemp.add(item)
                }

                val sinVista = _binding ?: return@launch

                // Ordenar por fecha de match descendente (más recientes primero)
                matchItemsTemp.sortWith(compareByDescending<MatchItem> { it.matchCreatedAt })

                matchItems.clear()
                matchItems.addAll(matchItemsTemp)
                sinVista.runOnUiThread {
                    adapter.notifyDataSetChanged()
                    actualizarVistaVacia()
                }

            } catch (e: Exception) {
                Log.e("MatchesListFragment", "Error cargando matches", e)
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
        if (matchItems.isEmpty()) {
            b.llEmpty.visibility = View.VISIBLE
            b.rvMatches.visibility = View.GONE
        } else {
            b.llEmpty.visibility = View.GONE
            b.rvMatches.visibility = View.VISIBLE
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

    /** Adaptador para la lista de matches. */
    inner class MatchesAdapter : RecyclerView.Adapter<MatchesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMatchBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val match = matchItems[position]
            val b = holder.binding

            b.tvName.text = match.otroNickname
            b.tvTimestamp.text = formatearFecha(match.matchCreatedAt)

            // Avatar
            if (match.otroFotoUrl?.isNotBlank() == true) {
                b.ivAvatar.load(match.otroFotoUrl!!) { crossfade(true) }
            } else {
                b.ivAvatar.setImageResource(R.color.primary)
            }

            // Click en el match para abrir chat
            b.cvMatch.setOnClickListener {
                if (AntiSpam.esDobleTap("match_${match.matchId}")) return@setOnClickListener
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("chat_id", match.matchId)
                    putExtra("otro_user_id", match.otroUserId)
                    putExtra("otro_nickname", match.otroNickname)
                    putExtra("otro_foto_url", match.otroFotoUrl ?: "")
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = matchItems.size
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