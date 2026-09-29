package com.arelove.app.home

/**
 * Perfil para mostrar en el motor de descubrimiento (swipe).
 */
data class SwipeProfile(
    val userId: String,
    val nickname: String,
    val age: Int?,
    val location: String?,
    val bio: String?,
    val verified: Boolean,
    val fotos: List<String>
)