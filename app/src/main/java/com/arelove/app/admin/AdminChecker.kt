package com.arelove.app.admin

import android.util.Log
import com.arelove.app.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lee profiles.is_admin del usuario actual, con caché corta.
 *
 * Única vía de saber si la cuenta es admin: NO se comparan emails ni se
 * guarda el rol en el dispositivo. Un cambio de rol en Supabase se refleja
 * como máximo 5 minutos después, o al instante si se llama a [invalidarCache].
 *
 * Solo consulta: aquí no se escribe ninguna columna.
 */
object AdminChecker {

    /** Último resultado; null = todavía no se ha consultado. */
    private var cache: Boolean? = null

    /** Marca de tiempo (ms) de la última consulta correcta. */
    private var cacheTimestamp = 0L

    /** Vigencia de la caché: 5 minutos. */
    private const val CACHE_TTL = 5 * 60 * 1000L

    /** true si el usuario actual tiene profiles.is_admin = true. */
    suspend fun esAdmin(): Boolean {
        val ahora = System.currentTimeMillis()
        cache?.let {
            if (ahora - cacheTimestamp < CACHE_TTL) return it
        }

        return try {
            SupabaseClient.client.auth.awaitInitialization()
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
            if (userId == null) {
                cache = false
                cacheTimestamp = ahora
                return false
            }

            // SELECT is_admin FROM profiles WHERE id = userId LIMIT 1
            val fila = SupabaseClient.client.postgrest["profiles"].select(
                columns = Columns.list("is_admin")
            ) {
                filter { eq("id", userId) }
                limit(1)
            }.decodeSingleOrNull<JsonObject>()

            val resultado = (fila?.get("is_admin") as? JsonPrimitive)?.content == "true"

            cache = resultado
            cacheTimestamp = ahora
            resultado
        } catch (e: Exception) {
            Log.e("AdminChecker", "No se pudo leer profiles.is_admin", e)
            false
        }
    }

    /** Invalida la caché (p. ej. al cambiar el rol desde el panel admin). */
    fun invalidarCache() {
        cache = null
        cacheTimestamp = 0
    }
}
