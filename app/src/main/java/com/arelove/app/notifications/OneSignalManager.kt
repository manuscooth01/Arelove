package com.arelove.app.notifications

import android.content.Context
import android.util.Log
import com.arelove.app.SupabaseClient
import com.onesignal.OneSignal
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gestiona OneSignal: inicialización, permiso de notificaciones y
 * registro del player id en profiles.onesignal_player_id para que la
 * API de OneSignal pueda enviar push a cada usuario.
 */
object OneSignalManager {
    private const val TAG = "OneSignal"
    // El APP_ID es público: va dentro del APK, no es un secreto.
    // (La REST_API_KEY sólo vive en ~/Arelove-secrets/onesignal.txt)
    private const val APP_ID = "b8289f2b-6e4b-4cba-9ae7-8f745057c824"

    // requestPermission() es suspend: se lanza en scope propio porque
    // onCreate() de Application no es una corrutina.
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        OneSignal.initWithContext(context, APP_ID)
        alcance.launch {
            try {
                OneSignal.Notifications.requestPermission(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error pidiendo permiso de notificaciones", e)
            }
        }
    }

    /** Guarda el player id de este dispositivo en profiles. */
    suspend fun registrarPlayerId() {
        try {
            val playerId = OneSignal.User.pushSubscription.id
            if (playerId.isNullOrEmpty()) {
                Log.w(TAG, "Player ID vacío")
                return
            }

            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                ?: return

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("onesignal_player_id", playerId) }
            ) {
                filter { eq("id", userId) }
            }

            Log.d(TAG, "Player ID registrado: $playerId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando Player ID", e)
        }
    }

    /** Desvincula el dispositivo al cerrar sesión. */
    fun desregistrar() {
        OneSignal.logout()
    }
}
