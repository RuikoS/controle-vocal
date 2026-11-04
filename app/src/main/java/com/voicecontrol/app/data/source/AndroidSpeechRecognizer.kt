package com.voicecontrol.app.data.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voicecontrol.app.core.speech.SpeechError
import com.voicecontrol.app.core.speech.SpeechEvent
import com.voicecontrol.app.core.speech.VoiceRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Implémentation de [VoiceRecognizer] au-dessus du `SpeechRecognizer` d'Android.
 *
 * CONSERVÉ COMME RÉFÉRENCE : depuis le passage à [VoskSpeechRecognizer], cette
 * classe n'est plus branchée. Elle reste le seul moyen de comparer les deux
 * moteurs, et un repli possible si le modèle hors ligne posait problème.
 * À supprimer dès que la reconnaissance hors ligne aura fait ses preuves.
 *
 * Trois particularités du système qu'il faut connaître, et qui expliquent tout
 * ce qui suit :
 *
 * 1. **Thread principal obligatoire.** Création de l'objet et callbacks s'y font,
 *    sinon rien ne fonctionne.
 * 2. **Il s'arrête tout seul.** Après chaque phrase, et aussi après quelques
 *    secondes de silence, le moteur rend la main. Pour une écoute permanente, il
 *    faut donc le relancer en boucle : c'est le rôle de [scheduleRestart].
 * 3. **Il n'existe pas forcément.** Beaucoup d'appareils n'ont aucun moteur de
 *    reconnaissance : on le vérifie avant de démarrer.
 *
 * @param scope doit s'exécuter sur le thread principal (voir point 1).
 */
class AndroidSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
) : VoiceRecognizer, RecognitionListener {

    // Buffer de secours : évite de perdre un événement si le collecteur est
    // brièvement occupé. Volontairement sans relecture (replay = 0) : un
    // événement vocal périmé n'a aucune valeur.
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    override val events: Flow<SpeechEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var restartJob: Job? = null

    override fun start() {
        if (isActive) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // Inutile de boucler : aucun moteur ne répondra jamais.
            _events.tryEmit(SpeechEvent.Failed(SpeechError.Unavailable))
            return
        }

        isActive = true
        ensureRecognizer()
        startListening()
    }

    override fun stop() {
        isActive = false
        restartJob?.cancel()
        restartJob = null
        // Les erreurs provoquées par cet arrêt volontaire seront ignorées
        // (voir le garde-fou en tête de onError).
        recognizer?.stopListening()
    }

    override fun release() {
        isActive = false
        restartJob?.cancel()
        restartJob = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@AndroidSpeechRecognizer)
            }
        }
    }

    /** Le moteur peut rester coincé en état « occupé » : on le reconstruit. */
    private fun recreateRecognizer() {
        recognizer?.destroy()
        recognizer = null
        ensureRecognizer()
    }

    private fun startListening() {
        if (!isActive) return
        recognizer?.startListening(buildRecognizerIntent())
    }

    /** Relance l'écoute après un court délai, en annulant toute relance déjà prévue. */
    private fun scheduleRestart(delayMillis: Long) {
        if (!isActive) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMillis)
            startListening()
        }
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // Sans ces deux lignes, la reconnaissance suit la langue du téléphone
            // et peut refuser le français.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, FRENCH_LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, FRENCH_LANGUAGE_TAG)

            // Transcription progressive : c'est ce qui permet d'afficher le texte
            // pendant qu'on parle plutôt qu'à la fin.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // NE JAMAIS mettre EXTRA_PREFER_OFFLINE à true ici.
            // Malgré son nom, cet extra signifie « n'utiliser QUE le moteur hors
            // ligne ». Sans le modèle français téléchargé, le moteur démarre
            // normalement, tient le micro… puis échoue à chaque cycle sans rien
            // renvoyer. C'est exactement la panne qu'on a eue : un moteur vivant
            // qui ne comprend jamais rien.
            // Si on veut le hors ligne plus tard, il faudra le proposer comme un
            // choix explicite, avec repli sur le moteur normal en cas d'échec.
        }

    // ------------------------------------------------------------------
    // Callbacks de RecognitionListener, tous sur le thread principal.
    // ------------------------------------------------------------------

    override fun onReadyForSpeech(params: Bundle?) {
        _events.tryEmit(SpeechEvent.ReadyForSpeech)
    }

    override fun onBeginningOfSpeech() {
        _events.tryEmit(SpeechEvent.SpeechStarted)
    }

    /** Niveau sonore du micro : inutile ici (sert à animer un vu-mètre). */
    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _events.tryEmit(SpeechEvent.SpeechEnded)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        firstTranscript(partialResults)?.let { _events.tryEmit(SpeechEvent.PartialResult(it)) }
    }

    override fun onResults(results: Bundle?) {
        // Une réponse peut arriver après un arrêt volontaire (pendant que la
        // synthèse vocale parle). L'ignorer évite de se relancer en boucle.
        if (!isActive) return

        val text = firstTranscript(results)
        if (text.isNullOrBlank()) {
            // Rien d'exploitable : on relance simplement l'écoute.
            _events.tryEmit(SpeechEvent.Silence)
            scheduleRestart(SILENCE_RESTART_DELAY_MILLIS)
        } else {
            // Cas normal : le contrôleur va couper l'écoute, parler, puis relancer.
            _events.tryEmit(SpeechEvent.FinalResult(text))
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(errorCode: Int) {
        // Après un arrêt volontaire, on reçoit un ERROR_CLIENT qui ne nous
        // intéresse pas : sans ce filtre, on se relancerait après chaque arrêt.
        if (!isActive) return

        if (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
            errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            // Ce n'est pas une panne, c'est du silence. On le signale quand même :
            // c'est ce qui permet de voir qu'un moteur tourne dans le vide.
            _events.tryEmit(SpeechEvent.Silence)
            scheduleRestart(SILENCE_RESTART_DELAY_MILLIS)
            return
        }

        val error = mapError(errorCode)
        // Le code brut est transmis : sans lui, une panne inattendue resterait
        // impossible à identifier.
        _events.tryEmit(SpeechEvent.Failed(error, errorCode))

        if (error == SpeechError.MicrophonePermission || error == SpeechError.LanguageUnsupported) {
            // Réessayer ne changerait rien : on s'arrête proprement.
            isActive = false
            return
        }

        if (error == SpeechError.Busy) {
            recreateRecognizer()
        }
        scheduleRestart(ERROR_RESTART_DELAY_MILLIS)
    }

    private fun firstTranscript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** Traduit les codes numériques d'Android en pannes compréhensibles. */
    private fun mapError(errorCode: Int): SpeechError = when (errorCode) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.MicrophonePermission

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> SpeechError.LanguageUnsupported

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.Busy
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.Audio

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> SpeechError.Network

        else -> SpeechError.Unknown
    }

    private companion object {
        /** Le français, explicitement. */
        const val FRENCH_LANGUAGE_TAG = "fr-FR"

        /** Après du silence, on réécoute presque tout de suite. */
        const val SILENCE_RESTART_DELAY_MILLIS = 250L

        /** Après une vraie panne, on laisse respirer le moteur. */
        const val ERROR_RESTART_DELAY_MILLIS = 1_200L
    }
}
