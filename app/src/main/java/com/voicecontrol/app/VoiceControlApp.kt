package com.voicecontrol.app

import android.app.Application
import com.voicecontrol.app.core.notifications.NotificationHelper
import com.voicecontrol.app.core.settings.AppSettings
import com.voicecontrol.app.data.LearningRepository
import com.voicecontrol.app.data.local.LearnedPhraseEntity
import com.voicecontrol.app.data.source.VocabularyCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Point d'entrée de l'application.
 *
 * Elle porte les objets qui doivent exister en un seul exemplaire : le canal de
 * notification, les réglages, et la base de ce que l'application apprend.
 * L'écran les modifie, le service les applique, sans qu'ils aient à se connaître.
 *
 * C'est le degré de « partage » le plus simple qui soit ; une vraie injection de
 * dépendances serait du luxe inutile à cette taille de projet.
 */
class VoiceControlApp : Application() {

    /** Portée de vie de l'application, pour les écritures en base. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Créés à la première demande, une seule fois. */
    val settings: AppSettings by lazy { AppSettings(this) }
    val learning: LearningRepository by lazy { LearningRepository(this, appScope) }
    val vocabulary: VocabularyCatalog by lazy { VocabularyCatalog(this) }

    override fun onCreate() {
        super.onCreate()
        // Le canal doit exister avant la moindre notification, sinon Android 8+ plante.
        NotificationHelper.createChannel(this)
        migrateLegacyLearnings()
    }

    /**
     * Reprend les apprentissages de l'ancien stockage (des préférences, avant
     * l'arrivée de la base), puis efface la préférence.
     *
     * Sans cette reprise, mettre à jour l'application ferait perdre les phrases
     * déjà apprises — exactement le genre de régression qu'on ne remarque qu'une
     * fois qu'elle a coûté quelque chose.
     */
    private fun migrateLegacyLearnings() {
        val legacy = settings.takeLegacyAliases()
        if (legacy.isEmpty()) return

        appScope.launch {
            legacy.forEach { alias ->
                learning.learn(
                    phrase = alias.phrase,
                    action = alias.action,
                    source = LearnedPhraseEntity.SOURCE_IMPORT,
                )
            }
        }
    }
}
