package com.arelove.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.arelove.app.R
import com.onesignal.OneSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Gestiona OneSignal: inicialización, permiso de notificaciones, canales.
 * El registro de player id se delega a SupabaseClientExt (pendiente fix compilación).
 * El manejo de clicks en notificaciones se hace vía Intent extras en MainActivity/onNewIntent.
 */
object OneSignalManager {
    private const val TAG = "OneSignal"
    private const val APP_ID = "b8289f2b-6e4b-4cba-9ae7-8f745057c824"

    // Scope para corrutinas de init/permiso
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
}