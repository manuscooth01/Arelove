package com.arelove.app

import com.onesignal.OneSignal
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.eq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extensiones de SupabaseClient para operaciones de OneSignal.
 * Copia exacta de los patrones que funcionan en AdminVerifyActivity.
 */
fun SupabaseClient.registrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
    try {
        val playerId = OneSignal.User.pushSubscription.id
        if (playerId.isNullOrEmpty()) {
            android.util.Log.w("OneSignal", "Player ID vacío")
            return@withContext
        }

        val userId = this.auth.currentSessionOrNull()?.user?.id
            ?: return@withContext

        this.postgrest["profiles"].update(
            buildJsonObject { put("onesignal_player_id", playerId) }
        ) {
            filter { eq("id", userId) }
        }

        android.util.Log.d("OneSignal", "Player ID registrado: $playerId")
    }

    /**
     * Borra el player id de profiles del usuario que cierra sesión.
     */
    suspend fun desregistrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
        try {
            val userId = this.auth.currentSessionOrNull()?.user?.id
                ?: return@withContext

            this.postgrest["profiles"].update(
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