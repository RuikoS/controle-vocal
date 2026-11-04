package com.voicecontrol.app.data.source

import android.content.Context
import android.content.Intent
import com.voicecontrol.app.core.actions.ActionPerformer
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
class AndroidActionPerformer(private val context: Context) : ActionPerformer {

    private val installedApps = InstalledApps(context)

    override fun perform(intent: VoiceIntent): Boolean = when (intent) {
        is VoiceIntent.Execute -> withAccessibility { it.perform(intent.action) }

        is VoiceIntent.LaunchApp -> launchApp(intent.spokenName)

        is VoiceIntent.TypeText -> withAccessibility { it.typeText(intent.text) }
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
