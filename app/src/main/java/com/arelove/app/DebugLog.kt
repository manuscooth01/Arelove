package com.arelove.app

/**
 * Registro en memoria para diagnóstico visible en pantalla.
 *
 * TEMPORAL: se usa porque el dispositivo no tiene root y no se puede
 * leer logcat. Se mostrará en un diálogo y se eliminará cuando se
 * encuentre el bug del Paso 2.
 */
object DebugLog {

    /** Últimas 15 líneas registradas (las más viejas se descartan). */
    private val lineas = mutableListOf<String>()

    fun add(mensaje: String) {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date())
        lineas.add("[$hora] $mensaje")
        if (lineas.size > 15) lineas.removeAt(0)
    }

    fun limpiar() = lineas.clear()

    fun texto(): String = lineas.joinToString("\n")
}
