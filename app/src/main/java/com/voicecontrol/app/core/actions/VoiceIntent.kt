package com.voicecontrol.app.core.actions

/**
 * Ce que l'utilisateur demande au téléphone, une fois la phrase comprise.
 *
 * Deux familles bien distinctes :
 *  - les **actions simples** ([Execute]) : un geste, sans rien à préciser ;
 *  - les **intentions à paramètre** ([LaunchApp], [TypeText]) : elles portent la
 *    valeur prononcée, parce qu'aucune liste fermée ne peut contenir tous les
 *    noms d'applications ni toutes les phrases que l'on peut vouloir écrire.
 *
 * C'est la transcription libre du moteur en ligne qui rend cette seconde famille
 * possible : on reçoit du texte, on l'interprète.
 */
sealed interface VoiceIntent {

    /** Exécuter une action simple, sans paramètre. */
    data class Execute(val action: VoiceAction) : VoiceIntent

    /** Ouvrir une application, désignée par le nom que l'utilisateur a prononcé. */
    data class LaunchApp(val spokenName: String) : VoiceIntent

    /** Écrire du texte dans le champ de saisie actif. */
    data class TypeText(val text: String) : VoiceIntent
}
