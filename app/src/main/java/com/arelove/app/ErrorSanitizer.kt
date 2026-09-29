package com.arelove.app

/**
 * Limpia los mensajes de error técnicos (Supabase / HTTP) para poder
 * mostrarlos en un diálogo al usuario.
 *
 * Un error de PostgREST llega con la URL, las cabeceras y el body JSON:
 * información útil para depurar en logcat, pero inútil (y peligrosa)
 * en pantalla.
 */
object ErrorSanitizer {

    /**
     * Devuelve solo la parte útil del mensaje: corta antes de las secciones
     * técnicas (URL, Headers, Http Method, Body).
     *
     * @param mensaje mensaje original o null
     * @return el mensaje sin metadatos, o cadena vacía si no había nada
     */
    fun limpiar(mensaje: String?): String {
        if (mensaje.isNullOrBlank()) return ""
        // Cortar antes de URLs, Headers, JSON
        return mensaje
            .substringBefore("\nURL:")
            .substringBefore("\nHeaders:")
            .substringBefore("\nHttp Method:")
            .substringBefore("\nBody:")
            .substringBefore("URL:")
            .trim()
    }
}
