package com.arelove.app.notifications

import android.util.Log
import com.arelove.app.SupabaseClient
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gestiona el registro y actualización del token FCM del usuario.
 * El token se guarda en profiles.fcm_token para que la Edge Function
 * de Supabase pueda enviar notificaciones.
 */
object FcmTokenManager {
    private const val TAG = "FcmToken"

    /**
     * Obtiene el token FCM y lo guarda en profiles.fcm_token.
     * Se llama tras login exitoso o registro.
     */
    suspend fun registrarToken() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNullOrEmpty()) {
                Log.w(TAG, "Token FCM vacío")
                return
            }

            val userId = SupabaseClient.client.auth.currentSessionOrNull()
                ?.user?.id ?: return

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("fcm_token", token) }
            ) {
                filter { eq("id", userId) }
            }

            Log.d(TAG, "Token FCM registrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando token FCM", e)
        }
    }

    /** Borra el token al cerrar sesión. */
    suspend fun desregistrarToken() {
        try {
            val userId = SupabaseClient.client.auth.currentSessionOrNull()
                ?.user?.id ?: return

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("fcm_token", kotlinx.serialization.json.JsonNull) }
            ) {
                filter { eq("id", userId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error desregistrando token FCM", e)
        }
    }
}
