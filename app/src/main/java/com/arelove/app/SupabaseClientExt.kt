package com.arelove.app

import com.onesignal.OneSignal
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Funciones de utilidad para operaciones de OneSignal sobre SupabaseClient.
 * Copia exacta de los patrones que funcionan en AdminVerifyActivity.
 */
object SupabaseClientExt {

    /**
     * Guarda el player id de OneSignal en profiles para el usuario con sesión activa.
     */
    suspend fun registrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
        try {
            val playerId = OneSignal.User.pushSubscription.id
            if (playerId.isNullOrEmpty()) {
                android.util.Log.w("OneSignal", "Player ID vacío")
                return@withContext
            }

            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                ?: return@withContext

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("onesignal_player_id", playerId) }
            ) {
                filter { eq("id", userId) }
            }

            android.util.Log.d("OneSignal", "Player ID registrado: $playerId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OneSignal", "Error registrando Player ID", e)
        }
    }

    /**
     * Borra el player id de profiles del usuario que cierra sesión.
     */
    suspend fun desregistrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
        try {
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                ?: return@withContext

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("onesignal_player_id", JsonNull) }
            ) {
                filter { eq("id", userId) }
            }

            android.util.Log.d("OneSignal", "Player ID borrado de la BD")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OneSignal", "Error borrando Player ID", e)
        }
    }
}