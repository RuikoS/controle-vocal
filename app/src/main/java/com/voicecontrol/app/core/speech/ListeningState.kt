package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceIntent

/**
 * Ce que l'écran doit afficher au sujet de l'écoute vocale.
 *
 * @property status étape courante de la boucle d'écoute.
 * @property partialText phrase en cours de reconnaissance, encore provisoire.
 * @property lastPhrase dernière phrase comprise définitivement.
 * @property error panne en cours, ou null. La couche interface la traduit
 *           en message français grâce aux ressources de traduction.
 * @property lastErrorCode code numérique brut renvoyé par Android, pour le
 *           diagnostic. Null quand il n'y en a pas.
 * @property silenceCount nombre de tours d'écoute consécutifs sans rien de
 *           compréhensible. C'est l'indicateur qui distingue « le moteur écoute
 *           et ne comprend rien » de « le moteur ne tourne pas ».
 * @property diagnostic dernière information technique du moteur (étape
 *           franchie, panne précise). Outil de mise au point, à retirer ensuite.
 * @property lastIntent dernière intention exécutée sur le téléphone.
 * @property actionFailed vrai si cette action n'a pas pu être exécutée — le
 *           service d'accessibilité est alors presque toujours en cause.
 * @property unknownPhrase phrase qui n'a pas été comprise, à rattacher à une
 *           action par l'utilisateur. C'est la matière première de
 *           l'apprentissage.
 */
data class ListeningState(
    val status: ListeningStatus = ListeningStatus.Idle,
    val partialText: String = "",
    val lastPhrase: String = "",
    val error: SpeechError? = null,
    val lastErrorCode: Int? = null,
    val silenceCount: Int = 0,
    val diagnostic: String? = null,
    val lastIntent: VoiceIntent? = null,
    val actionFailed: Boolean = false,
    val unknownPhrase: String? = null,
)

/** Étape courante de la boucle d'écoute. */
enum class ListeningStatus {
    /** Rien ne se passe : le mot-clé (ou le service) n'a pas ouvert la porte. */
    Idle,

    /** Le moteur charge son modèle : quelques secondes au tout premier lancement. */
    Preparing,

    /**
     * Le moteur écoute, mais aucune commande ne sera acceptée tant que le
     * mot-clé n'a pas été prononcé.
     */
    WaitingForWakeWord,

    /** Le moteur écoute. */
    Listening,

    /** La phrase est terminée, le moteur l'analyse. */
    Processing,

    /** Une panne est survenue. */
    Error,
}
