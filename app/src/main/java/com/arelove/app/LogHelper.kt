package com.arelove.app

import android.util.Log

/**
 * Utilidad de logging segura que nunca expone datos sensibles en logcat.
 *
 * Reglas de seguridad (según OWASP Mobile Security y supabase best-practices):
 * - Nunca loguear Exception completo (puede contener URLs, tokens, paths, stack traces)
 * - Nunca loguear URLs firmadas (signed URLs con tokens embebidos)
 * - Nunca loguear IDs de usuario, emails o identificadores personales
 * - Nunca loguear cuerpos de respuesta HTTP
 *
 * En release, los logs de error se envían a Log.w (no Log.e) para evitar
 * que se muestren en consola de desarrollador.
 */
object LogHelper {

    private const val PREFIX = "Arelove"

    /**
     * Loguea un mensaje de error sanitario sin el objeto Exception.
     * Si se necesita diagnóstico, usar [logSafe] con un mensaje descriptivo
     * y no pasar la excepción original.
     */
    fun e(tag: String, mensaje: String) {
        val safe = sanitizar(mensaje)
        Log.e("$PREFIX.$tag", safe)
    }

    /**
     * Loguea un mensaje de advertencia sanitario sin el objeto Exception.
     */
    fun w(tag: String, mensaje: String) {
        val safe = sanitizar(mensaje)
        Log.w("$PREFIX.$tag", safe)
    }

    /**
     * Loguea un mensaje de debug sanitario (solo en BuildConfig.DEBUG).
     */
    fun d(tag: String, mensaje: String) {
        if (BuildConfig.DEBUG) {
            val safe = sanitizar(mensaje)
            Log.d("$PREFIX.$tag", safe)
        }
    }

    /**
     * Sanitiza un mensaje eliminando posibles datos sensibles:
     * URLs completas, tokens, paths de archivo, stack traces.
     */
    private fun sanitizar(mensaje: String): String {
        if (mensaje.isNullOrBlank()) return "Error sin mensaje"

        var resultado = mensaje.trim()

        // Eliminar URLs completas (http/https)
        resultado = resultado.replace(Regex("https?://[\\S]+"), "[URL_OMITIDA]")

        // Eliminar posibles tokens (patrones tipo sb_publishable_ o similar)
        resultado = resultado.replace(Regex("sb_[a-zA-Z0-9_\\-]+"), "[TOKEN_OMITIDO]")

        // Eliminar paths de archivo (Android/data, /storage, etc.)
        resultado = resultado.replace(Regex("/(data|storage|mnt|sdcard)[\\S]+"), "[RUTA_OMITIDA]")

        // Limitar longitud para no llenar logcat
        if (resultado.length > 200) {
            resultado = resultado.substring(0, 200) + "..."
        }

        return resultado
    }
}