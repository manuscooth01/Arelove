package com.arelove.app.admin

import android.util.Log
import com.arelove.app.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lee profiles.is_admin del usuario actual, con caché corta POR USUARIO.
 *
 * Única vía de saber si la cuenta es admin: NO se comparan emails ni se
 * guarda el rol en el dispositivo.
 *
 * La caché guarda también el userId al que pertenece el resultado: si la
 * sesión cambia (logout + login de otra cuenta), lo cacheado se descarta
 * automáticamente y se vuelve a consultar. Así un admin no "presta" su
 * `true` a otro usuario durante los 5 minutos de vigencia.
 *
 * Solo consulta: aquí no se escribe ninguna columna.
 */
object AdminChecker {

    /** Último resultado; null = todavía no se ha consultado. */
    private var cache: Boolean? = null

    /** Usuario al que pertenece [cache]; null = sin sesión cacheada. */
    private var cacheUserId: String? = null

    /** Marca de tiempo (ms) de la última consulta correcta. */
    private var cacheTimestamp = 0L

    /** Vigencia de la caché: 5 minutos. */
    private const val CACHE_TTL = 5 * 60 * 1000L

    /** true si el usuario actual tiene profiles.is_admin = true. */
    suspend fun esAdmin(): Boolean {
        try {
            // awaitInitialization() ANTES de leer la sesión (regla del repo)
            SupabaseClient.client.auth.awaitInitialization()
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id

            // Caché por usuario: si cambió la sesión, lo cacheado no vale
            if (userId != cacheUserId) {
                cache = null
                cacheUserId = userId
                cacheTimestamp = 0
            }

            // Sin sesión no hay admin (y no se cachea nada)
            if (userId == null) return false

            val ahora = System.currentTimeMillis()
            cache?.let {
                if (ahora - cacheTimestamp < CACHE_TTL) return it
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
            cacheUserId = userId
            cacheTimestamp = ahora
            return resultado
        } catch (e: Exception) {
            Log.e("AdminChecker", "No se pudo leer profiles.is_admin", e)
            cache = null // nunca cachear un error
            return false
        }
    }

    /** Invalida la caché (logout, login o cambio de rol desde el panel admin). */
    fun invalidarCache() {
        cache = null
        cacheUserId = null
        cacheTimestamp = 0
    }
}
