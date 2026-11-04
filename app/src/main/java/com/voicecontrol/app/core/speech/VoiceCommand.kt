package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceAction

/**
 * Une commande du vocabulaire : la formulation principale, les autres
 * formulations acceptées, et l'action déclenchée.
 *
 * C'est le lien entre ce que l'on dit et ce que le téléphone fait : il n'existe
 * qu'à un seul endroit, pour qu'on ne puisse pas ajouter une phrase sans décider
 * de son effet.
 *
 * @property phrase la formulation de référence, celle qu'on montre à l'écran.
 * @property action ce que la phrase déclenche.
 * @property aliases autres façons de le dire, acceptées à l'identique. Elles
 *           servent surtout aux mots anglais (« swipe ») que le moteur en ligne
 *           transcrit sans difficulté, là où le modèle embarqué les massacrait.
 */
data class VoiceCommand(
    val phrase: String,
    val action: VoiceAction,
    val aliases: List<String> = emptyList(),
)
