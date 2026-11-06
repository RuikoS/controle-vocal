package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceAction

/**
 * Dernier recours quand aucune commande locale ne correspond.
 *
 * L'implémentation réelle interroge un modèle de langage ; la logique vocale n'a
 * besoin de savoir qu'une chose : « cette phrase veut-elle dire cette action ? ».
 *
 * Deux limites à garder en tête :
 *  - le modèle ne choisit que parmi les actions **existantes** : il ajoute de la
 *    compréhension, pas des capacités ;
 *  - sa réponse est une **proposition**, jamais une exécution : le modèle peut se
 *    tromper, et une phrase mal interprétée ne doit pas verrouiller l'écran par
 *    surprise.
 */
interface AiFallback {

    /**
     * Propose une action pour une phrase non comprise, ou null.
     *
     * @return null si rien de convaincant, si le service est injoignable, ou si
     *         la réponse est illisible. L'appelant retombe alors sur la carte de
     *         correction.
     */
    suspend fun resolveAction(phrase: String): VoiceAction?
}
