package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceAction

/**
 * Une formulation **apprise de l'utilisateur** : une phrase qu'il a prononcée,
 * que l'application n'a pas comprise, et qu'il a lui-même rattachée à une action.
 *
 * C'est le seul apprentissage possible ici, et c'est le plus utile. On ne
 * réentraîne pas le modèle acoustique — cela demanderait des centaines d'heures
 * d'audio et un GPU — mais on apprend **le vocabulaire de la personne** : ses
 * tournures, ses raccourcis, sa façon de désigner un geste.
 *
 * Une formulation apprise est prioritaire sur toute autre interprétation :
 * l'utilisateur a explicitement dit ce que sa phrase voulait dire.
 */
data class LearnedAlias(
    val phrase: String,
    val action: VoiceAction,
    /**
     * Combien de fois cette association a servi.
     *
     * C'est la fréquence qui départagera deux formulations également
     * ressemblantes : celle qui sert le plus souvent est celle qu'on voulait.
     */
    val useCount: Int = 1,
)
