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
 * Lo usan MainActivity (al arrancar la app) y LoginActivity (tras iniciar
 * sesión) para decidir entre HomeActivity y ProfileSetupActivity: un perfil
 * incompleto nunca debe entrar en Home.
 *
 * Comprueba los 3 pasos críticos del asistente:
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

    /**
     * @return true solo si los 3 pasos del asistente están completos.
     */
    suspend fun perfilCompleto(): Boolean {
        try {
            // Espera a que GoTrue termine de cargar la sesión guardada en disco
            SupabaseClient.client.auth.awaitInitialization()

            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
            if (userId == null) {
                // Sin sesión no hay perfil que comprobar
                return false
            }

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

            // Atajo rápido: Supabase ya marcó el perfil como completo
            if (esVerdadero(perfil["profile_completed"])) return true

            // profiles.profile_completed = false => comprobamos los 3 pasos
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
