package com.voicecontrol.app.core.speech

/**
 * Vocabulaire de la couche vocale : ce que la reconnaissance a le droit de nous
 * dire, et rien de plus.
 *
 * Ces types ne dépendent PAS d'Android : le code qui parle au moteur traduit ses
 * erreurs en [SpeechError]. C'est ce qui permet à l'interface d'afficher un
 * message en français sans connaître la moindre particularité du moteur.
 */
sealed interface SpeechEvent {

    /**
     * Le moteur se prépare. Avec Vosk, cela correspond au chargement du modèle
     * (et à sa recopie depuis les ressources lors du tout premier lancement),
     * ce qui peut prendre plusieurs secondes.
     */
    data object Preparing : SpeechEvent

    /** Le moteur est prêt : c'est à l'utilisateur de parler. */
    data object ReadyForSpeech : SpeechEvent

    /** Un début de voix a été détecté. */
    data object SpeechStarted : SpeechEvent

    /** Le moteur considère la phrase terminée, il analyse. */
    data object SpeechEnded : SpeechEvent

    /** Transcription provisoire, qui se précise pendant qu'on parle. */
    data class PartialResult(val text: String) : SpeechEvent

    /** Transcription définitive d'une phrase. */
    data class FinalResult(val text: String) : SpeechEvent

    /**
     * Le moteur a écouté mais n'a rien compris.
     *
     * Ce n'est PAS une panne : c'est un tour de boucle sans parole. Événement
     * indispensable au diagnostic : sans lui, un moteur qui écoute dans le vide
     * ne se distinguerait pas d'un moteur qui ne fonctionne pas du tout.
     */
    data object Silence : SpeechEvent

    /**
     * Message technique destiné au diagnostic, affiché tel quel sur l'écran.
     *
     * Volontairement non traduit et non poli : ce n'est pas un message destiné à
     * l'utilisateur, c'est un outil de mise au point. L'objectif est de savoir
     * exactement où le moteur s'arrête au lieu de le deviner. À retirer une fois
     * la reconnaissance stabilisée.
     */
    data class Diagnostic(val message: String) : SpeechEvent

    /**
     * Quelque chose s'est mal passé.
     *
     * @property error nature compréhensible de la panne.
     * @property rawCode code numérique du moteur, conservé pour le diagnostic.
     *           Null quand la panne n'a pas de code (modèle illisible, par exemple).
     */
    data class Failed(val error: SpeechError, val rawCode: Int? = null) : SpeechEvent
}

/**
 * Nature d'une panne de reconnaissance vocale.
 *
 * Volontairement en anglais et sans texte : le message affiché à l'utilisateur
 * est choisi par la couche d'interface, dans les ressources de traduction.
 */
enum class SpeechError {
    /** Aucun moteur de reconnaissance vocale disponible sur l'appareil. */
    Unavailable,

    /** Le modèle de reconnaissance n'a pas pu être chargé. */
    ModelUnavailable,

    /**
     * Le modèle est là mais le moteur n'a pas pu démarrer — par exemple parce
     * qu'un mot du vocabulaire est absent de son dictionnaire.
     */
    EngineUnavailable,

    /** L'autorisation microphone a été refusée. */
    MicrophonePermission,

    /** Le français n'est pas géré par le moteur installé. */
    LanguageUnsupported,

    /** Le moteur est déjà utilisé par autre chose. On réessaie. */
    Busy,

    /** Panne réseau, quand la reconnaissance passe par Internet. */
    Network,

    /** Le micro est inaccessible (souvent utilisé par une autre application). */
    Audio,

    /** Tout le reste. */
    Unknown,
}
