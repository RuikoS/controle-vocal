package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.ActionPerformer
import com.voicecontrol.app.core.actions.VoiceAction
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
 * L'application utilise **deux moteurs**, et ce choix mérite d'être expliqué :
 *  - **Veille** : un moteur *local* écoute en permanence à la recherche du seul
 *    mot-clé. C'est la partie qui tourne jour et nuit, elle doit être économe et
 *    fonctionner sans réseau.
 *  - **Commande** : dès le mot-clé reconnu, un moteur *en ligne* prend le relais.
 *    Sa précision est sans comparaison, et seule la commande quitte le téléphone.
 *
 * Le résultat d'une commande est ensuite nuancé par un **score de confiance** :
 * exécution directe, proposition de deux ou trois actions, ou demande de
 * reformulation — cette dernière alimentant l'apprentissage.
 *
 * Il n'y a volontairement AUCUN retour vocal : l'application ne parle pas.
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
    /** Faut-il un mot-clé, ou l'écoute est-elle permanente ? Observé pour réagir au changement. */
    private val activationMode: StateFlow<ActivationMode>,
    /** Fournit les formulations apprises des corrections de l'utilisateur. */
    private val learnedAliasesProvider: () -> List<LearnedAlias>,
    /** Signale qu'une formulation apprise vient de servir, pour son compteur. */
    private val onLearnedPhraseUsed: (String) -> Unit,
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
    private var activationJob: Job? = null

    /**
     * Instant jusqu'auquel les transcriptions sont ignorées.
     *
     * En écoute permanente, le téléphone émet ses propres bips : sans ce filtre,
     * il les capterait et pourrait les prendre pour des commandes.
     */
    private var ignoreResultsUntil = 0L

    /** Démarre la veille. Sans effet si elle tourne déjà. */
    fun start() {
        if (started) return
        started = true

        wakeWordJob = scope.launch { wakeWordRecognizer.events.collect(::onWakeWordEvent) }
        commandJob = scope.launch { commandRecognizer.events.collect(::onCommandEvent) }

        // Le mode d'écoute peut changer à tout moment depuis les réglages : on
        // l'observe. Le flux émet immédiatement sa valeur courante, ce qui
        // déclenche le premier démarrage.
        activationJob = scope.launch { activationMode.collect(::onActivationModeChanged) }
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
        activationJob?.cancel()
        activationJob = null

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

    /**
     * Exécute une action choisie par l'utilisateur sur l'écran.
     *
     * Sert quand le score était moyen : l'application a proposé deux ou trois
     * actions, et c'est l'utilisateur qui tranche. Son choix est enregistré côté
     * écran comme un apprentissage.
     */
    fun performFromUi(action: VoiceAction) {
        execute(VoiceIntent.Execute(action), transcript = "", learnedPhrase = null)
    }

    /**
     * Déclenchement manuel, depuis un bouton de l'écran.
     *
     * C'est l'alternative au mot-clé : on ouvre la capture de commande sans rien
     * avoir à prononcer. Utile dans le bruit, quand le mot-clé est peu fiable,
     * ou simplement quand on ne veut pas parler pour activer.
     */
    fun captureCommand() {
        if (!started) return
        // En écoute permanente, le moteur écoute déjà : il n'y a rien à déclencher.
        if (activationMode.value == ActivationMode.AlwaysListening) return

        enterCommandMode()
    }

    // ------------------------------------------------------------------
    // Passation de micro entre les deux moteurs
    // ------------------------------------------------------------------

    /** Le réglage d'écoute a changé : on bascule vers le bon moteur. */
    private fun onActivationModeChanged(newMode: ActivationMode) {
        if (!started) return

        when (newMode) {
            ActivationMode.WakeWord -> enterWakeWordMode()
            ActivationMode.AlwaysListening -> enterPermanentListening()
        }
    }

    /**
     * Écoute permanente : le moteur en ligne prend le micro et ne le rend plus.
     *
     * Pas de bip de réveil ici, et pas de fenêtre bornée : il n'y a pas de
     * « moment pour parler », on est déjà à l'écoute. C'est le mode qui coûte le
     * plus cher en batterie, d'où son statut d'option explicite.
     */
    private fun enterPermanentListening() {
        mode = Mode.Command
        windowJob?.cancel()
        windowJob = null

        switchJob?.cancel()
        switchJob = scope.launch {
            wakeWordRecognizer.stop()
            delay(MICROPHONE_HANDOVER_MILLIS)
            if (mode != Mode.Command) return@launch

            commandRecognizer.start()
            _state.update {
                it.copy(
                    status = ListeningStatus.Listening,
                    partialText = "",
                    diagnostic = null,
                    unknownPhrase = null,
                    ambiguity = null,
                )
            }
        }
    }

    private fun enterWakeWordMode() {
        mode = Mode.WakeWord
        windowJob?.cancel()
        windowJob = null

        switchJob?.cancel()
        switchJob = scope.launch {
            commandRecognizer.stop()
            // Le micro doit être réellement rendu avant d'être repris.
            delay(MICROPHONE_HANDOVER_MILLIS)
            if (mode != Mode.WakeWord) return@launch

            wakeWordRecognizer.start()
            _state.update {
                it.copy(
                    status = ListeningStatus.WaitingForWakeWord,
                    partialText = "",
                    diagnostic = null,
                    ambiguity = null,
                )
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
            // être pris pour une parole, et c'est ce qui en fait un signal fiable
            // du moment où il faut parler.
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
                    ambiguity = null,
                )
            }
        }

        // Fenêtre bornée : sans elle, l'application resterait à transcrire le
        // monde entier si l'on ne dit rien.
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

            // Réaction immédiate, sans attendre la fin de la phrase.
            is SpeechEvent.PartialResult -> {
                if (VoiceVocabulary.containsWakeWord(event.text, wakeWordProvider())) {
                    enterCommandMode()
                }
            }

            SpeechEvent.Silence -> Unit
        }
    }

    private fun onWakeWordPhrase(transcript: String) {
        val heard = transcript.trim()

        when (val interpretation = VoiceVocabulary.interpret(
            transcript = heard,
            gateOpen = false,
            wakeWord = wakeWordProvider(),
            learnedAliases = learnedAliasesProvider(),
        )) {
            VoiceVocabulary.Interpretation.WakeWord -> enterCommandMode()

            is VoiceVocabulary.Interpretation.Command -> {
                // « ordinateur, glisse à droite » dit d'une seule traite.
                applyResolution(interpretation.resolution, heard)
            }

            VoiceVocabulary.Interpretation.Unrelated -> _state.update {
                it.copy(diagnostic = "Ignoré : « $heard »")
            }
        }
    }

    // ------------------------------------------------------------------
    // Commande : on transcrit, on note, on agit.
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

            is SpeechEvent.FinalResult -> {
                // En écoute permanente, on ignore ce qui arrive juste après un
                // bip émis par le téléphone lui-même.
                if (System.currentTimeMillis() >= ignoreResultsUntil) onCommandPhrase(event.text)
            }

            is SpeechEvent.Diagnostic -> _state.update { it.copy(diagnostic = event.message) }

            is SpeechEvent.Failed -> {
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
        val resolution = VoiceVocabulary.resolve(heard, learnedAliasesProvider())

        applyResolution(resolution, heard)

        // Une seule commande par mot-clé — mais seulement s'il y a un mot-clé.
        if (activationMode.value == ActivationMode.WakeWord) {
            enterWakeWordMode()
        }

        // Le retour sonore est joué APRÈS la bascule : à cet instant, c'est le
        // moteur local qui écoute, et un bip ne peut plus être pris pour une
        // commande.
        if (resolution !is VoiceVocabulary.Resolution.Ambiguous) {
            val succeeded = resolution is VoiceVocabulary.Resolution.Single
            playFeedback(if (succeeded) FeedbackCue.Success else FeedbackCue.Failure)
        }
    }

    /**
     * Applique le résultat du score, quel qu'il soit.
     *
     * C'est le cœur du comportement demandé : haute confiance, on agit ; moyenne,
     * on propose ; basse, on demande de reformuler — et l'écran transformera la
     * correction en apprentissage.
     */
    private fun applyResolution(resolution: VoiceVocabulary.Resolution, transcript: String) {
        when (resolution) {
            is VoiceVocabulary.Resolution.Single -> {
                val succeeded = execute(
                    intent = resolution.intent,
                    transcript = transcript,
                    learnedPhrase = resolution.learnedPhrase,
                )
                // En veille (mot-clé et commande d'une traite), le retour sonore
                // doit être joué tout de suite.
                if (mode == Mode.WakeWord) {
                    playFeedback(if (succeeded) FeedbackCue.Success else FeedbackCue.Failure)
                }
            }

            is VoiceVocabulary.Resolution.Ambiguous -> _state.update {
                it.copy(
                    status = ListeningStatus.Listening,
                    partialText = "",
                    unknownPhrase = null,
                    ambiguity = Ambiguity(phrase = resolution.phrase, options = resolution.options),
                    diagnostic = "Doute — ${resolution.options.size} actions proposées",
                )
            }

            VoiceVocabulary.Resolution.None -> _state.update {
                it.copy(
                    ambiguity = null,
                    // La phrase devient la matière première de l'apprentissage.
                    unknownPhrase = transcript.ifBlank { null },
                    diagnostic = "Ignoré : « $transcript »",
                )
            }
        }
    }

    /**
     * Joue un retour sonore en s'assurant que le moteur ne l'entendra pas.
     *
     * En écoute permanente, il n'y a pas de bascule de micro entre le son et
     * l'écoute : sans cette précaution, le bip serait transcrit et pourrait
     * devenir une commande.
     */
    private fun playFeedback(cue: FeedbackCue) {
        ignoreResultsUntil = System.currentTimeMillis() + SOUND_SUPPRESSION_MILLIS
        soundFeedback.play(cue)
    }

    /** Exécute l'intention, rend compte du résultat, et dit si elle a abouti. */
    private fun execute(intent: VoiceIntent, transcript: String, learnedPhrase: String?): Boolean {
        val succeeded = performer.perform(intent)

        // Une formulation apprise qui sert gagne en importance : c'est ce
        // compteur qui départagera les prochaines ambiguïtés.
        learnedPhrase?.let(onLearnedPhraseUsed)

        _state.update {
            it.copy(
                status = if (mode == Mode.WakeWord) {
                    ListeningStatus.WaitingForWakeWord
                } else {
                    ListeningStatus.Listening
                },
                partialText = "",
                lastPhrase = transcript.ifBlank { "choix manuel" },
                error = null,
                silenceCount = 0,
                lastIntent = intent,
                actionFailed = !succeeded,
                unknownPhrase = null,
                ambiguity = null,
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

        /** Fenêtre pendant laquelle on ignore les transcriptions, après un bip. */
        const val SOUND_SUPPRESSION_MILLIS = 700L
    }
}
