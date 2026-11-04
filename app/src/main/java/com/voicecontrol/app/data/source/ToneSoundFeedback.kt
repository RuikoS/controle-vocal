package com.voicecontrol.app.data.source

import android.media.AudioManager
import android.media.ToneGenerator
import com.voicecontrol.app.core.settings.AppSettings
import com.voicecontrol.app.core.speech.FeedbackCue
import com.voicecontrol.app.core.speech.SoundFeedback
import com.voicecontrol.app.core.speech.WakeSound

/**
 * Joue les sons de l'application avec les tonalités intégrées d'Android
 * (`ToneGenerator`).
 *
 * Aucun fichier son n'est embarqué : cela évite d'alourdir l'application pour
 * quelques bips, et les tonalités du système sont déjà conçues pour être
 * reconnaissables sur un haut-parleur de téléphone.
 *
 * Le son passe par le canal des notifications : si le téléphone est en
 * silencieux, aucun bip ne sortira — c'est le réglage choisi par l'utilisateur,
 * et l'écran reste le moyen de confirmation.
 */
class ToneSoundFeedback(private val settings: AppSettings) : SoundFeedback {

    /**
     * Un seul générateur, réutilisé : en créer un à chaque bip ferait cliqueter
     * la sortie audio du téléphone. Volontairement créé à la première
     * utilisation plutôt qu'au démarrage, pour ne pas mobiliser l'audio sans
     * raison.
     */
    private var toneGenerator: ToneGenerator? = null

    override fun playWakeSound() {
        playTone(wakeToneFor(settings.wakeSound.value))
    }

    override fun play(cue: FeedbackCue) {
        playTone(
            when (cue) {
                // Deux tonalités franchement différentes : on doit pouvoir
                // distinguer réussite et échec sans regarder l'écran.
                FeedbackCue.Success -> ToneDefinition(ToneGenerator.TONE_PROP_ACK, 400)
                FeedbackCue.Failure -> ToneDefinition(ToneGenerator.TONE_PROP_NACK, 400)
            },
        )
    }

    /**
     * Joue un son de réveil précis sans toucher aux réglages.
     * C'est ce qui permet à l'écran de faire écouter un son avant de l'adopter.
     */
    fun previewWakeSound(sound: WakeSound) {
        playTone(wakeToneFor(sound))
    }

    override fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun playTone(definition: ToneDefinition?) {
        if (definition == null) return

        val generator = toneGenerator
            ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT).also {
                toneGenerator = it
            }

        // Si une tonalité est déjà en cours, ou si la sortie audio est
        // indisponible, l'échec n'a aucune conséquence : le son est un confort,
        // pas une nécessité. On ne bloque donc jamais l'écoute pour lui.
        runCatching { generator.startTone(definition.toneType, definition.durationMillis) }
    }

    /** Traduit un son choisi en tonalité système. Null pour « aucun son ». */
    private fun wakeToneFor(sound: WakeSound): ToneDefinition? = when (sound) {
        WakeSound.Beep -> ToneDefinition(ToneGenerator.TONE_PROP_BEEP, 150)
        WakeSound.Pip -> ToneDefinition(ToneGenerator.TONE_CDMA_PIP, 200)
        WakeSound.Acknowledge -> ToneDefinition(ToneGenerator.TONE_PROP_ACK, 500)
        WakeSound.Negative -> ToneDefinition(ToneGenerator.TONE_PROP_NACK, 500)
        WakeSound.Alert -> ToneDefinition(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000)
        WakeSound.Silent -> null
    }

    private data class ToneDefinition(val toneType: Int, val durationMillis: Int)

    private companion object {
        /** Volume volontairement modéré : un signal, pas une alarme. */
        const val VOLUME_PERCENT = 70
    }
}
