package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.actions.VoiceIntent
import com.voicecontrol.app.core.text.TextMatcher

/**
 * Le vocabulaire de l'application : le mot-clé de réveil, les commandes sans
 * paramètre, les verbes qui en attendent un, et la façon de comprendre une phrase.
 *
 * Le rapprochement ne répond plus « oui » ou « non » mais donne un **score de
 * confiance**, qui décide de la suite :
 *  - confiance haute : on exécute ;
 *  - confiance moyenne : on propose les actions les plus probables ;
 *  - confiance basse : on demande de reformuler (et on apprend de la correction).
 *
 * HISTORIQUE — une première version donnait le vocabulaire au moteur Vosk sous
 * forme de *grammaire*. Le moteur renvoyait alors « [unk] » sur presque tout : la
 * reconnaissance s'est effondrée. La grammaire a été abandonnée au profit d'une
 * reconnaissance libre, et c'est CE fichier qui décide ce que vaut la transcription.
 */
object VoiceVocabulary {

    /**
     * Forme affichée à l'écran, avec sa majuscule. Le mot-clé se règle dans les
     * préférences : un prénom inventé n'existe pas dans le dictionnaire du modèle
     * local, qui l'écrit donc au hasard.
     */
    fun displayWakeWord(wakeWord: String): String = wakeWord.replaceFirstChar { it.uppercase() }

    /**
     * Commandes sans paramètre, en français.
     *
     * On évite les termes tactiles anglais (« swipe », « scroll ») comme
     * formulations de référence ; ils restent acceptés en synonymes, que le
     * moteur en ligne transcrit sans difficulté.
     */
    val COMMANDS: List<VoiceCommand> = listOf(
        VoiceCommand(
            phrase = "glisse à droite",
            action = VoiceAction.SwipeRight,
            aliases = listOf("swipe à droite", "glisse vers la droite"),
        ),
        VoiceCommand(
            phrase = "glisse à gauche",
            action = VoiceAction.SwipeLeft,
            aliases = listOf("swipe à gauche", "glisse vers la gauche"),
        ),
        VoiceCommand(
            phrase = "glisse en haut",
            action = VoiceAction.SwipeUp,
            aliases = listOf("swipe en haut", "swipe vers le haut"),
        ),
        VoiceCommand(
            phrase = "glisse en bas",
            action = VoiceAction.SwipeDown,
            aliases = listOf("swipe en bas", "swipe vers le bas"),
        ),
        VoiceCommand(
            phrase = "fais défiler vers le bas",
            action = VoiceAction.ScrollDown,
            aliases = listOf("défile vers le bas", "descends dans la page"),
        ),
        VoiceCommand(
            phrase = "fais défiler vers le haut",
            action = VoiceAction.ScrollUp,
            aliases = listOf("défile vers le haut", "remonte dans la page"),
        ),
        VoiceCommand(
            phrase = "retour",
            action = VoiceAction.Back,
            aliases = listOf("reviens", "en arrière"),
        ),
        VoiceCommand(
            phrase = "accueil",
            action = VoiceAction.Home,
            aliases = listOf("écran d'accueil", "maison"),
        ),
        VoiceCommand(
            phrase = "applications récentes",
            action = VoiceAction.Recents,
            aliases = listOf("applis récentes", "tâches récentes"),
        ),
        VoiceCommand(
            phrase = "verrouille",
            action = VoiceAction.LockScreen,
            aliases = listOf("verrouille l'écran", "éteins l'écran"),
        ),
    )

    /** Verbes qui annoncent le lancement d'une application (écrits sans accent : la comparaison est normalisée). */
    private val LAUNCH_VERBS = listOf("ouvre", "ouvrir", "lance", "lancer", "demarre", "demarrer")

    /** Verbes qui annoncent une dictée. */
    private val TYPE_VERBS = listOf(
        "ecris", "ecrit", "ecrire",
        "tape", "taper",
        "note", "noter",
        "saisis", "saisir",
    )

    /** Jeton renvoyé par le moteur pour une phrase qu'il n'a pas su écrire. */
    const val UNKNOWN_TOKEN = "[unk]"

    /** Ce que la phrase signifie à l'échelle du dialogue. */
    sealed interface Interpretation {
        /** Le mot-clé seul : on attend une commande. */
        data object WakeWord : Interpretation

        /** Une commande a été comprise, avec plus ou moins de certitude. */
        data class Command(val resolution: Resolution) : Interpretation

        /** Rien de reconnu. */
        data object Unrelated : Interpretation
    }

    /** Ce que la phrase signifie à l'échelle de la commande. */
    sealed interface Resolution {

        /**
         * Une action, et la certitude qu'on en a.
         *
         * @property learnedPhrase la formulation apprise qui a permis de
         *           comprendre, si c'en est une. Sert à compter son usage.
         */
        data class Single(
            val intent: VoiceIntent,
            val confidence: Float,
            val learnedPhrase: String?,
        ) : Resolution

        /** Plusieurs actions plausibles : à l'utilisateur de trancher. */
        data class Ambiguous(val phrase: String, val options: List<VoiceAction>) : Resolution

        /** Rien d'assez convaincant. */
        data object None : Resolution
    }

    /**
     * Décide ce que vaut une transcription.
     *
     * @param gateOpen vrai si le mot-clé a déjà été prononcé récemment.
     */
    fun interpret(
        transcript: String,
        gateOpen: Boolean,
        wakeWord: String,
        learnedAliases: List<LearnedAlias> = emptyList(),
    ): Interpretation {
        val words = transcript.trim().split(SPACES).filter { it.isNotBlank() }
        if (words.isEmpty()) return Interpretation.Unrelated

        // Le mot-clé est cherché PARTOUT : le moteur place souvent un mot
        // parasite avant (« euh », « bon »).
        val match = findWakeWord(words.map { TextMatcher.normalize(it) }, wakeWord)
        if (match != null) {
            val (index, length) = match
            val rest = words.drop(index + length).joinToString(" ").trim()
            if (rest.isBlank()) return Interpretation.WakeWord

            val resolution = resolve(rest, learnedAliases)
            return if (resolution == Resolution.None) {
                // Mot-clé compris, mais la suite n'est pas une commande : on
                // ouvrira quand même l'écoute, l'utilisateur redira.
                Interpretation.WakeWord
            } else {
                Interpretation.Command(resolution)
            }
        }

        if (!gateOpen) return Interpretation.Unrelated
        val resolution = resolve(transcript, learnedAliases)
        return if (resolution == Resolution.None) Interpretation.Unrelated
        else Interpretation.Command(resolution)
    }

    /**
     * Note toutes les formulations connues et décide quoi en faire.
     */
    fun resolve(transcript: String, learnedAliases: List<LearnedAlias>): Resolution {
        val heard = transcript.trim()
        if (heard.isBlank()) return Resolution.None

        // Les verbes à paramètre sont des structures, pas des ressemblances :
        // « ouvre X » et « écris Y » n'ont pas besoin d'être notés.
        parseStructuredIntent(heard)?.let { intent ->
            return Resolution.Single(intent = intent, confidence = 1f, learnedPhrase = null)
        }

        val candidates = buildList {
            learnedAliases.forEach { alias ->
                add(
                    Candidate(
                        text = alias.phrase,
                        intent = VoiceIntent.Execute(alias.action),
                        learnedPhrase = alias.phrase,
                        useCount = alias.useCount,
                    ),
                )
            }
            COMMANDS.forEach { command ->
                (listOf(command.phrase) + command.aliases).forEach { wording ->
                    add(
                        Candidate(
                            text = wording,
                            intent = VoiceIntent.Execute(command.action),
                            learnedPhrase = null,
                            useCount = 0,
                        ),
                    )
                }
            }
        }

        val scored = candidates
            .map { it to score(heard, it) }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return Resolution.None

        return when {
            best.second >= CONFIDENCE_HIGH ->
                Resolution.Single(best.first.intent, best.second, best.first.learnedPhrase)

            best.second >= CONFIDENCE_MEDIUM -> Resolution.Ambiguous(
                phrase = heard,
                options = topOptions(scored),
            )

            else -> Resolution.None
        }
    }

    /**
     * Note une formulation candidate.
     *
     * Le score part de la ressemblance des textes, puis reçoit deux bonus :
     * celui d'avoir été **apprise** (l'utilisateur l'a explicitement voulue) et
     * celui de servir souvent — c'est la fréquence qui départage les ambiguïtés.
     */
    private fun score(heard: String, candidate: Candidate): Float {
        var score = TextMatcher.similarity(heard, candidate.text)

        if (score > 0f) {
            // Un mot-clé significatif suffit souvent à viser juste : « remonte »
            // pour « remonte en haut », appris une fois.
            score = maxOf(score, keywordScore(heard, candidate.text))
        }

        if (candidate.learnedPhrase != null) {
            score += LEARNED_BONUS
        }
        if (candidate.useCount > 0) {
            score += minOf(MAX_USAGE_BONUS, candidate.useCount * USAGE_BONUS_STEP)
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Recouvrement des mots significatifs de la formulation connue.
     *
     * Tous présents : quasi-certitude. La moitié au moins : très probable.
     * En dessous, on ne se prononce pas — un mot isolé ne doit pas suffire.
     */
    private fun keywordScore(heard: String, reference: String): Float {
        val referenceWords = significantWords(reference)
        if (referenceWords.isEmpty()) return 0f

        val heardWords = TextMatcher.normalize(heard).split(' ').toSet()
        val matched = referenceWords.count { keyword ->
            heardWords.any { word -> TextMatcher.isSameWord(keyword, word) }
        }
        if (matched == 0) return 0f

        val ratio = matched.toFloat() / referenceWords.size
        return when {
            ratio >= 1f -> 0.95f
            ratio >= 0.5f -> 0.85f
            ratio >= 0.25f -> 0.65f
            else -> 0f
        }
    }

    /** Les deux ou trois actions les plus probables, sans doublon. */
    private fun topOptions(scored: List<Pair<Candidate, Float>>): List<VoiceAction> =
        scored
            .mapNotNull { (candidate, _) ->
                (candidate.intent as? VoiceIntent.Execute)?.action
            }
            .distinct()
            .take(MAX_OPTIONS)

    /**
     * Transforme une phrase en intention **structurée**, ou null.
     *
     * Seuls les verbes à paramètre sont traités ici : « écris … » et « ouvre … ».
     * Le reste passe par le score.
     */
    fun parseStructuredIntent(transcript: String): VoiceIntent? {
        val words = transcript.trim().split(SPACES).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val normalizedWords = words.map { TextMatcher.normalize(it) }

        // « écris … » : le texte est repris TEL QUEL, avec sa ponctuation et ses
        // majuscules — c'est ce qui sera écrit dans le champ de saisie.
        val typeIndex = normalizedWords.indexOfFirst { it in TYPE_VERBS }
        if (typeIndex >= 0) {
            val text = words.drop(typeIndex + 1).joinToString(" ").trim()
            if (text.isNotBlank()) return VoiceIntent.TypeText(text)
        }

        // « ouvre … » : le nom sera rapproché des applications installées.
        val launchIndex = normalizedWords.indexOfFirst { it in LAUNCH_VERBS }
        if (launchIndex >= 0) {
            val name = words.drop(launchIndex + 1).joinToString(" ").trim()
            if (name.isNotBlank()) return VoiceIntent.LaunchApp(name)
        }

        return null
    }

    /**
     * Vrai si le mot-clé se trouve dans la phrase.
     *
     * Utilisé sur les transcriptions **provisoires** : on réagit dès que le mot
     * est reconnu, sans attendre que le moteur estime la phrase terminée.
     */
    fun containsWakeWord(transcript: String, wakeWord: String): Boolean =
        findWakeWord(
            transcript.trim().split(SPACES)
                .filter { it.isNotBlank() }
                .map { TextMatcher.normalize(it) },
            wakeWord,
        ) != null

    /** Cherche le mot-clé et renvoie sa position (index, nombre de mots occupés). */
    private fun findWakeWord(normalizedWords: List<String>, wakeWord: String): Pair<Int, Int>? {
        val reference = TextMatcher.normalize(wakeWord)
        if (reference.isBlank()) return null

        normalizedWords.forEachIndexed { index, word ->
            if (isCloseEnough(word, reference)) return index to 1

            // Le moteur peut écrire un mot-clé en deux morceaux.
            val next = normalizedWords.getOrNull(index + 1)
            if (next != null && isCloseEnough(word + next, reference)) return index to 2
        }
        return null
    }

    /** Tolérance proportionnelle à la longueur : fixe, elle ferait un joker des mots courts. */
    private fun isCloseEnough(text: String, reference: String): Boolean =
        TextMatcher.levenshtein(text, reference) <= TextMatcher.tolerance(reference)

    /** Mots d'une formulation qui portent réellement l'intention. */
    private fun significantWords(phrase: String): List<String> =
        TextMatcher.normalize(phrase)
            .split(' ')
            .filter { it.length >= MIN_KEYWORD_LENGTH && it !in STOP_WORDS }
            .distinct()

    /** Une formulation candidate, avant notation. */
    private data class Candidate(
        val text: String,
        val intent: VoiceIntent,
        val learnedPhrase: String?,
        val useCount: Int,
    )

    // ---- Seuils de confiance ----

    /** Au-dessus : on exécute sans hésiter. */
    private const val CONFIDENCE_HIGH = 0.82f

    /** Au-dessus : on propose les actions probables. En dessous : on demande de reformuler. */
    private const val CONFIDENCE_MEDIUM = 0.55f

    /** Une formulation apprise part avec un avantage : l'utilisateur l'a voulue. */
    private const val LEARNED_BONUS = 0.05f

    /** Chaque usage compte un peu, sans jamais écraser la ressemblance du texte. */
    private const val USAGE_BONUS_STEP = 0.02f
    private const val MAX_USAGE_BONUS = 0.08f

    /** Trois choix au maximum : au-delà, on ne choisit plus, on devine. */
    private const val MAX_OPTIONS = 3

    private const val MIN_KEYWORD_LENGTH = 4

    /**
     * Mots de liaison, qui ne portent aucune intention.
     * Les écarter évite qu'une formulation apprise corresponde à n'importe quoi.
     */
    private val STOP_WORDS = setOf(
        "les", "des", "une", "aux", "dans", "sur", "vers", "pour", "avec",
        "cette", "mon", "mes", "ton", "tes", "peu", "plus", "moins", "bien",
        "fais", "fait", "tout", "tous", "toute", "alors", "donc", "puis",
    )

    private val SPACES = Regex("\\s+")
}
