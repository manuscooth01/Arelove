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
    val fotos: List<String>,
    val height: Int? = null,
    val zodiac: String? = null,
    val occupation: String? = null,
    val intereses: List<String> = emptyList(),
    val lookingFor: String? = null,
    val gender: String? = null
)