package com.arelove.app

import com.onesignal.OneSignal
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.eq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extensiones de SupabaseClient para operaciones relacionadas con OneSignal.
 * Separado en archivo propio para evitar problemas de compilación en OneSignalManager.
 */
@Suppress("UNUSED_PARAMETER")
object SupabaseClientExt {

    /**
     * Guarda el player id de OneSignal en profiles para el usuario con sesión activa.
     */
    suspend fun SupabaseClient.registrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
        try {
            val playerId = com.onesignal.OneSignal.User.pushSubscription.id
            if (playerId.isNullOrEmpty()) {
                android.util.Log.w("OneSignal", "Player ID vacío")
                return@withContext
            }

            val userId = this.auth.currentSessionOrNull()?.user?.id
                ?: return

            this.postgrest["profiles"].update(
                kotlinx.serialization.json.buildJsonObject { put("onesignal_player_id", playerId) }
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
    suspend fun SupabaseClient.desregistrarOneSignalPlayerId() = withContext(Dispatchers.IO) {
        try {
            val userId = this.auth.currentSessionOrNull()?.user?.id
                ?: return

            this.postgrest["profiles"].update(
                kotlinx.serialization.json.buildJsonObject { put("onesignal_player_id", kotlinx.serialization.json.JsonNull) }
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