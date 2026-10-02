package com.arelove.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.arelove.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Servicio que recibe notificaciones push de Firebase.
 * Crea el canal de notificaciones y muestra la notificación al usuario.
 */
class AreloveFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // El token cambió. Lo guardamos en la BD.
        // No podemos llamar suspend aquí directamente, hay que usar
        // un scope. Ver nota abajo.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: return
        val body = message.notification?.body ?: return

        mostrarNotificacion(title, body)
    }

    private fun mostrarNotificacion(titulo: String, cuerpo: String) {
        val canalId = "arelove_general"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                canalId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(canal)
        }

        val notificacion = NotificationCompat.Builder(this, canalId)
            .setSmallIcon(R.drawable.ic_logo_heart)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notificacion)
    }
}
