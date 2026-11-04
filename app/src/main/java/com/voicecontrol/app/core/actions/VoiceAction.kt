package com.voicecontrol.app.core.actions

/**
 * Les actions que l'application sait exécuter sur le téléphone.
 *
 * Elles sont volontairement peu nombreuses et sans paramètre : agir sur le
 * téléphone à la voix demande de la précision, et chaque action ajoutée dilue
 * la reconnaissance. On préfère dix gestes parfaitement fiables à cinquante
 * approximatifs.
 *
 * Ce type ne dépend pas d'Android : c'est la couche d'interface qui décide du
 * texte affiché, et le service d'accessibilité qui décide des gestes réels.
 */
enum class VoiceAction {

    /**
     * Glissement du doigt d'un côté à l'autre. Le sens décrit le **mouvement du
     * doigt** : « glisse à droite » veut dire que le doigt va vers la droite.
     */
    SwipeRight,
    SwipeLeft,
    SwipeUp,
    SwipeDown,

    /**
     * Défilement long d'une page entière. Le sens décrit ici le **contenu que
     * l'on veut voir**, pas le doigt : « fais défiler vers le bas » veut dire
     * aller plus bas dans la page, donc faire glisser le doigt vers le haut.
     */
    ScrollDown,
    ScrollUp,

    /** Bouton Retour du système. */
    Back,

    /** Écran d'accueil. */
    Home,

    /** Applications récemment utilisées. */
    Recents,

    /** Verrouillage de l'écran. */
    LockScreen,
}
