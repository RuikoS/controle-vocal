package com.voicecontrol.app.core.speech

import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.actions.VoiceIntent
import com.voicecontrol.app.core.text.TextMatcher

/**
 * Le vocabulaire de l'application : le mot-clé de réveil, les commandes sans
 * paramètre, les verbes qui en attendent un, et la façon de comprendre une
 * phrase.
 *
 * DEUX FAMILLES DE PHRASES :
 *
 *  - les **commandes** ([COMMANDS]) sont une liste fermée : dix phrases, dix
 *    actions. C'est ce qui rend la reconnaissance fiable, et c'est suffisant
 *    pour tout ce qui ne dépend pas d'un nom propre ;
 *  - les **verbes à paramètre** ([LAUNCH_VERBS], [TYPE_VERBS]) ouvrent au
 *    contraire sur l'infini : « ouvre <n'importe quelle application> »,
 *    « écris <n'importe quelle phrase> ». Ils ne sont possibles que parce que le
 *    moteur en ligne transcrit du texte libre, que l'on interprète ensuite.
 *
 * HISTORIQUE TECHNIQUE — à lire avant de vouloir « optimiser » : une première
 * version donnait la liste fermée au moteur sous forme de *grammaire*. À l'usage
 * le moteur renvoyait « [unk] » sur presque tout : il rejetait les phrases
 * entières au lieu de les reconnaître. La grammaire a donc été abandonnée au
 * profit d'une reconnaissance libre, et c'est CE fichier qui décide ce que vaut
 * la transcription — d'où la tolérance assumée du rapprochement.
 */
object VoiceVocabulary {

    /**
     * Forme affichée à l'écran, avec sa majuscule.
     *
     * Le mot-clé n'est plus une constante : il se règle dans les préférences.
     * Un prénom comme « Gregor » n'existe pas dans le dictionnaire du modèle
     * local, qui l'écrit donc au hasard — c'est exactement la cause du mot-clé
     * « capricieux ».
     */
    fun displayWakeWord(wakeWord: String): String = wakeWord.replaceFirstChar { it.uppercase() }

    /**
     * Commandes sans paramètre, en français.
     *
     * On évite délibérément les termes tactiles anglais (« swipe », « scroll »,
     * « tap ») : le dictionnaire du modèle local est français et les prononcerait
     * mal.
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

    /**
     * Verbes qui annoncent le lancement d'une application.
     * Les formes accentuées sont écrites sans accent : la comparaison se fait
     * sur du texte normalisé.
     */
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

    /** Ce qu'une transcription signifie pour l'application. */
    sealed interface Interpretation {

        /** Le mot-clé seul : on attend une commande. */
        data object WakeWord : Interpretation

        /** Une intention, valable seulement si le mot-clé a déjà été dit. */
        data class Intent(val intent: VoiceIntent) : Interpretation

        /** Le mot-clé ET l'intention dits d'une seule traite. */
        data class WakeWordWithIntent(val intent: VoiceIntent) : Interpretation

        /** Rien de reconnu : bruit, conversation, phrase hors vocabulaire. */
        data object Unrelated : Interpretation
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

        // Le mot-clé est cherché PARTOUT dans la phrase, pas seulement en tête :
        // le moteur place souvent un mot parasite avant (« euh », « et », « bon »),
        // et refuser la phrase pour cette raison ferait rater des ordres clairs.
        val match = findWakeWord(words.map { TextMatcher.normalize(it) }, wakeWord)
        if (match != null) {
            val (index, length) = match
            val rest = words.drop(index + length).joinToString(" ").trim()
            if (rest.isBlank()) return Interpretation.WakeWord

            val intent = parseIntent(rest, learnedAliases)
            return if (intent != null) {
                Interpretation.WakeWordWithIntent(intent)
            } else {
                // Mot-clé compris, mais la suite n'est pas une commande : on
                // ouvrira quand même l'écoute, l'utilisateur redira.
                Interpretation.WakeWord
            }
        }

        // Pas de mot-clé : la phrase ne compte que si l'écoute est déjà ouverte.
        if (!gateOpen) return Interpretation.Unrelated
        val intent = parseIntent(transcript, learnedAliases)
        return if (intent != null) Interpretation.Intent(intent) else Interpretation.Unrelated
    }

    /**
     * Transforme une phrase en intention, ou renvoie null si elle ne veut rien dire.
     *
     * L'ordre des vérifications n'est pas anodin :
     *  1. les formulations **apprises** passent en premier — l'utilisateur a
     *     explicitement dit ce que sa phrase voulait dire ;
     *  2. puis les verbes à paramètre, avant les commandes fermées, sinon
     *     « écris retour » serait pris pour la commande « retour » ;
     *  3. enfin les commandes sans paramètre.
     */
    fun parseIntent(
        transcript: String,
        learnedAliases: List<LearnedAlias> = emptyList(),
    ): VoiceIntent? {
        val words = transcript.trim().split(SPACES).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        // 1. Ce que l'utilisateur nous a appris.
        matchLearnedAlias(transcript, learnedAliases)?.let { return VoiceIntent.Execute(it.action) }

        val normalizedWords = words.map { TextMatcher.normalize(it) }

        // 2. « écris … » : le texte est repris TEL QUEL, avec sa ponctuation et
        //    ses majuscules — c'est ce qui sera écrit dans le champ de saisie.
        val typeIndex = normalizedWords.indexOfFirst { it in TYPE_VERBS }
        if (typeIndex >= 0) {
            val text = words.drop(typeIndex + 1).joinToString(" ").trim()
            if (text.isNotBlank()) return VoiceIntent.TypeText(text)
        }

        // 3. « ouvre … » : le nom sera rapproché des applications installées.
        val launchIndex = normalizedWords.indexOfFirst { it in LAUNCH_VERBS }
        if (launchIndex >= 0) {
            val name = words.drop(launchIndex + 1).joinToString(" ").trim()
            if (name.isNotBlank()) return VoiceIntent.LaunchApp(name)
        }

        // 4. Les commandes sans paramètre.
        matchCommand(transcript)?.let { return VoiceIntent.Execute(it.action) }

        // 5. Enfin, les MOTS appris. Volontairement en dernier : un mot isolé ne
        //    doit jamais court-circuiter une commande explicite comme
        //    « glisse en haut ». C'est pour cela que l'ordre compte.
        matchLearnedKeyword(transcript, learnedAliases)
            ?.let { return VoiceIntent.Execute(it.action) }

        return null
    }

    /** Rapproche une phrase des formulations apprises. */
    private fun matchLearnedAlias(
        transcript: String,
        learnedAliases: List<LearnedAlias>,
    ): LearnedAlias? {
        if (learnedAliases.isEmpty()) return null

        val index = TextMatcher.bestMatchIndex(transcript, learnedAliases.map { it.phrase })
            ?: return null
        return learnedAliases[index]
    }

    /**
     * Dernier recours : la phrase contient-elle un **mot significatif** d'une
     * formulation apprise ?
     *
     * C'est ce qui rend l'apprentissage **généralisant**. « Remonte en haut »
     * appris une seule fois fait aussi fonctionner « remonte », « remonte un
     * peu » ou « remonte la page » — sans rien réapprendre.
     *
     * La généralisation porte sur les MOTS, pas sur le sens : c'est une limite
     * assumée. Elle couvre la majorité des cas, parce qu'on se trompe rarement
     * sur la phrase entière et souvent sur la même tournure.
     */
    private fun matchLearnedKeyword(
        transcript: String,
        learnedAliases: List<LearnedAlias>,
    ): LearnedAlias? {
        if (learnedAliases.isEmpty()) return null

        val heardWords = TextMatcher.normalize(transcript).split(' ').toSet()

        val scored = learnedAliases.mapNotNull { alias ->
            val matched = significantWords(alias.phrase).count { keyword ->
                // Rapprochement par racine : « remonte » et « remonter » sont le
                // même geste, et l'utilisateur ne va pas réapprendre chaque forme.
                heardWords.any { word -> TextMatcher.isSameWord(keyword, word) }
            }
            if (matched == 0) null else alias to matched
        }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }

        // Deux actions à égalité : on ne devine pas. Mieux vaut redemander que
        // d'exécuter la mauvaise chose.
        if (scored.count { it.second == bestScore } > 1) return null

        return scored.first { it.second == bestScore }.first
    }

    /**
     * Mots d'une formulation qui portent réellement l'intention.
     *
     * On écarte les mots de liaison et les mots trop courts : « en », « le » ou
     * « un » ne distinguent rien et feraient correspondre n'importe quoi.
     */
    private fun significantWords(phrase: String): List<String> =
        TextMatcher.normalize(phrase)
            .split(' ')
            .filter { it.length >= MIN_KEYWORD_LENGTH && it !in STOP_WORDS }
            .distinct()

    /**
     * Rapproche une transcription d'une commande du vocabulaire.
     *
     * Chaque formulation acceptée (principale et synonymes) devient une
     * candidate : on rapproche de l'ensemble, puis on remonte à la commande
     * propriétaire.
     */
    fun matchCommand(transcript: String): VoiceCommand? {
        val candidates = mutableListOf<String>()
        val owners = mutableListOf<VoiceCommand>()

        COMMANDS.forEach { command ->
            (listOf(command.phrase) + command.aliases).forEach { wording ->
                candidates += wording
                owners += command
            }
        }

        val index = TextMatcher.bestMatchIndex(transcript, candidates) ?: return null
        return owners[index]
    }

    /** Toutes les formulations connues, pour l'écran qui liste les commandes. */
    fun allWording(): List<Pair<String, VoiceCommand>> =
        COMMANDS.flatMap { command ->
            (listOf(command.phrase) + command.aliases).map { it to command }
        }

    /**
     * Vrai si le mot-clé se trouve dans la phrase.
     *
     * Utilisé sur les transcriptions **provisoires** : on réagit dès que le mot
     * est reconnu, sans attendre que le moteur estime la phrase terminée. C'est
     * ce qui supprime le délai qui donnait l'impression d'un mot-clé capricieux.
     */
    fun containsWakeWord(transcript: String, wakeWord: String): Boolean =
        findWakeWord(
            transcript.trim().split(SPACES)
                .filter { it.isNotBlank() }
                .map { TextMatcher.normalize(it) },
            wakeWord,
        ) != null

    /**
     * Cherche le mot-clé dans la phrase et renvoie sa position sous la forme
     * (index, nombre de mots occupés).
     *
     * @param normalizedWords mots déjà normalisés, pour comparer sans accents.
     * Gère le cas où le moteur écrit un mot-clé en deux mots — « ordi nateur ».
     */
    private fun findWakeWord(normalizedWords: List<String>, wakeWord: String): Pair<Int, Int>? {
        val reference = TextMatcher.normalize(wakeWord)
        if (reference.isBlank()) return null

        normalizedWords.forEachIndexed { index, word ->
            if (isCloseEnough(word, reference)) return index to 1

            val next = normalizedWords.getOrNull(index + 1)
            if (next != null && isCloseEnough(word + next, reference)) return index to 2
        }
        return null
    }

    /**
     * Le moteur n'écrit jamais exactement ce qu'il entend : on accepte une
     * petite distance, proportionnelle à la longueur du mot-clé.
     *
     * Une tolérance fixe serait absurde sur un mot court : deux caractères
     * d'écart sur « ok » en feraient un joker.
     */
    private fun isCloseEnough(text: String, reference: String): Boolean =
        TextMatcher.levenshtein(text, reference) <= TextMatcher.tolerance(reference)

    /**
     * Longueur minimale d'un mot pour qu'il serve de repère.
     * En dessous de quatre lettres, un mot ressemble à trop de choses.
     */
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
