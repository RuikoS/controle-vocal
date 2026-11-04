package com.voicecontrol.app.core.actions

/**
 * Exécute une intention sur le téléphone.
 *
 * L'interface existe pour que la logique vocale n'ait aucune idée de la façon
 * dont on agit réellement : aujourd'hui c'est le service d'accessibilité et le
 * gestionnaire de paquets, mais la logique de commandes n'aura pas à changer si
 * l'on ajoute autre chose.
 */
interface ActionPerformer {

    /**
     * Exécute l'intention demandée.
     *
     * @return vrai si l'action a été confiée au système. Faux si c'est
     *         impossible : application introuvable, aucun champ de saisie
     *         actif, ou service d'accessibilité désactivé — auquel cas aucune
     *         action n'est possible.
     */
    fun perform(intent: VoiceIntent): Boolean
}
