package com.arelove.app

import android.content.Context

/**
 * Convierte los mensajes de error de Supabase Auth en mensajes legibles en español.
 * Los textos viven en `res/values/strings.xml`, por eso necesita un [Context].
 */
object AuthErrorMapper {

    fun traducir(context: Context, mensaje: String?): String {
        if (mensaje == null) return context.getString(R.string.error_unknown)
        return when {
            mensaje.contains("Invalid login credentials", ignoreCase = true) ->
                context.getString(R.string.error_wrong_credentials)

            mensaje.contains("User already registered", ignoreCase = true) ->
                context.getString(R.string.error_email_in_use)

            mensaje.contains("Email not confirmed", ignoreCase = true) ->
                context.getString(R.string.error_email_unconfirmed)

            mensaje.contains("Password should be at least", ignoreCase = true) ->
                context.getString(R.string.error_password_too_short)

            mensaje.contains("Unable to validate email", ignoreCase = true) ->
                context.getString(R.string.validation_invalid_email)

            mensaje.contains("network", ignoreCase = true) ||
                mensaje.contains("Unable to resolve host", ignoreCase = true) ||
                mensaje.contains("timeout", ignoreCase = true) ->
                context.getString(R.string.error_network)

            mensaje.contains("rate limit", ignoreCase = true) ->
                context.getString(R.string.error_rate_limit)

            else -> mensaje
        }
    }
}
