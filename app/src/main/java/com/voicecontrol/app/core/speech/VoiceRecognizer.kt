package com.voicecontrol.app.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Un moteur de reconnaissance vocale, vu de l'extérieur.
 *
 * L'interface existe pour que le reste du code ne connaisse pas
 * `SpeechRecognizer`, qui est une API Android tordue à utiliser : appels
 * obligatoires sur le thread principal, arrêt systématique après chaque phrase,
 * codes d'erreur numériques.
 *
 * IMPORTANT — thread : [start] doit être appelé depuis le thread principal,
 * car `SpeechRecognizer` s'y attache et y livre ses callbacks.
 */
interface VoiceRecognizer {

    /** Flux d'événements : ce que le moteur entend et ce qui lui arrive. */
    val events: Flow<SpeechEvent>

    /** Démarre l'écoute et la relance toute seule après chaque silence. */
    fun start()

    /** Arrête l'écoute. Conçu pour pouvoir être suivi d'un nouveau [start]. */
    fun stop()

    /** Libère définitivement les ressources. L'instance ne sert plus après. */
    fun release()
}
