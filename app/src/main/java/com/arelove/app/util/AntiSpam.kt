package com.arelove.app.util

/**
 * Anti-spam global de la app: evita que un doble toque dispare dos veces
 * la misma acción (subir dos fotos, dos logins, dos avances de paso...).
 *
 * Uso dentro de un setOnClickListener:
 *
 *     binding.btnX.setOnClickListener {
 *         if (AntiSpam.esDobleTap("btnX")) return@setOnClickListener
 *         // lógica original
 *     }
 *
 * Cada botón usa su propia [clave], así que un toque en un botón no bloquea
 * a los demás. El mapa solo crece con el número de botones distintos.
 */
object AntiSpam {

    /** Último toque aceptado por clave (milisegundos desde el arranque). */
    private val ultimoClick = mutableMapOf<String, Long>()

    /** Intervalo mínimo entre dos toques de la misma clave. */
    private const val INTERVALO_MS = 800L

    /** Devuelve true si el click debe ignorarse (doble tap). */
    fun esDobleTap(clave: String): Boolean {
        val ahora = System.currentTimeMillis()
        val ultimo = ultimoClick[clave] ?: 0L
        if (ahora - ultimo < INTERVALO_MS) return true
        ultimoClick[clave] = ahora
        return false
    }
}
