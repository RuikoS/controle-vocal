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

    // ---- Gestes système ----

    /** Volet des notifications, tiré depuis le bord supérieur de l'écran. */
    OpenNotifications,

    /** Réglages rapides : deux tirages successifs depuis le haut. */
    QuickSettings,

    /** Capture d'écran (Android 11+). */
    Screenshot,

    /** Menu d'alimentation du système. */
    PowerMenu,

    /** Appui simple au centre de l'écran. */
    TapCenter,

    /** Appui long au centre. */
    LongPress,

    /** Double appui au centre. */
    DoubleTap,

    /** Remonter tout en haut de la page, par défilements répétés. */
    ScrollToTop,

    /** Descendre tout en bas de la page. */
    ScrollToBottom,

    /** Zoom avant, par pincement à deux doigts. */
    ZoomIn,

    /** Zoom arrière. */
    ZoomOut,

    // ---- Média et volume (aucune permission nécessaire) ----

    VolumeUp,
    VolumeDown,
    VolumeMute,
    MediaPlayPause,
    MediaNext,
    MediaPrevious,
}
