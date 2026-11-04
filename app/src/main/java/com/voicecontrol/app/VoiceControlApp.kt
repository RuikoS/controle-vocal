package com.voicecontrol.app

import android.app.Application
import com.voicecontrol.app.core.notifications.NotificationHelper
import com.voicecontrol.app.core.settings.AppSettings

/**
 * Point d'entrée de l'application.
 *
 * Elle porte les objets qui doivent exister en un seul exemplaire pour toute
 * l'application : le canal de notification, et les réglages partagés entre
 * l'écran d'accueil (qui les modifie) et le service d'écoute (qui les applique).
 *
 * C'est le degré de « partage » le plus simple qui soit ; une vraie injection de
 * dépendances serait du luxe inutile à cette taille de projet.
 */
class VoiceControlApp : Application() {

    /** Créés à la première demande, une seule fois. */
    val settings: AppSettings by lazy { AppSettings(this) }

    override fun onCreate() {
        super.onCreate()
        // Le canal doit exister avant la moindre notification, sinon Android 8+ plante.
        NotificationHelper.createChannel(this)
    }
}
