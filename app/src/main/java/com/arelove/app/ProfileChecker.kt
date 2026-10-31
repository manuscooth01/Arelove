package com.arelove.app

import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Comprueba si el perfil del usuario actual está completo en Supabase.
 *
 * Lo usan MainActivity (al arrancar la app) para decidir entre HomeActivity y
 * ProfileSetupActivity: un perfil incompleto nunca debe entrar en Home.
 *
 * Única vía de entrada a Home: `profiles.profile_completed = true`, que solo
 * marca el Paso 5 del asistente al terminarlo (con o sin selfie). Además se
 * exige la salvaguarda de los 3 pasos críticos:
 *  - Paso 1: age, location y looking_for con valor
 *  - Paso 2: al menos 3 filas en user_interests
 *  - Paso 3: al menos 2 fotos con type = 'profile' en media
 *
 * Solo consulta: aquí no se escribe ninguna columna.
 */
object ProfileChecker {

    /** Intereses mínimos que exige el Paso 2. */
    private const val MINIMO_INTERESES = 3

    /** Fotos de perfil mínimas que exige el Paso 3. */
    private const val MINIMO_FOTOS = 2

    /** Validez de la caché en memoria del último resultado (30 segundos). */
    private const val CACHE_TTL_MS = 30_000L

    /** Último resultado cacheado; null mientras no se ha comprobado nada. */
    private var cacheResultado: Boolean? = null

    /** Usuario al que pertenece [cacheResultado] (no mezclar cuentas). */
    private var cacheUserId: String? = null

    /** Instante en que se guardó [cacheResultado]. */
    private var cacheTimestamp: Long = 0

    /**
     * @return true solo si el asistente se terminó (profile_completed) y los
     * 3 pasos siguen completos.
     *
     * Requiere que el llamante haya hecho awaitInitialization().
     */
    suspend fun perfilCompleto(): Boolean {
        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
        // Sin sesión no hay perfil que comprobar (y no se cachea)
        if (userId == null) return false

        // Caché de 30s: evita repetir las 3 queries en el mismo arranque
        val ahora = System.currentTimeMillis()
        val cacheado = cacheResultado
        if (cacheado != null && cacheUserId == userId && ahora - cacheTimestamp < CACHE_TTL_MS) {
            return cacheado
        }

        val resultado = calcularPerfilCompleto(userId)
        cacheResultado = resultado
        cacheUserId = userId
        cacheTimestamp = System.currentTimeMillis()
        return resultado
    }

    /** Consultas de Supabase que responden si el perfil está completo. */
    private suspend fun calcularPerfilCompleto(userId: String): Boolean {
        try {
            // SELECT age, location, looking_for, profile_completed
            //     FROM profiles WHERE id = userId LIMIT 1
            val resultado = SupabaseClient.client.postgrest["profiles"].select(
                columns = Columns.list("age", "location", "looking_for", "profile_completed")
            ) {
                filter { eq("id", userId) }
                limit(1)
            }

            // Fila inexistente (0 resultados) => perfil incompleto
            val perfil = resultado.decodeSingleOrNull<JsonObject>() ?: return false

            // Atajo: sin profile_completed no se entra en Home, aunque los
            // 3 primeros pasos estén completos (los marca solo el Paso 5)
            if (!esVerdadero(perfil["profile_completed"])) return false

            // Salvaguarda: si el flag está pero falta algún dato esencial
            // (borrado a posteriori), volvemos al asistente
            val paso1 = tieneValor(perfil, "age") &&
                tieneValor(perfil, "location") &&
                tieneValor(perfil, "looking_for")
            if (!paso1) return false

            // SELECT count(*) FROM user_interests WHERE user_id = userId
            val intereses = SupabaseClient.client.postgrest["user_interests"]
                .select(head = true) {
                    filter { eq("user_id", userId) }
                    count(Count.EXACT)
                }
                .countOrNull() ?: 0
            if (intereses < MINIMO_INTERESES) return false

            // SELECT count(*) FROM media WHERE user_id = userId AND type = 'profile'
            val fotos = SupabaseClient.client.postgrest["media"]
                .select(head = true) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    count(Count.EXACT)
                }
                .countOrNull() ?: 0
            if (fotos < MINIMO_FOTOS) return false

            return true
        } catch (e: Exception) {
            // Si algo falla (tabla, RLS, red...) abrimos el asistente de perfil
            return false
        }
    }

    /**
     * Devuelve true si la clave existe en la fila y no está vacía / null.
     */
    private fun tieneValor(perfil: JsonObject, clave: String): Boolean {
        val elemento = perfil[clave] ?: return false
        if (elemento is JsonNull) return false
        if (elemento !is JsonPrimitive) return false
        return elemento.content.trim().isNotEmpty()
    }

    /**
     * Devuelve true si la columna booleana es true (ausente o null => false).
     */
    private fun esVerdadero(elemento: JsonElement?): Boolean =
        elemento is JsonPrimitive && !elemento.isString && elemento.content == "true"
}
