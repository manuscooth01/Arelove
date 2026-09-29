package com.arelove.app

import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Comprueba si el perfil del usuario actual está completo en Supabase.
 *
 * Lo usan MainActivity (al arrancar la app) y LoginActivity (tras iniciar
 * sesión) para decidir entre HomeActivity y ProfileSetupActivity.
 */
object ProfileChecker {

    /**
     * Consulta `profiles` en Supabase (SELECT * FROM profiles WHERE id = auth.uid())
     * y comprueba que exista la fila y que "age", "location" y "looking_for"
     * tengan valor.
     *
     * @return true solo si el perfil existe y está completo.
     */
    suspend fun perfilCompleto(): Boolean {
        return try {
            // Espera a que GoTrue termine de cargar la sesión guardada en disco
            SupabaseClient.client.auth.awaitInitialization()

            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
            if (userId == null) {
                // Sin sesión no hay perfil que comprobar
                false
            } else {
                val resultado = SupabaseClient.client.postgrest["profiles"].select {
                    filter { eq("id", userId) }
                    limit(1)
                }
                // Fila inexistente (0 resultados) => perfil incompleto
                val perfil = resultado.decodeSingleOrNull<JsonObject>()
                perfil != null && estaCompleto(perfil)
            }
        } catch (e: Exception) {
            // Si la consulta falla (tabla, RLS, red...) abrimos el asistente de perfil
            false
        }
    }

    /**
     * Devuelve true si las tres claves obligatorias del perfil tienen valor.
     */
    private fun estaCompleto(perfil: JsonObject): Boolean =
        tieneValor(perfil, "age") &&
            tieneValor(perfil, "location") &&
            tieneValor(perfil, "looking_for")

    /**
     * Devuelve true si la clave existe en la fila y no está vacía / null.
     */
    private fun tieneValor(perfil: JsonObject, clave: String): Boolean {
        val elemento = perfil[clave] ?: return false
        if (elemento is JsonNull) return false
        if (elemento !is JsonPrimitive) return false
        return elemento.content.trim().isNotEmpty()
    }
}
