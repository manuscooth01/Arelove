package com.arelove.app

/**
 * Convierte los mensajes de error de Supabase Auth en mensajes legibles en español.
 */
object AuthErrorMapper {

    fun traducir(mensaje: String?): String {
        if (mensaje == null) return "Error desconocido"
        return when {
            mensaje.contains("Invalid login credentials", ignoreCase = true) ->
                "Correo o contraseña incorrectos"

            mensaje.contains("User already registered", ignoreCase = true) ->
                "Este correo ya está registrado"

            mensaje.contains("Email not confirmed", ignoreCase = true) ->
                "Debes confirmar tu correo antes de iniciar sesión"

            mensaje.contains("Password should be at least", ignoreCase = true) ->
                "La contraseña es muy corta"

            mensaje.contains("Unable to validate email", ignoreCase = true) ->
                "Correo electrónico inválido"

            mensaje.contains("network", ignoreCase = true) ||
                mensaje.contains("Unable to resolve host", ignoreCase = true) ||
                mensaje.contains("timeout", ignoreCase = true) ->
                "Sin conexión. Revisa tu internet"

            mensaje.contains("rate limit", ignoreCase = true) ->
                "Demasiados intentos. Espera unos minutos"

            else -> mensaje
        }
    }
}
