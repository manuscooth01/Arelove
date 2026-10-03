package com.arelove.app.notifications

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arelove.app.R
import com.onesignal.notifications.INotificationReceivedEvent
import com.onesignal.notifications.INotificationServiceExtension

/**
 * Extensión de notificación de OneSignal 5.x (interfaz
 * INotificationServiceExtension; en 4.x era la clase NotificationServiceExtension,
 * que ya no existe). Se registra en AndroidManifest con el meta-data
 * com.onesignal.NotificationServiceExtension.
 *
 * Aplica el icono y el color de marca a cada notificación entrante: al no
 * llamar a preventDefault(), OneSignal la muestra igual pero con el
 * extender aplicado. Fallback: ic_notif_message (genérico).
 * La Edge Function envía small_icon + android_accent_color por tipo (like/match/mensaje).
 */
class AreloveNotificationService : INotificationServiceExtension {

    override fun onNotificationReceived(event: INotificationReceivedEvent) {
        val contexto = event.context
        event.notification.setExtender(object : NotificationCompat.Extender {
            override fun extend(builder: NotificationCompat.Builder): NotificationCompat.Builder {
                return builder
                    .setSmallIcon(R.drawable.ic_notif_message)
                    .setColor(ContextCompat.getColor(contexto, R.color.primary))
            }
        })
    }
}