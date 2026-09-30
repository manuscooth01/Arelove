package com.arelove.app.home

/**
 * Item de chat para la lista de ChatsFragment.
 */
data class ChatItem(
    val chatId: String,
    val otroUserId: String,
    val otroNickname: String,
    val otroFotoUrl: String?,
    val ultimoMensaje: String?,
    val ultimaFecha: String?,
    val noLeidos: Int
)