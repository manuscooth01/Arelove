package com.arelove.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import com.arelove.app.LogHelper
import androidx.core.content.ContextCompat
import com.arelove.app.R
import com.onesignal.OneSignal
import com.onesignal.notifications.INotificationClickEvent
import com.onesignal.notifications.INotificationClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Gestiona OneSignal: inicialización, permiso de notificaciones, canales.
 * El registro de player id se delega a SupabaseClientExt (pendiente fix compilación).
 * El manejo de clicks en notificaciones se hace vía Intent extras en MainActivity/onNewIntent
 * y mediante un INotificationClickListener para asegurar compatibilidad con OneSignal 5.x.
 */
object OneSignalManager {
    private const val TAG = "OneSignal"
    private const val APP_ID = "b8289f2b-6e4b-4cba-9ae7-8f745057c824"

    // Scope para corrutinas de init/permiso
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        OneSignal.initWithContext(context, APP_ID)
        crearCanales(context)
        // Agregar click listener para capturar datos de notificación (OneSignal 5.x)
        OneSignal.Notifications.addClickListener(object : INotificationClickListener {
            override fun onClick(event: INotificationClickEvent) {
                val additionalData = event.notification.additionalData
                if (additionalData != null) {
                    val bundle = Bundle()
                    val iterator = additionalData.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val value = additionalData.get(key)
                        when (value) {
                            is String -> bundle.putString(key, value)
                            is Int -> bundle.putInt(key, value)
                            is Long -> bundle.putLong(key, value)
                            is Double -> bundle.putDouble(key, value)
                            is Boolean -> bundle.putBoolean(key, value)
                            else -> bundle.putString(key, value.toString())
                        }
                    }
                    LogHelper.d("OneSignal", "Click listener extras")
                    alcance.launch {
                        PushRouter.fromExtras(bundle)?.let { PushRouter.enrutar(it) }
                    }
                }
            }
        })
        alcance.launch {
            try {
                OneSignal.Notifications.requestPermission(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.e("OneSignal", "Error pidiendo permiso de notificaciones")
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