package com.arelove.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.arelove.app.R
import com.arelove.app.MainActivity
import com.arelove.app.SupabaseClient
import com.onesignal.OneSignal
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.eq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gestiona OneSignal: inicialización, permiso de notificaciones, canales,
 * registro del player id en profiles.onesignal_player_id y listener de clicks.
 */
object OneSignalManager {
    private const val TAG = "OneSignal"
    private const val APP_ID = "b8289f2b-6e4b-4cba-9ae7-8f745057c824"

    // Scope para corrutinas de init/permiso
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Flag para evitar registrar listener dos veces
    private var listenerRegistrado = false

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

        // Registrar listener de click (una sola vez) - OneSignal 5.x usa lambda
        if (!listenerRegistrado) {
            registrarListenerClick(context)
            listenerRegistrado = true
        }
    }

    /**
     * Registra el listener de "usuario tocó la notificación".
     * OneSignal 5.x: OneSignal.Notifications.addClickListener { result -> ... }
     */
    private fun registrarListenerClick(context: Context) {
        OneSignal.Notifications.addClickListener { result ->
            val notification = result.notification
            val additionalData = notification.additionalData

            if (additionalData == null) {
                Log.w(TAG, "Click en notificación sin additionalData")
                return@addClickListener
            }

            // Leer payload custom que enviamos desde Edge Function
            val tipo = additionalData.optString("tipo", "")
            if (tipo.isBlank()) {
                Log.w(TAG, "Click en notificación sin campo 'tipo'")
                return@addClickListener
            }

            // Construir extras para MainActivity
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("push_tipo", tipo)
                // IDs comunes - cast explícito para evitar ambigüedad de overload
                additionalData.optString("chat_id")?.let { putExtra("chat_id", it as String) }
                additionalData.optString("sender")?.let { putExtra("sender", it as String) }
                additionalData.optString("sender_nickname")?.let { putExtra("sender_nickname", it as String) }
                additionalData.optString("match_id")?.let { putExtra("match_id", it as String) }
                additionalData.optString("other_user_id")?.let { putExtra("other_user_id", it as String) }
                additionalData.optString("from_user")?.let { putExtra("from_user", it as String) }
            }

            Log.d(TAG, "Push click -> tipo=$tipo, launching MainActivity con extras")

            // Emitir al PushRouter (fire-and-forget desde Main thread)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                PushRouter.enrutar(PushRouter.fromExtras(intent.extras!!))
            }

            // Lanzar MainActivity (ella decidirá a dónde navegar)
            context.startActivity(intent)
        }
    }

    /**
     * Crea los 3 canales de notificación con importancia y sonido distintos.
     * Android conserva los canales entre sesiones.
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

        // Likes: importancia normal y sin vibración
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
     * con sesión activa.
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
                kotlinx.serialization.json.buildJsonObject { put("onesignal_player_id", playerId) }
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
     * Borra el player id de profiles del usuario que cierra sesión.
     */
    suspend fun desregistrar() {
        try {
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                ?: return

            SupabaseClient.client.postgrest["profiles"].update(
                kotlinx.serialization.json.buildJsonObject { put("onesignal_player_id", kotlinx.serialization.json.JsonNull) }
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