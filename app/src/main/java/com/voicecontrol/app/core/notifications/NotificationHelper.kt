package com.voicecontrol.app.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.voicecontrol.app.MainActivity
import com.voicecontrol.app.R

/**
 * Centralise tout ce qui concerne la notification permanente du service d'écoute.
 *
 * Un service de premier plan doit obligatoirement afficher une notification :
 * c'est le « prix » à payer pour qu'Android accepte de nous laisser tourner en
 * arrière-plan sans nous tuer.
 */
object NotificationHelper {

    /** Identifiant du canal de notification (obligatoire depuis Android 8.0). */
    const val CHANNEL_ID = "voice_control_service"

    /** Identifiant de la notification du service. */
    const val SERVICE_NOTIFICATION_ID = 1001

    /**
     * Crée le canal de notification s'il n'existe pas déjà.
     * Appelé une fois depuis [com.voicecontrol.app.VoiceControlApp].
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // IMPORTANCE_LOW : notification discrète, sans son ni vibration.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Construit la notification affichée pendant que le service tourne.
     *
     * Elle est non balayable ([setOngoing]) et ramène l'utilisateur sur l'écran
     * d'accueil quand on la touche.
     */
    fun buildServiceNotification(context: Context): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            // FLAG_IMMUTABLE est obligatoire à partir d'Android 12.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
