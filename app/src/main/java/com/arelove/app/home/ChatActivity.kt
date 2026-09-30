package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityChatBinding
import com.arelove.app.databinding.ItemMessageBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import coil.transform.RoundedCornersTransformation
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Activity de conversación individual.
 * Muestra mensajes en tiempo real y permite enviar nuevos.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var chatId: String = ""
    private var otroUserId: String = ""
    private var currentUserId: String = ""
    private var currentNickname: String = ""
    private var currentFotoUrl: String = ""
    private var currentPage = 0

    private val messages = mutableListOf<MessageItem>()
    private lateinit var adapter: MessagesAdapter
    private var listeningJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra("chat_id") ?: ""
        otroUserId = intent.getStringExtra("otro_user_id") ?: ""
        currentNickname = intent.getStringExtra("otro_nickname") ?: "Usuario"
        currentFotoUrl = intent.getStringExtra("otro_foto_url") ?: ""

        if (chatId.isEmpty() || otroUserId.isEmpty()) {
            finish()
            return
        }

        configurarVista()
        cargarDatos()
    }

    override fun onResume() {
        super.onResume()
        suscribirRealtime()
    }

    override fun onPause() {
        super.onPause()
        cancelarRealtime()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelarRealtime()
        listeningJob?.cancel()
    }

    private fun configurarVista() {
        binding.tvNickname.text = currentNickname
        if (currentFotoUrl.isNotBlank()) {
            binding.ivAvatar.load(currentFotoUrl) { crossfade(true) }
        } else {
            binding.ivAvatar.setImageResource(R.color.primary)
        }

        binding.btnBack.setOnClickListener {
            if (AntiSpam.esDobleTap("btnBack")) return@setOnClickListener
            finish()
        }

        binding.btnMore.setOnClickListener {
            if (AntiSpam.esDobleTap("btnMore")) return@setOnClickListener
            // TODO: mostrar bottom sheet con opciones
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        adapter = MessagesAdapter()
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener {
            if (AntiSpam.esDobleTap("btnSend")) return@setOnClickListener
            enviarMensaje()
        }

        binding.btnAttach.setOnClickListener {
            if (AntiSpam.esDobleTap("btnAttach")) return@setOnClickListener
            // TODO: abrir selector de adjuntos
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                enviarMensaje()
                true
            } else {
                false
            }
        }
    }

    private fun cargarDatos() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                currentUserId = session?.user?.id ?: ""
                if (currentUserId.isEmpty()) {
                    runOnUiThread { finish() }
                    return@launch
                }

                // Cargar mensajes existentes
                val filas = SupabaseClient.client.postgrest["messages"].select(
                    columns = Columns.list("id", "chat_id", "sender", "content", "type", "seen", "created_at")
                ) {
                    filter { eq("chat_id", chatId) }
                    order("created_at", Order.ASCENDING)
                    limit(100)
                }.decodeList<JsonObject>()

                val loadedMessages = filas.map { fila ->
                    MessageItem(
                        id = (fila["id"] as? JsonPrimitive)?.content.orEmpty(),
                        chatId = (fila["chat_id"] as? JsonPrimitive)?.content.orEmpty(),
                        sender = (fila["sender"] as? JsonPrimitive)?.content.orEmpty(),
                        content = (fila["content"] as? JsonPrimitive)?.content.orEmpty(),
                        type = (fila["type"] as? JsonPrimitive)?.content.orEmpty(),
                        seen = (fila["seen"] as? JsonPrimitive)?.content == "true",
                        createdAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()
                    )
                }.toMutableList()

                val sinVista = binding
                runOnUiThread {
                    messages.clear()
                    messages.addAll(loadedMessages)
                    adapter.notifyDataSetChanged()
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }

                // Marcar como vistos los mensajes del otro
                marcarComoVistos()

                // Suscribirse a realtime
                suscribirRealtime()

            } catch (e: Exception) {
                Log.e("ChatActivity", "Error cargando chat", e)
                runOnUiThread {
                    AreloveDialog.show(
                        this@ChatActivity,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        getString(R.string.error_generic),
                        onPositive = { finish() }
                    )
                }
            }
        }
    }

    private fun enviarMensaje() {
        val texto = binding.etMessage.text.toString().trim()
        if (texto.isBlank()) return

        val tempId = "temp_${System.currentTimeMillis()}"
        val tempMessage = MessageItem(
            id = tempId,
            chatId = chatId,
            sender = currentUserId,
            content = texto,
            type = "text",
            seen = true, // Los propios se marcan como vistos
            createdAt = java.time.Instant.now().toString()
        )

        // Optimistic UI: añadir inmediatamente
        messages.add(tempMessage)
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.etMessage.setText("")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                SupabaseClient.client.postgrest["messages"].insert<JsonObject>(buildJsonObject {
                    put("chat_id", chatId)
                    put("sender", currentUserId)
                    put("content", texto)
                    put("type", "text")
                })
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error enviando mensaje", e)
                runOnUiThread {
                    // Quitar mensaje temporal si falla
                    val index = messages.indexOfFirst { it.id == tempId }
                    if (index != -1) {
                        messages.removeAt(index)
                        adapter.notifyItemRemoved(index)
                    }
                }
            }
        }
    }

    private fun marcarComoVistos() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                SupabaseClient.client.postgrest["messages"].update<JsonObject>(buildJsonObject {
                    put("seen", true)
                }) {
                    filter { eq("chat_id", chatId) }
                    filter { eq("sender", otroUserId) }
                    filter { eq("seen", false) }
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error marcando como vistos", e)
            }
        }
    }

    private fun suscribirRealtime() {
        // TODO: Implementar realtime cuando se verifique la API correcta
        // Por ahora, usar polling simple cada 5 segundos
        listeningJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                if (!isActive) break
                cargarMensajesNuevos()
            }
        }
    }

    private fun cargarMensajesNuevos() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ultimaFecha = messages.lastOrNull()?.createdAt ?: ""
                if (ultimaFecha.isBlank()) return@launch

                val filas = SupabaseClient.client.postgrest["messages"].select(
                    columns = Columns.list("id", "chat_id", "sender", "content", "type", "seen", "created_at")
                ) {
                    filter { eq("chat_id", chatId) }
                    filter { gte("created_at", ultimaFecha) }
                    order("created_at", Order.ASCENDING)
                }.decodeList<JsonObject>()

                val nuevosMensajes = filas.map { fila ->
                    MessageItem(
                        id = (fila["id"] as? JsonPrimitive)?.content.orEmpty(),
                        chatId = (fila["chat_id"] as? JsonPrimitive)?.content.orEmpty(),
                        sender = (fila["sender"] as? JsonPrimitive)?.content.orEmpty(),
                        content = (fila["content"] as? JsonPrimitive)?.content.orEmpty(),
                        type = (fila["type"] as? JsonPrimitive)?.content.orEmpty(),
                        seen = (fila["seen"] as? JsonPrimitive)?.content == "true",
                        createdAt = (fila["created_at"] as? JsonPrimitive)?.content.orEmpty()
                    )
                }.filterNot { msg -> messages.any { it.id == msg.id } }

                if (nuevosMensajes.isNotEmpty()) {
                    runOnUiThread {
                        val startPos = messages.size
                        messages.addAll(nuevosMensajes)
                        adapter.notifyItemRangeInserted(startPos, nuevosMensajes.size)
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error polling mensajes", e)
            }
        }
    }

    private fun cancelarRealtime() {
        listeningJob?.cancel()
        listeningJob = null
    }

    /** Adaptador para los mensajes. */
    inner class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            val b = holder.binding
            val esPropio = msg.sender == currentUserId

            b.cvMessageOwn.visibility = if (esPropio) View.VISIBLE else View.GONE
            b.cvMessageOther.visibility = if (!esPropio) View.VISIBLE else View.GONE
            b.tvTimeOwn.visibility = if (esPropio) View.VISIBLE else View.GONE
            b.tvTimeOther.visibility = if (!esPropio) View.VISIBLE else View.GONE

            val contentView = if (esPropio) b.tvMessageOwn else b.tvMessageOther
            val timeView = if (esPropio) b.tvTimeOwn else b.tvTimeOther

            contentView.text = msg.content
            timeView.text = formatearHora(msg.createdAt)

            // Animación fade + slide para mensajes nuevos
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 30f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }

        override fun getItemCount(): Int = messages.size
    }

    private fun formatearHora(fechaIso: String?): String {
        if (fechaIso?.isBlank() == true) return ""
        try {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
            val date = formatter.parse(fechaIso)
            val outFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return outFormatter.format(date!!)
        } catch (e: Exception) {
            return ""
        }
    }

    /** Item de mensaje para el adapter. */
    private data class MessageItem(
        val id: String,
        val chatId: String,
        val sender: String,
        val content: String,
        val type: String,
        val seen: Boolean,
        val createdAt: String
    )
}