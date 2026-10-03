package com.arelove.app.notifications

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach

/**
 * Router singleton para navegación desde notificaciones push.
 * Usa Channel (hot, con buffer) para pasar el payload de navegación
 * desde MainActivity/OneSignalManager a HomeActivity/ChatActivity.
 */
object PushRouter {

    // Canal con buffer: permite enviar incluso si aún no hay receiver
    private val canal = Channel<PushPayload>(10)

    /** Envía payload de navegación (no se suspende mientras haya espacio en el buffer) */
    suspend fun enrutar(payload: PushPayload) {
        canal.send(payload)
    }

    /** Canal para que HomeActivity/ChatActivity colecten */
    fun watch(): ReceiveChannel<PushPayload> = canal

    /** Payload de navegación según tipo de notificación */
    sealed interface PushPayload {
        data class Like(
            val fromUser: String
        ) : PushPayload

        data class Match(
            val matchId: String
        ) : PushPayload

        data class Mensaje(
            val chatId: String,
            val sender: String,
            val senderNickname: String?
        ) : PushPayload
    }

    /** Factory desde extras de Intent/OneSignal */
    fun fromExtras(extras: android.os.Bundle): PushPayload? {
        val tipo = extras.getString("tipo") ?: return null
        return when (tipo) {
            "like" -> {
                val fromUser = extras.getString("from_user") ?: return null
                PushPayload.Like(fromUser)
            }
            "match" -> {
                val matchId = extras.getString("match_id") ?: return null
                // other_user_id opcional, no requerido para navegación a ChatsFragment
                PushPayload.Match(matchId)
            }
            "mensaje" -> {
                val chatId = extras.getString("chat_id") ?: return null
                val sender = extras.getString("sender") ?: return null
                val senderNickname = extras.getString("sender_nickname")
                PushPayload.Mensaje(chatId, sender, senderNickname)
            }
            else -> null
        }
    }
}