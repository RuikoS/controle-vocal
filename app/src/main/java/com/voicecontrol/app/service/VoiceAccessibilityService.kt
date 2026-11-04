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
    ): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(fromXRatio * width, fromYRatio * height)
            lineTo(toXRatio * width, toYRatio * height)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()

        // Le troisième paramètre est le Handler qui recevra le compte rendu :
        // null signifie « celui du service », ce qui nous convient.
        return dispatchGesture(gesture, null, null)
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
    }
}
