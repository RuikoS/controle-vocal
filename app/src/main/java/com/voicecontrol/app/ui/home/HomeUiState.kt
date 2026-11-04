package com.voicecontrol.app.ui.home

import com.voicecontrol.app.core.settings.AppSettings
import com.voicecontrol.app.core.speech.ListeningState
import com.voicecontrol.app.core.speech.WakeSound

/**
 * Représentation COMPLÈTE de ce que l'écran d'accueil doit afficher.
 *
 * Toute la logique de l'écran se résume à produire un objet de ce type :
 * la partie Compose ne fait plus que le dessiner, sans calcul ni condition
 * métier. C'est le principe du state hoisting et c'est ce qui rend l'UI testable.
 *
 * @property serviceRunning vrai si le service d'écoute tourne réellement.
 * @property microphoneGranted vrai si l'autorisation micro est accordée.
 * @property accessibilityEnabled vrai si le service d'accessibilité est activé.
 * @property notificationsGranted vrai si les notifications sont autorisées.
 * @property listening état de la reconnaissance vocale (écoute, transcription, panne).
 * @property appVersion version installée, affichée en bas de l'écran. Sert à
 *           savoir sans ambiguïté quelle version de l'application on teste.
 * @property wakeSound son joué quand le mot-clé est reconnu.
 * @property wakeWord mot-clé qui réveille l'application.
 * @property pendingCorrection phrase non comprise, en attente d'être rattachée
 *           à une action par l'utilisateur.
 */
data class HomeUiState(
    val serviceRunning: Boolean = false,
    val microphoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val listening: ListeningState = ListeningState(),
    val appVersion: String = "",
    val wakeSound: WakeSound = WakeSound.DEFAULT,
    val wakeWord: String = AppSettings.DEFAULT_WAKE_WORD,
    val pendingCorrection: String? = null,
) {

    /** Les trois autorisations sont-elles réunies ? Sans elles, on ne peut pas démarrer. */
    val permissionsGranted: Boolean
        get() = microphoneGranted && accessibilityEnabled && notificationsGranted
}
