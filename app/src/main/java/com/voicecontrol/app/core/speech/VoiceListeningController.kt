package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.ActionPerformer
import com.voicecontrol.app.core.actions.VoiceIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Chef d'orchestre de l'écoute.
 *
 * L'application utilise **deux moteurs de reconnaissance**, et ce choix mérite
 * d'être expliqué :
 *
 *  - **Veille** : un moteur *local* écoute en permanence, à la recherche du seul
 *    mot-clé de réveil. C'est la partie qui tourne jour et nuit, elle doit être
 *    économe et fonctionner sans réseau — aucun service en ligne ne sait
 *    surveiller un mot en continu.
 *  - **Commande** : dès que le mot-clé est reconnu, un moteur *en ligne* prend le
 *    relais pour transcrire la commande. Sa précision est sans comparaison avec
 *    un modèle embarqué, et seule la commande quitte alors le téléphone.
 *
 * L'écran ne sait rien de tout cela : il ne voit qu'un état et des événements.
 *
 * Il n'y a volontairement AUCUN retour vocal : l'application ne parle pas. Un
 * bip signale simplement le moment où il faut parler.
 */
class VoiceListeningController(
    /** Moteur local, chargé de guetter le mot-clé. */
    private val wakeWordRecognizer: VoiceRecognizer,
    /** Moteur en ligne, chargé de transcrire la commande. */
    private val commandRecognizer: VoiceRecognizer,
    private val performer: ActionPerformer,
    private val soundFeedback: SoundFeedback,
    /** Fournit le mot-clé courant : il peut changer depuis les réglages. */
    private val wakeWordProvider: () -> String,
    /** Fournit les formulations apprises des corrections de l'utilisateur. */
    private val learnedAliasesProvider: () -> List<LearnedAlias>,
    private val scope: CoroutineScope,
) {

    /** Les deux seules choses que fait l'application : guetter, ou comprendre. */
    private enum class Mode { WakeWord, Command }

    private val _state = MutableStateFlow(ListeningState())

    /** État à afficher, exposé à l'interface. */
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    private var mode = Mode.WakeWord
    private var started = false

    private var wakeWordJob: Job? = null
    private var commandJob: Job? = null
    private var switchJob: Job? = null
    private var windowJob: Job? = null

    /** Démarre la veille. Sans effet si elle tourne déjà. */
    fun start() {
        if (started) return
        started = true

        wakeWordJob = scope.launch { wakeWordRecognizer.events.collect(::onWakeWordEvent) }
        commandJob = scope.launch { commandRecognizer.events.collect(::onCommandEvent) }

        enterWakeWordMode()
    }

    /** Arrête l'écoute mais garde le matériel : un [start] peut suivre. */
    fun stop() {
        started = false

        wakeWordJob?.cancel()
        wakeWordJob = null
        commandJob?.cancel()
        commandJob = null
        switchJob?.cancel()
        switchJob = null
        windowJob?.cancel()
        windowJob = null

        wakeWordRecognizer.stop()
        commandRecognizer.stop()
        _state.value = ListeningState()
    }

    /** Arrête tout et libère définitivement les ressources. */
    fun release() {
        stop()
        wakeWordRecognizer.release()
        commandRecognizer.release()
        soundFeedback.release()
    }

    // ------------------------------------------------------------------
    // Les deux modes, et la passation de micro entre les deux moteurs.
    // ------------------------------------------------------------------

    private fun enterWakeWordMode() {
        mode = Mode.WakeWord
        windowJob?.cancel()
        windowJob = null

        switchJob?.cancel()
        switchJob = scope.launch {
            commandRecognizer.stop()
            // Le micro doit être réellement rendu avant d'être repris : deux
            // moteurs ne peuvent pas l'ouvrir en même temps.
            delay(MICROPHONE_HANDOVER_MILLIS)
            if (mode != Mode.WakeWord) return@launch

            wakeWordRecognizer.start()
            _state.update {
                it.copy(status = ListeningStatus.WaitingForWakeWord, partialText = "", diagnostic = null)
            }
        }
    }

    private fun enterCommandMode() {
        mode = Mode.Command

        switchJob?.cancel()
        switchJob = scope.launch {
            wakeWordRecognizer.stop()
            delay(MICROPHONE_HANDOVER_MILLIS)
            if (mode != Mode.Command) return@launch

            // Le bip est joué pendant que PERSONNE n'écoute : il ne peut donc pas
            // être pris pour une parole. C'est le seul instant où c'est possible —
            // et c'est exactement pour cela qu'il indique quand parler.
            soundFeedback.playWakeSound()
            delay(SIGNAL_SETTLE_MILLIS)
            if (mode != Mode.Command) return@launch

            commandRecognizer.start()
            _state.update {
                it.copy(
                    status = ListeningStatus.Listening,
                    partialText = "",
                    diagnostic = null,
                    unknownPhrase = null,
                )
            }
        }

        // Fenêtre bornée, dans une tâche séparée pour pouvoir l'annuler sans
        // annuler la séquence ci-dessus. Sans elle, l'application resterait à
        // transcrire le monde entier si l'on ne dit rien.
        windowJob?.cancel()
        windowJob = scope.launch {
            delay(COMMAND_WINDOW_MILLIS)
            if (mode == Mode.Command) enterWakeWordMode()
        }
    }

    // ------------------------------------------------------------------
    // Veille : on ne cherche que le mot-clé.
    // ------------------------------------------------------------------

    private fun onWakeWordEvent(event: SpeechEvent) {
        // Un événement tardif du moteur local, après une passation, ne doit pas
        // polluer l'état courant.
        if (mode != Mode.WakeWord) return

        when (event) {
            SpeechEvent.Preparing -> _state.update {
                it.copy(status = ListeningStatus.Preparing, diagnostic = null)
            }

            SpeechEvent.ReadyForSpeech -> _state.update {
                it.copy(status = ListeningStatus.WaitingForWakeWord, partialText = "")
            }

            SpeechEvent.SpeechStarted,
            SpeechEvent.SpeechEnded,
            -> _state.update { it.copy(status = ListeningStatus.Processing) }

            is SpeechEvent.FinalResult -> onWakeWordPhrase(event.text)

            is SpeechEvent.Diagnostic -> _state.update { it.copy(diagnostic = event.message) }

            is SpeechEvent.Failed -> _state.update {
                it.copy(
                    status = ListeningStatus.Error,
                    error = event.error,
                    lastErrorCode = event.rawCode,
                )
            }

            // Réaction IMMÉDIATE : on n'attend pas que le moteur estime la phrase
            // terminée pour reconnaître le mot-clé. C'est ce qui supprime le
            // délai qui faisait paraître la détection capricieuse.
            is SpeechEvent.PartialResult -> {
                if (VoiceVocabulary.containsWakeWord(event.text, wakeWordProvider())) {
                    enterCommandMode()
                }
            }

            // En veille, les silences ne sont jamais affichés : on n'y verrait
            // que des trous dans une conversation.
            SpeechEvent.Silence -> Unit
        }
    }

    private fun onWakeWordPhrase(transcript: String) {
        val heard = transcript.trim()

        // gateOpen = false : sans mot-clé, RIEN ne doit s'exécuter. C'est la
        // garantie que le téléphone n'obéit pas à une phrase entendue par hasard.
        when (val interpretation = VoiceVocabulary.interpret(
            transcript = heard,
            gateOpen = false,
            wakeWord = wakeWordProvider(),
            learnedAliases = learnedAliasesProvider(),
        )) {
            VoiceVocabulary.Interpretation.WakeWord -> enterCommandMode()

            is VoiceVocabulary.Interpretation.WakeWordWithIntent -> {
                // « Gregor, ouvre Spotify » dit d'une seule traite : l'intention
                // est déjà comprise, inutile de réveiller le moteur en ligne.
                val succeeded = execute(interpretation.intent, heard)
                soundFeedback.play(if (succeeded) FeedbackCue.Success else FeedbackCue.Failure)
            }

            else -> _state.update { it.copy(diagnostic = "Ignoré : « $heard »") }
        }
    }

    // ------------------------------------------------------------------
    // Commande : on transcrit, on exécute, on rend la main.
    // ------------------------------------------------------------------

    private fun onCommandEvent(event: SpeechEvent) {
        if (mode != Mode.Command) return

        when (event) {
            SpeechEvent.ReadyForSpeech -> _state.update {
                it.copy(status = ListeningStatus.Listening, partialText = "")
            }

            SpeechEvent.SpeechStarted,
            SpeechEvent.SpeechEnded,
            -> _state.update { it.copy(status = ListeningStatus.Processing) }

            is SpeechEvent.PartialResult -> _state.update { it.copy(partialText = event.text) }

            is SpeechEvent.FinalResult -> onCommandPhrase(event.text)

            is SpeechEvent.Diagnostic -> _state.update { it.copy(diagnostic = event.message) }

            is SpeechEvent.Failed -> {
                // Rien de compréhensible : on rend la main à la veille plutôt que
                // d'insister. Les silences, eux, sont gérés par la fenêtre bornée.
                _state.update {
                    it.copy(
                        diagnostic = "Non compris — redites « " +
                            VoiceVocabulary.displayWakeWord(wakeWordProvider()) + " »",
                    )
                }
                enterWakeWordMode()
            }

            SpeechEvent.Preparing, SpeechEvent.Silence -> Unit
        }
    }

    private fun onCommandPhrase(transcript: String) {
        val heard = transcript.trim()
        val intent = VoiceVocabulary.parseIntent(heard, learnedAliasesProvider())

        val succeeded = if (intent != null) {
            execute(intent, heard)
        } else {
            // Phrase non comprise : on la transmet pour que l'utilisateur puisse
            // la rattacher lui-même à une action. C'est ainsi que l'application
            // apprend son vocabulaire.
            _state.update {
                it.copy(diagnostic = "Ignoré : « $heard »", unknownPhrase = heard)
            }
            false
        }

        // Une seule commande par mot-clé : il faut redire « Gregor ».
        enterWakeWordMode()

        // Le retour sonore est joué APRÈS la bascule : à cet instant, c'est le
        // moteur local qui écoute, et un bip ne peut donc plus être pris pour
        // une commande.
        soundFeedback.play(if (succeeded) FeedbackCue.Success else FeedbackCue.Failure)
    }

    /** Exécute l'intention, rend compte du résultat, et dit si elle a abouti. */
    private fun execute(intent: VoiceIntent, transcript: String): Boolean {
        val succeeded = performer.perform(intent)

        _state.update {
            it.copy(
                status = if (mode == Mode.WakeWord) {
                    ListeningStatus.WaitingForWakeWord
                } else {
                    ListeningStatus.Listening
                },
                partialText = "",
                lastPhrase = transcript,
                error = null,
                silenceCount = 0,
                lastIntent = intent,
                actionFailed = !succeeded,
                unknownPhrase = null,
                diagnostic = "Compris : « $transcript »",
            )
        }

        return succeeded
    }

    private companion object {
        /**
         * Temps laissé au moteur sortant pour rendre le microphone.
         * Court, mais indispensable : deux moteurs ne peuvent pas l'ouvrir ensemble.
         */
        const val MICROPHONE_HANDOVER_MILLIS = 250L

        /** Temps laissé au bip pour finir avant que le moteur en ligne n'écoute. */
        const val SIGNAL_SETTLE_MILLIS = 400L

        /** Durée maximale d'attente d'une commande après le mot-clé. */
        const val COMMAND_WINDOW_MILLIS = 8_000L
    }
}
