package com.arelove.app.notifications

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arelove.app.R
import com.onesignal.notifications.INotificationReceivedEvent
import com.onesignal.notifications.INotificationServiceExtension

/**
 * Extensión de notificación de OneSignal 5.x.
 * Solo aplica el color de marca (android_accent_color).
 * El small_icon lo envía la Edge Function según el tipo (like/match/mensaje).
 */
class AreloveNotificationService : INotificationServiceExtension {

    override fun onNotificationReceived(event: INotificationReceivedEvent) {
        val contexto = event.context
        event.notification.setExtender(object : NotificationCompat.Extender {
            override fun extend(builder: NotificationCompat.Builder): NotificationCompat.Builder {
                return builder
                    .setColor(ContextCompat.getColor(contexto, R.color.primary))
            }
        })
    }
}