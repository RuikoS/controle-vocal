package com.voicecontrol.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voicecontrol.app.core.actions.VoiceAction

/**
 * Service d'accessibilité : c'est lui qui donne à l'application le pouvoir
 * d'agir réellement sur le téléphone.
 *
 * Il exécute deux familles d'actions :
 *  - les **actions globales** du système (retour, accueil, applications
 *    récentes, verrouillage), pour lesquelles Android fournit une API directe ;
 *  - les **gestes**, dessinés comme un doigt le ferait (`dispatchGesture`), ce
 *    qui permet de glisser et de faire défiler dans n'importe quelle
 *    application.
 *
 * Rappel : ce service ne peut pas être activé par l'application. L'utilisateur
 * doit l'activer lui-même dans Réglages > Accessibilité — et sur un APK installé
 * à la main, débloquer au préalable les « paramètres restreints ».
 */
class VoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Le système vient de nous connecter : on devient utilisable.
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nous n'observons pas encore l'écran : les actions actuelles n'en ont
        // pas besoin. Ce sera le cas si l'on veut, un jour, viser un élément
        // précis plutôt que glisser au hasard.
    }

    override fun onInterrupt() {
        // Appelé par Android quand il faut interrompre un retour en cours.
        // Rien à faire ici : nos actions sont instantanées.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // L'utilisateur a désactivé le service : on oublie l'instance.
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Exécute l'action demandée.
     *
     * @return vrai si le système a accepté l'action. Faux en cas d'échec — le
     *         verrouillage, par exemple, n'existe qu'à partir d'Android 9.
     */
    fun perform(action: VoiceAction): Boolean = when (action) {
        VoiceAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        VoiceAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        VoiceAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)

        VoiceAction.LockScreen ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                // Le verrouillage à la voix n'existe qu'à partir d'Android 9.
                false
            }

        // « glisse à droite » : le doigt va vers la droite. Petits gestes
        // (un peu plus de la moitié de l'écran) : ce sont des coups de doigt,
        // pas des défilements de page.
        VoiceAction.SwipeRight -> swipe(
            fromXRatio = 0.30f, fromYRatio = 0.50f,
            toXRatio = 0.80f, toYRatio = 0.50f,
            durationMillis = FLICK_DURATION_MILLIS,
        )
        VoiceAction.SwipeLeft -> swipe(
            fromXRatio = 0.80f, fromYRatio = 0.50f,
            toXRatio = 0.30f, toYRatio = 0.50f,
            durationMillis = FLICK_DURATION_MILLIS,
        )
        VoiceAction.SwipeUp -> swipe(
            fromXRatio = 0.50f, fromYRatio = 0.70f,
            toXRatio = 0.50f, toYRatio = 0.30f,
            durationMillis = FLICK_DURATION_MILLIS,
        )
        VoiceAction.SwipeDown -> swipe(
            fromXRatio = 0.50f, fromYRatio = 0.30f,
            toXRatio = 0.50f, toYRatio = 0.70f,
            durationMillis = FLICK_DURATION_MILLIS,
        )

        // « fais défiler vers le bas » : on veut voir plus bas dans la page,
        // donc le contenu monte, donc le doigt monte. Geste plus long que les
        // précédents : c'est un vrai défilement, pas un coup de doigt.
        VoiceAction.ScrollDown -> swipe(
            fromXRatio = 0.50f, fromYRatio = 0.85f,
            toXRatio = 0.50f, toYRatio = 0.15f,
            durationMillis = SCROLL_DURATION_MILLIS,
        )
        VoiceAction.ScrollUp -> swipe(
            fromXRatio = 0.50f, fromYRatio = 0.15f,
            toXRatio = 0.50f, toYRatio = 0.85f,
            durationMillis = SCROLL_DURATION_MILLIS,
        )

        // ---- Gestes système ----

        // Le volet se tire depuis le bord supérieur : on part du tout haut pour
        // que le système reconnaisse le geste de la barre d'état.
        VoiceAction.OpenNotifications -> swipe(
            fromXRatio = 0.50f, fromYRatio = 0.005f,
            toXRatio = 0.50f, toYRatio = 0.60f,
            durationMillis = PULL_DURATION_MILLIS,
        )

        VoiceAction.QuickSettings -> twoStepPull()

        VoiceAction.Screenshot ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                // La capture par accessibilité n'existe que depuis Android 11.
                false
            }

        VoiceAction.PowerMenu -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

        VoiceAction.TapCenter -> tap(0.5f, 0.5f)
        VoiceAction.LongPress -> longPress(0.5f, 0.5f)
        VoiceAction.DoubleTap -> doubleTap(0.5f, 0.5f)

        // Vers le haut de la page : le doigt descend, le contenu monte.
        VoiceAction.ScrollToTop -> repeatedSwipe(fromYRatio = 0.25f, toYRatio = 0.85f)
        VoiceAction.ScrollToBottom -> repeatedSwipe(fromYRatio = 0.75f, toYRatio = 0.15f)

        VoiceAction.ZoomIn -> pinch(outward = true)
        VoiceAction.ZoomOut -> pinch(outward = false)

        // Le volume et les touches média ne passent pas par l'accessibilité :
        // c'est le gestionnaire audio qui s'en charge, sans aucune permission.
        VoiceAction.VolumeUp,
        VoiceAction.VolumeDown,
        VoiceAction.VolumeMute,
        VoiceAction.MediaPlayPause,
        VoiceAction.MediaNext,
        VoiceAction.MediaPrevious,
        -> false
    }

    /**
     * Écrit [text] dans le champ de saisie qui a le focus.
     *
     * On écrit directement dans le champ plutôt que de simuler des frappes sur
     * le clavier : c'est bien plus simple, bien plus fiable, et cela fonctionne
     * avec n'importe quel clavier — y compris ceux dont la disposition nous est
     * inconnue.
     *
     * Attention : cela REMPLACE le contenu du champ, et non l'ajoute. Dicter
     * dans un champ vide est le cas courant ; compléter un texte existant
     * demanderait de décider où insérer, ce qui est un autre sujet.
     *
     * @return faux si aucun champ de saisie n'est actif, ou si le champ refuse
     *         l'écriture.
     */
    fun typeText(text: String): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Dessine un glissement de doigt à l'écran.
     *
     * Les positions sont exprimées en proportion de l'écran (0 à 1) : le geste
     * s'adapte ainsi à n'importe quelle taille et à n'importe quelle densité,
     * sans calcul de pixels dans le reste du code.
     */
    private fun swipe(
        fromXRatio: Float,
        fromYRatio: Float,
        toXRatio: Float,
        toYRatio: Float,
        durationMillis: Long,
    ): Boolean = dispatchSingleStroke(
        fromXRatio = fromXRatio,
        fromYRatio = fromYRatio,
        toXRatio = toXRatio,
        toYRatio = toYRatio,
        start = 0L,
        duration = durationMillis,
    )

    /** Un appui simple : un trait de longueur nulle, sur une durée très courte. */
    private fun tap(xRatio: Float, yRatio: Float): Boolean =
        dispatchSingleStroke(xRatio, yRatio, xRatio, yRatio, 0L, TAP_DURATION_MILLIS)

    /** Un appui long : même chose, mais le doigt reste posé. */
    private fun longPress(xRatio: Float, yRatio: Float): Boolean =
        dispatchSingleStroke(xRatio, yRatio, xRatio, yRatio, 0L, LONG_PRESS_DURATION_MILLIS)

    /** Deux appuis successifs : deux traits décalés dans le temps. */
    private fun doubleTap(xRatio: Float, yRatio: Float): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(stroke(xRatio, yRatio, xRatio, yRatio, 0L, TAP_DURATION_MILLIS))
            .addStroke(stroke(xRatio, yRatio, xRatio, yRatio, TAP_GAP_MILLIS, TAP_DURATION_MILLIS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Deux tirages successifs depuis le haut : le second ouvre les réglages
     * rapides complets.
     *
     * Les deux traits sont dans le même geste, décalés dans le temps : deux
     * appels séparés pourraient se chevaucher.
     */
    private fun twoStepPull(): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(stroke(0.5f, 0.005f, 0.5f, 0.55f, 0L, PULL_DURATION_MILLIS))
            .addStroke(
                stroke(
                    fromXRatio = 0.5f, fromYRatio = 0.005f,
                    toXRatio = 0.5f, toYRatio = 0.75f,
                    start = PULL_DURATION_MILLIS + PULL_GAP_MILLIS,
                    duration = PULL_DURATION_MILLIS,
                ),
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Défilement répété, pour aller d'un bout à l'autre d'une page. */
    private fun repeatedSwipe(fromYRatio: Float, toYRatio: Float): Boolean {
        val builder = GestureDescription.Builder()
        repeat(SCROLL_REPEAT_COUNT) { index ->
            builder.addStroke(
                stroke(
                    fromXRatio = 0.5f, fromYRatio = fromYRatio,
                    toXRatio = 0.5f, toYRatio = toYRatio,
                    start = index * SCROLL_STEP_GAP_MILLIS,
                    duration = SCROLL_DURATION_MILLIS,
                ),
            )
        }
        return dispatchGesture(builder.build(), null, null)
    }

    /**
     * Pincement à deux doigts : les doigts s'écartent pour zoomer, se rapprochent
     * pour dézoomer.
     *
     * Les deux doigts sont simulés par deux traits verticaux symétriques, joués
     * en parallèle — c'est ainsi qu'Android représente un pincement.
     */
    private fun pinch(outward: Boolean): Boolean {
        val inner = if (outward) 0.45f else 0.25f
        val outer = if (outward) 0.25f else 0.45f

        val gesture = GestureDescription.Builder()
            .addStroke(stroke(0.5f, inner, 0.5f, outer, 0L, PINCH_DURATION_MILLIS))
            .addStroke(stroke(0.5f, 1f - inner, 0.5f, 1f - outer, 0L, PINCH_DURATION_MILLIS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Exécute un geste d'un seul trait. */
    private fun dispatchSingleStroke(
        fromXRatio: Float,
        fromYRatio: Float,
        toXRatio: Float,
        toYRatio: Float,
        start: Long,
        duration: Long,
    ): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(stroke(fromXRatio, fromYRatio, toXRatio, toYRatio, start, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Construit un trait de geste, en proportion de l'écran. */
    private fun stroke(
        fromXRatio: Float,
        fromYRatio: Float,
        toXRatio: Float,
        toYRatio: Float,
        start: Long,
        duration: Long,
    ): GestureDescription.StrokeDescription {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(fromXRatio * width, fromYRatio * height)
            lineTo(toXRatio * width, toYRatio * height)
        }

        return GestureDescription.StrokeDescription(path, start, duration)
    }

    companion object {
        /**
         * Instance actuellement connectée, ou null si le service est désactivé.
         * C'est par elle que passent les actions demandées à la voix.
         */
        @Volatile
        var instance: VoiceAccessibilityService? = null
            internal set

        /** Un coup de doigt : rapide, sur environ la moitié de l'écran. */
        private const val FLICK_DURATION_MILLIS = 250L

        /** Un défilement de page : plus long et plus ample qu'un coup de doigt. */
        private const val SCROLL_DURATION_MILLIS = 350L

        /** Un appui : le doigt touche brièvement. */
        private const val TAP_DURATION_MILLIS = 60L

        /** Un appui long : le doigt reste posé le temps qu'il faut. */
        private const val LONG_PRESS_DURATION_MILLIS = 700L

        /** Écart entre les deux appuis d'un double appui. */
        private const val TAP_GAP_MILLIS = 140L

        /** Tirage du volet depuis le bord supérieur. */
        private const val PULL_DURATION_MILLIS = 280L

        /** Écart entre les deux tirages des réglages rapides. */
        private const val PULL_GAP_MILLIS = 350L

        /** Nombre de défilements pour parcourir une page entière. */
        private const val SCROLL_REPEAT_COUNT = 5

        /** Écart entre deux défilements successifs. */
        private const val SCROLL_STEP_GAP_MILLIS = 450L

        /** Durée du pincement à deux doigts. */
        private const val PINCH_DURATION_MILLIS = 320L
    }
}
