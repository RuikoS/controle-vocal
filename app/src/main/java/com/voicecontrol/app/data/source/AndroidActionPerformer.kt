package com.voicecontrol.app.data.source

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.voicecontrol.app.core.actions.ActionPerformer
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.actions.VoiceIntent
import com.voicecontrol.app.service.VoiceAccessibilityService

/**
 * Exécute les intentions sur le téléphone, avec les deux moyens dont dispose
 * Android :
 *
 *  - le **service d'accessibilité** pour tout ce qui touche à l'écran : gestes,
 *    actions du système, écriture dans un champ de saisie ;
 *  - le **gestionnaire de paquets** pour lancer une application, ce qui ne
 *    demande aucun droit particulier — juste de pouvoir la voir.
 *
 * Son intérêt est de garder la logique vocale (dans `core`) totalement
 * ignorante d'Android.
 *
 * Si `perform` renvoie faux, la cause est presque toujours l'une de celles-ci :
 * service d'accessibilité non activé, application introuvable, ou aucun champ
 * de saisie actif.
 */
class AndroidActionPerformer(
    private val context: Context,
    catalog: VocabularyCatalog? = null,
) : ActionPerformer {

    private val installedApps = InstalledApps(context, catalog)

    override fun perform(intent: VoiceIntent): Boolean = when (intent) {
        is VoiceIntent.Execute -> performAction(intent.action)

        is VoiceIntent.LaunchApp -> launchApp(intent.spokenName)

        is VoiceIntent.TypeText -> withAccessibility { it.typeText(intent.text) }
    }

    /**
     * Les actions passent soit par le service d'accessibilité (tout ce qui touche
     * à l'écran), soit par le gestionnaire audio (volume et touches média), qui
     * ne demande aucune permission.
     */
    private fun performAction(action: VoiceAction): Boolean = when (action) {
        VoiceAction.VolumeUp -> adjustVolume(adjustment = AudioManager.ADJUST_RAISE)
        VoiceAction.VolumeDown -> adjustVolume(adjustment = AudioManager.ADJUST_LOWER)
        VoiceAction.VolumeMute -> muteSound()
        VoiceAction.MediaPlayPause -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        VoiceAction.MediaNext -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        VoiceAction.MediaPrevious -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

        else -> withAccessibility { it.perform(action) }
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private fun adjustVolume(adjustment: Int): Boolean {
        val audio = audioManager() ?: return false
        return runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI)
            true
        }.getOrDefault(false)
    }

    private fun muteSound(): Boolean {
        val audio = audioManager() ?: return false
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Envoie une touche média au lecteur en cours.
     *
     * On simule l'appui et le relâchement : un seul des deux ne déclenche rien.
     */
    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val audio = audioManager() ?: return false
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
    }

    /** Toutes les actions d'écran passent par le service connecté, ou échouent. */
    private inline fun withAccessibility(block: (VoiceAccessibilityService) -> Boolean): Boolean {
        val service = VoiceAccessibilityService.instance ?: return false
        return block(service)
    }

    private fun launchApp(spokenName: String): Boolean {
        val target = installedApps.resolve(spokenName) ?: return false
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(target.packageName)
            ?: return false

        // Obligatoire depuis un contexte qui n'est pas une Activity.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }
}
