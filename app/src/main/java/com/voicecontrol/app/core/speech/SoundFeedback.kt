package com.voicecontrol.app.core.speech

/**
 * Les sons de réveil proposés à l'utilisateur.
 *
 * L'énumération ne contient AUCUNE référence Android : les sons réels sont des
 * tonalités du système, choisies dans la couche qui les joue. Ici, on ne décide
 * que de l'identité du son et de son nom.
 */
enum class WakeSound {

    /** Bip court et sec : le plus neutre, celui par défaut. */
    Beep,

    /** Petit « pip » discret, moins agressif dans le calme. */
    Pip,

    /** Double tonalité montante, plus facile à percevoir en milieu bruyant. */
    Acknowledge,

    /** Tonalité descendante : convient si l'on préfère un son plus grave. */
    Negative,

    /** Sonnerie courte et plus longue, pour les environnements bruyants. */
    Alert,

    /** Aucun son : le mot-clé est alors confirmé uniquement par l'écran. */
    Silent,
    ;

    companion object {
        val DEFAULT = Beep
    }
}

/**
 * Les sons fixes de l'application, qui ne se choisissent pas.
 *
 * Ils répondent à une autre question que le son de réveil : celui-ci dit
 * « parle maintenant », ceux-là disent « c'est fait » ou « je n'ai pas pu ».
 */
enum class FeedbackCue {

    /** La commande a été comprise, et l'action a été confiée au système. */
    Success,

    /** Rien de compris, ou action impossible à exécuter. */
    Failure,
}

/**
 * Les sons que l'application produit.
 *
 * Le son de réveil n'est pas décoratif : entre le moment où l'on dit « Gregor »
 * et celui où le moteur de commande prend le micro, il s'écoule un court
 * instant, et la parole prononcée pendant cet intervalle serait perdue. Le bip
 * dit exactement quand parler.
 *
 * Les sons de retour, eux, sont joués alors que le moteur en ligne a cessé
 * d'écouter : ils ne peuvent donc pas être pris pour une commande.
 */
interface SoundFeedback {

    /** Joue le bip d'activation choisi dans les réglages. */
    fun playWakeSound()

    /** Joue le son de retour correspondant au résultat de la commande. */
    fun play(cue: FeedbackCue)

    /** Libère les ressources audio. */
    fun release()
}
