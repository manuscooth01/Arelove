package com.arelove.app.home

/**
 * Item de like recibido para la lista de LikesReceivedFragment.
 */
data class LikeItem(
    val likeId: String,
    val fromUserId: String,
    val nickname: String,
    val age: Int?,
    val location: String?,
    val fotoUrl: String?,
    val createdAt: String,
    val expiresAt: String
)