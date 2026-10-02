package com.arelove.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.onesignal.OneSignal
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gestiona OneSignal: inicialización, permiso de notificaciones, canales
 * y registro del player id en profiles.onesignal_player_id para que la
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
        crearCanales(context)
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

    /**
     * Crea los 3 canales de notificación con importancia y sonido distintos.
     * Android conserva los canales entre sesiones: si cambia la importancia
     * de un canal hay que borrarlo y crearlo de nuevo (o reinstalar la app).
     * OneSignal sólo usa estos canales si el payload lleva android_channel_id
     * con el id del canal ("mensajes", "matches" o "likes").
     */
    private fun crearCanales(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        val sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Mensajes del chat: máximo destello y vibración
        manager.createNotificationChannel(
            NotificationChannel(
                "mensajes",
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(sonido, audio)
                enableVibration(true)
            }
        )

        // Matches: mismo destello que los mensajes
        manager.createNotificationChannel(
            NotificationChannel(
                "matches",
                context.getString(R.string.notif_channel_matches),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(sonido, audio)
                enableVibration(true)
            }
        )

        // Likes: importancia normal y sin vibración (sonido más discreto)
        manager.createNotificationChannel(
            NotificationChannel(
                "likes",
                context.getString(R.string.notif_channel_likes),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(sonido, audio)
                enableVibration(false)
            }
        )
    }

    /**
     * Guarda el player id de este dispositivo en profiles para el usuario
     * con sesión activa. Se llama al abrir la app con sesión, tras login y
     * al terminar el asistente: así el valor pertenece siempre al usuario
     * actual y no se queda con el del anterior.
     */
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

    /**
     * Borra el player id de profiles del usuario que cierra sesión
     * (ANTES del signOut, mientras su sesión sigue viva). Así el player id
     * del dispositivo no se queda vinculado a dos cuentas a la vez.
     */
    suspend fun desregistrar() {
        try {
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                ?: return

            SupabaseClient.client.postgrest["profiles"].update(
                buildJsonObject { put("onesignal_player_id", JsonNull) }
            ) {
                filter { eq("id", userId) }
            }

            Log.d(TAG, "Player ID borrado de la BD")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error borrando Player ID", e)
        }
    }
}
