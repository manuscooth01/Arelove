package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
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
import com.arelove.app.BuildConfig
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LogHelper
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                // Hacemos dos queries separadas y combinamos (evita problema con 'or' en postgrest-kt)
                val filas1 = SupabaseClient.client.postgrest["chats"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user1", currentUserId!!) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val filas2 = SupabaseClient.client.postgrest["chats"].select(
                    columns = Columns.list("id", "user1", "user2", "created_at")
                ) {
                    filter { eq("user2", currentUserId!!) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                val filasChats = (filas1 + filas2).distinctBy { (it["id"] as? JsonPrimitive)?.content.orEmpty() }
                    .sortedByDescending { (it["created_at"] as? JsonPrimitive)?.content.orEmpty() }

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

                // Cargar perfiles de los otros usuarios en batch (una query para perfiles, otra para fotos)
                val perfiles = mutableMapOf<String, Pair<String?, String?>>()

                val perfilFilas = SupabaseClient.client.postgrest["profiles_publicos"].select(
                    columns = Columns.list("id", "nickname")
                ) {
                    filter { isIn("id", otrosUserIds.toList()) }
                    filter { eq("profile_completed", true) }
                }.decodeList<JsonObject>()

                val nicknames = mutableMapOf<String, String>()
                perfilFilas.forEach { fila ->
                    val id = (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                    val nickname = (fila["nickname"] as? JsonPrimitive)?.content.orEmpty()
                    if (id.isNotEmpty()) {
                        nicknames[id] = nickname
                    }
                }

                val fotoFilas = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("user_id", "url")
                ) {
                    filter { isIn("user_id", otrosUserIds.toList()) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                }.decodeList<JsonObject>()

                val fotosPorUsuario = mutableMapOf<String, String>()
                fotoFilas.forEach { fila ->
                    val uid = (fila["user_id"] as? JsonPrimitive)?.content
                    val url = (fila["url"] as? JsonPrimitive)?.content
                    if (uid != null && url != null && fotosPorUsuario[uid] == null) {
                        fotosPorUsuario[uid] = url
                    }
                }

                otrosUserIds.forEach { otroId ->
                    val nickname = nicknames[otroId] ?: "Usuario"
                    val fotoUrl = fotosPorUsuario[otroId]
                    perfiles[otroId] = nickname to fotoUrl
                }

                val sinVista2 = _binding ?: return@launch

                // Ahora cargar último mensaje y no leídos para cada chat en batch
                val chatIds = filasChats.map { fila ->
                    (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                }.filter { it.isNotEmpty() }

                // Último mensaje por chat (orden descendente, limit 1 por chat_id no disponible directo,
                // así que cargamos todos los mensajes recientes de estos chats y nos quedamos con el último)
                val todosMensajes = SupabaseClient.client.postgrest["messages"].select(
                    columns = Columns.list("id", "chat_id", "content", "created_at", "sender", "seen")
                ) {
                    filter { isIn("chat_id", chatIds) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()

                // Índice del último mensaje por chat
                val ultimoMensajePorChat = mutableMapOf<String, JsonObject>()
                todosMensajes.forEach { fila ->
                    val cid = (fila["chat_id"] as? JsonPrimitive)?.content
                    if (cid != null && ultimoMensajePorChat[cid] == null) {
                        ultimoMensajePorChat[cid] = fila
                    }
                }

                // Mensajes no leídos por chat
                val noLeidosPorChat = mutableMapOf<String, Int>()
                todosMensajes.forEach { fila ->
                    val cid = (fila["chat_id"] as? JsonPrimitive)?.content
                    val seen = (fila["seen"] as? JsonPrimitive)?.content == "true"
                    val sender = (fila["sender"] as? JsonPrimitive)?.content.orEmpty()
                    if (cid != null && !seen && sender != currentUserId) {
                        noLeidosPorChat[cid] = (noLeidosPorChat[cid] ?: 0) + 1
                    }
                }

                val chatItemsTemp = mutableListOf<ChatItem>()

                for (fila in filasChats) {
                    val chatId = (fila["id"] as? JsonPrimitive)?.content.orEmpty()
                    val user1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                    val user2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                    val chatCreatedAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()

                    val otroId = if (user1 == currentUserId) user2 else user1
                    val perfil = perfiles[otroId] ?: "Usuario" to null
                    val nickname = perfil.first ?: "Usuario"
                    val fotoUrl = perfil.second

                    // Último mensaje
                    var ultimoMensaje: String? = null
                    var ultimaFecha: String? = chatCreatedAt

                    val ultimoMsg = ultimoMensajePorChat[chatId]
                    if (ultimoMsg != null) {
                        ultimoMensaje = (ultimoMsg["content"] as? JsonPrimitive)?.content.orEmpty()
                        ultimaFecha = (ultimoMsg["created_at"] as? JsonPrimitive)?.content.orEmpty()
                    }

                    // Contar no leídos
                    val noLeidos = noLeidosPorChat[chatId] ?: 0

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

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e("ChatsFragment", "Error cargando chats")
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