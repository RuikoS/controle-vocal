package com.voicecontrol.app.core.text

import kotlin.math.abs

/**
 * Rapproche un texte entendu d'une liste de textes connus.
 *
 * Le même besoin revient partout dans ce projet : reconnaître une commande
 * (« glisse a droite » pour « glisse à droite »), reconnaître un nom
 * d'application (« ouat's app » pour « WhatsApp »), ou repérer un mot-clé.
 * Plutôt que de dupliquer la logique et ses tolérances, elle vit ici.
 *
 * L'idée directrice : **on préfère accepter à tort que refuser à raison**. Une
 * commande refusée est une frustration immédiate ; une commande acceptée de
 * travers n'a lieu que si le mot-clé a été prononcé juste avant.
 */
object TextMatcher {

    /**
     * Normalise un texte : minuscules, accents retirés, ponctuation remplacée
     * par des espaces. C'est ce qui fait que « Glisse à droite ! » et
     * « glisse a droite » deviennent identiques.
     */
    fun normalize(text: String): String {
        val withoutAccents = java.text.Normalizer
            .normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

        return withoutAccents
            .replace(NON_ALPHANUMERIC, " ")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
    }

    /**
     * Cherche la meilleure correspondance et renvoie son **indice** dans la
     * liste, ou null si rien ne correspond.
     *
     * `query` est normalisé ici : on peut donc lui passer du texte brut.
     */
    fun bestMatchIndex(query: String, candidates: List<String>): Int? {
        val searched = normalize(query)
        if (searched.isBlank() || candidates.isEmpty()) return null

        val prepared = candidates.map { it to normalize(it) }

        // 1. Égalité : elle doit toujours gagner.
        prepared.indexOfFirst { (_, candidate) -> candidate == searched }
            .takeIf { it >= 0 }
            ?.let { return it }

        // 2. L'un commence par l'autre. C'est le cas des libellés d'applications,
        //    qui sont souvent plus longs que ce qu'on prononce : « Spotify »
        //    contre « Spotify : musique et podcasts ».
        prepared
            .withIndex()
            .filter { (_, pair) -> pair.second.startsWith(searched) || searched.startsWith(pair.second) }
            .minByOrNull { (_, pair) -> abs(pair.second.length - searched.length) }
            ?.let { return it.index }

        // 3. L'un contient l'autre : « le retour » pour « retour ».
        prepared
            .withIndex()
            .filter { (_, pair) -> pair.second.contains(searched) || searched.contains(pair.second) }
            .minByOrNull { (_, pair) -> abs(pair.second.length - searched.length) }
            ?.let { return it.index }

        // 4. En dernier recours, la ressemblance approximative — mais bornée :
        //    sans limite, « bonjour » finirait par ressembler à quelque chose.
        return prepared
            .withIndex()
            .map { (index, pair) -> Triple(index, pair.second, levenshtein(searched, pair.second)) }
            .filter { (_, candidate, distance) -> distance <= tolerance(candidate) }
            .minByOrNull { (_, _, distance) -> distance }
            ?.first
    }

    /** Même chose, mais renvoie le texte trouvé plutôt que son indice. */
    fun bestMatch(query: String, candidates: List<String>): String? =
        bestMatchIndex(query, candidates)?.let { candidates[it] }

    /**
     * Deux mots désignent-ils la même chose malgré leur forme ?
     *
     * « Remonte » et « remonter » sont le même geste ; « verrouille » et
     * « verrouiller » aussi. On rapproche donc les mots dont l'un commence par
     * l'autre — mais seulement s'ils sont assez longs pour que ce ne soit pas un
     * hasard : « vers » ne doit pas se confondre avec « verrouille ».
     *
     * Ce n'est pas de la linguistique, c'est une approximation assumée : elle
     * couvre les formes verbales courantes, qui sont l'essentiel des variantes.
     */
    fun isSameWord(left: String, right: String): Boolean {
        if (left == right) return true
        if (minOf(left.length, right.length) < MIN_PREFIX_LENGTH) return false
        return left.startsWith(right) || right.startsWith(left)
    }

    /**
     * Nombre de caractères à ajouter, retirer ou remplacer pour passer d'un
     * texte à l'autre.
     *
     * Implémentation classique en deux lignes de tableau : on ne garde que la
     * précédente, ce qui suffit et évite de tout garder en mémoire.
     */
    fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,                 // suppression
                    previous[j] + 1,                    // insertion
                    previous[j - 1] + substitutionCost, // remplacement
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    /**
     * Plus la référence est longue, plus on tolère de différences.
     * Un quart de la longueur : six caractères sur une phrase de vingt-quatre,
     * deux seulement sur un mot de six.
     */
    fun tolerance(reference: String): Int = maxOf(MIN_TOLERANCE, reference.length / 4)

    private const val MIN_TOLERANCE = 2

    /** Longueur minimale pour rapprocher deux mots par leur racine commune. */
    private const val MIN_PREFIX_LENGTH = 5

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
    private val MULTIPLE_SPACES = Regex(" +")
}
