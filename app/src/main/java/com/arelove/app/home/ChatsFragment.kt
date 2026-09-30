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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentChatsBinding
import com.arelove.app.databinding.ItemChatBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.gt
import io.github.jan.supabase.postgrest.query.or
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fragment de Chats - lista de conversaciones del usuario.
 */
class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private val chatItems = mutableListOf<ChatItem>()
    private lateinit var adapter: ChatsAdapter
    private var loading = false
    private var currentUserId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurarRecyclerView()
        configurarSwipeRefresh()
        cargarChats()
    }

    override fun onResume() {
        super.onResume()
        if (!loading) {
            cargarChats()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun configurarRecyclerView() {
        adapter = ChatsAdapter()
        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChats.adapter = adapter
    }

    private fun configurarSwipeRefresh() {
        binding.srlChats.setOnRefreshListener {
            cargarChats()
        }
        binding.srlChats.setColorSchemeResources(R.color.primary)
    }

    private fun cargarChats() {
        val b = _binding ?: return
        if (loading) return
        loading = true
        b.srlChats.isRefreshing = true
        b.llEmpty.visibility = View.GONE
        b.rvChats.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                currentUserId = session?.user?.id
                if (currentUserId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }

                // SELECT id, user1, user2 FROM chats WHERE user1 = currentUserId OR user2 = currentUserId
                val filasChats = SupabaseClient.client.postgrest["chats"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter {
                        or(
                            { eq("user1", currentUserId!!) },
                            { eq("user2", currentUserId!!) }
                        )
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                sinVista.srlChats.isRefreshing = false

                if (filasChats.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.rvChats.visibility = View.GONE
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
                    sinVista.rvChats.visibility = View.GONE
                    return@launch
                }

                // Cargar perfiles de los otros usuarios en paralelo
                val perfiles = mutableMapOf<String, Pair<String?, String?>>()
                val perfilesJobs = mutableListOf<kotlinx.coroutines.Job>()

                for (otroId in otrosUserIds) {
                    val job = lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val filasPerfil = SupabaseClient.client.postgrest["profiles"].select(
                                columns = Columns.list("nickname")
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
                            val fotoUrl = fotoMedia.firstOrNull()?.let { (it["url"] as? JsonPrimitive)?.content.orEmpty() }

                            withContext(Dispatchers.Main) {
                                perfiles[otroId] = nickname to fotoUrl
                            }
                        } catch (e: Exception) {
                            Log.e("ChatsFragment", "Error cargando perfil $otroId", e)
                        }
                    }
                    perfilesJobs.add(job)
                }

                // Esperar a que terminen todas las cargas de perfiles
                perfilesJobs.forEach { it.join() }

                val sinVista2 = _binding ?: return@launch

                // Ahora cargar último mensaje y no leídos para cada chat
                val chatItemsTemp = mutableListOf<ChatItem>()

                for (fila in filasChats) {
                    val chatId = (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                    val user1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                    val user2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                    val chatCreatedAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()

                    val otroId = if (user1 == currentUserId) user2 else user1
                    val perfil = perfiles[otroId] ?: "Usuario" to null
                    val nickname = perfil.first
                    val fotoUrl = perfil.second

                    // Último mensaje
                    val filasMensajes = SupabaseClient.client.postgrest["messages"].select(
                        columns = Columns.list("content", "created_at", "sender", "seen")
                    ) {
                        filter { eq("chat_id", chatId) }
                        order("created_at", Order.DESCENDING)
                        limit(1)
                    }.decodeList<JsonObject>()

                    var ultimoMensaje: String? = null
                    var ultimaFecha: String? = chatCreatedAt
                    var noLeidos = 0

                    if (filasMensajes.isNotEmpty()) {
                        val msg = filasMensajes[0]
                        ultimoMensaje = (msg["content"] as? JsonPrimitive)?.content.orEmpty()
                        ultimaFecha = (msg["created_at"] as? JsonPrimitive)?.content.orEmpty()

                        // Contar no leídos
                        val todasMsg = SupabaseClient.client.postgrest["messages"].select(
                            columns = Columns.list("id", "sender", "seen")
                        ) {
                            filter { eq("chat_id", chatId) }
                            filter { eq("seen", false) }
                        }.decodeList<JsonObject>()

                        for (msg in todasMsg) {
                            val sender = (msg["sender"] as? JsonPrimitive)?.content.orEmpty()
                            if (sender != currentUserId) noLeidos++
                        }
                    }

                    val item = ChatItem(
                        chatId = chatId,
                        otroUserId = otroId,
                        otroNickname = nickname,
                        otroFotoUrl = fotoUrl,
                        ultimoMensaje = ultimoMensaje,
                        ultimaFecha = ultimaFecha,
                        noLeidos = noLeidos
                    )
                    chatItemsTemp.add(item)
                }

                val sinVista3 = _binding ?: return@launch

                // Ordenar: con último mensaje primero (más reciente), luego los que no tienen mensajes
                chatItemsTemp.sortWith(compareByDescending<ChatItem> { it.ultimaFecha?.isNotBlank() == true }
                    .thenByDescending { it.ultimaFecha ?: "" })

                chatItems.clear()
                chatItems.addAll(chatItemsTemp)
                Handler(Looper.getMainLooper()).post {
                    adapter.notifyDataSetChanged()
                    actualizarVistaVacia()
                }

            } catch (e: Exception) {
                Log.e("ChatsFragment", "Error cargando chats", e)
                val sinVista = _binding ?: return@launch
                sinVista.srlChats.isRefreshing = false
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
        if (chatItems.isEmpty()) {
            b.llEmpty.visibility = View.VISIBLE
            b.rvChats.visibility = View.GONE
        } else {
            b.llEmpty.visibility = View.GONE
            b.rvChats.visibility = View.VISIBLE
        }
    }

    private fun mostrarError(mensaje: String) {
        val contexto = context ?: return
        _binding?.srlChats?.isRefreshing = false
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            onPositive = { cargarChats() }
        )
    }

    /** Adaptador para la lista de chats. */
    inner class ChatsAdapter : RecyclerView.Adapter<ChatsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chat = chatItems[position]
            val b = holder.binding

            b.tvNickname.text = chat.otroNickname
            b.tvLastMessage.text = chat.ultimoMensaje ?: ""
            b.tvTimestamp.text = formatearFecha(chat.ultimaFecha)

            // Avatar
            if (chat.otroFotoUrl?.isNotBlank() == true) {
                b.ivAvatar.load(chat.otroFotoUrl!!) {
                    crossfade(true)
                }
            } else {
                b.ivAvatar.setImageResource(R.color.primary)
            }

            // Badge no leídos
            if (chat.noLeidos > 0) {
                b.tvUnreadBadge.text = if (chat.noLeidos > 99) "99+" else chat.noLeidos.toString()
                b.tvUnreadBadge.visibility = View.VISIBLE
            } else {
                b.tvUnreadBadge.visibility = View.GONE
            }

            // Click en el chat
            b.cvChat.setOnClickListener {
                if (AntiSpam.esDobleTap("chat_${chat.chatId}")) return@setOnClickListener
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("chat_id", chat.chatId)
                    putExtra("otro_user_id", chat.otroUserId)
                    putExtra("otro_nickname", chat.otroNickname)
                    putExtra("otro_foto_url", chat.otroFotoUrl ?: "")
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = chatItems.size
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
                    outFormatter.format(date)
                }
            }
        } catch (e: Exception) {
            return ""
        }
    }
}