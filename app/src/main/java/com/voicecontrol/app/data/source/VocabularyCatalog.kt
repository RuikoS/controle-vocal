package com.voicecontrol.app.data.source

import android.content.Context
import com.voicecontrol.app.core.text.TextMatcher
import org.json.JSONObject

/**
 * Données de vocabulaire français : corrections de transcription et alias
 * d'applications, chargées une fois depuis les ressources.
 *
 * Ces tables viennent du constat le plus utile fait à l'usage : le moteur se
 * trompe **toujours de la même façon**. Il écrit « you tube » pour YouTube,
 * « spotifaille » pour Spotify, « adroite » pour « à droite ». Corriger ces
 * erreurs connues vaut mieux que d'espérer un meilleur modèle.
 *
 * Les clés sont normalisées (minuscules, sans accents, sans ponctuation) : c'est
 * la forme sous laquelle le rapprochement travaille.
 */
class VocabularyCatalog(context: Context) {

    /** Corrections appliquées au texte normalisé, de la plus longue à la plus courte. */
    private val corrections: List<Pair<Regex, String>>

    /** Alias normalisé -> nom canonique de l'application. */
    private val appAliases: Map<String, String>

    init {
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(json)

        corrections = root.optJSONObject("corrections")?.let { table ->
            table.keys().asSequence()
                .map { key -> key to table.optString(key) }
                // Les expressions longues d'abord : « vers le haut » doit être
                // traité avant « haut », sinon le remplacement partiel casse la
                // phrase.
                .sortedByDescending { (key, _) -> key.length }
                .map { (key, value) ->
                    Regex("(?<![a-z0-9])" + Regex.escape(key) + "(?![a-z0-9])") to value
                }
                .toList()
        }.orEmpty()

        appAliases = root.optJSONObject("appAliases")?.let { table ->
            buildMap {
                table.keys().asSequence().forEach { canonical ->
                    val aliases = table.optJSONArray(canonical)
                    // Le nom canonique est lui aussi un alias : on peut le dire tel quel.
                    put(TextMatcher.normalize(canonical), canonical)
                    for (index in 0 until (aliases?.length() ?: 0)) {
                        val alias = aliases?.optString(index).orEmpty()
                        if (alias.isNotBlank()) put(TextMatcher.normalize(alias), canonical)
                    }
                }
            }
        }.orEmpty()
    }

    /**
     * Corrige les erreurs de transcription connues.
     *
     * @param normalizedText texte déjà normalisé par [TextMatcher.normalize].
     */
    fun applyCorrections(normalizedText: String): String {
        if (normalizedText.isBlank()) return normalizedText

        var result = normalizedText
        corrections.forEach { (pattern, replacement) ->
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Normalise puis corrige : c'est la forme sous laquelle on compare.
     *
     * Une seule fonction pour les deux étapes, parce qu'elles vont toujours
     * ensemble : les corrections sont écrites dans la forme normalisée.
     */
    fun normalizeAndCorrect(text: String): String = applyCorrections(TextMatcher.normalize(text))

    /**
     * Retrouve le nom canonique d'une application à partir de ce qui a été
     * prononcé. « you tube », « iou tube » et « youtube » désignent tous YouTube.
     */
    fun canonicalAppName(spokenName: String): String? =
        appAliases[TextMatcher.normalize(spokenName)]

    /** Nombre d'entrées connues, utile pour le diagnostic. */
    fun size(): String = "${corrections.size} corrections, ${appAliases.size} alias d'applications"

    private companion object {
        const val ASSET_NAME = "vocabulary.json"
    }
}
