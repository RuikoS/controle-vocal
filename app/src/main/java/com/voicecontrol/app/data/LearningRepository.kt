package com.voicecontrol.app.data

import android.content.Context
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.speech.LearnedAlias
import com.voicecontrol.app.data.local.AppAliasEntity
import com.voicecontrol.app.data.local.LearnedPhraseEntity
import com.voicecontrol.app.data.local.VoiceControlDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Ce que l'application apprend, conservé en base locale (Room).
 *
 * Deux choses y vivent :
 *  - **les formulations** : « cette phrase veut dire cette action », avec un
 *    compteur d'usage qui servira à départager les cas ambigus ;
 *  - **les correspondances d'applications** : le nom tel qu'il est prononcé et
 *    l'application réellement visée — la mémoire des erreurs de transcription
 *    du type « ouvre you tube ».
 *
 * Rien ne sort du téléphone. Le flux [learnedAliases] est lu directement par la
 * logique de reconnaissance, ce qui fait qu'un apprentissage prend effet
 * immédiatement, sans redémarrage.
 */
class LearningRepository(context: Context, private val scope: CoroutineScope) {

    private val dao = VoiceControlDatabase.get(context).learningDao()

    /** Formulations apprises, avec leur fréquence d'usage. */
    val learnedAliases: StateFlow<List<LearnedAlias>> = dao.observePhrases()
        .map { rows ->
            rows.mapNotNull { row ->
                actionOf(row.action)?.let { action ->
                    LearnedAlias(phrase = row.phrase, action = action, useCount = row.useCount)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Correspondances apprises entre un nom prononcé et une application. */
    val appAliases: StateFlow<List<AppAliasEntity>> = dao.observeAppAliases()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Apprend qu'une phrase désigne une action — **sans demander de confirmation** :
     * c'est l'utilisateur qui vient de l'exprimer en corrigeant.
     *
     * Réapprendre la même association compte comme une confirmation et fait
     * monter la fréquence. Réapprendre une association **différente** la
     * remplace, sans effacer l'historique d'usage.
     */
    fun learn(
        phrase: String,
        action: VoiceAction,
        source: String = LearnedPhraseEntity.SOURCE_CORRECTION,
    ) {
        val cleaned = phrase.trim()
        if (cleaned.isBlank()) return

        scope.launch {
            val existing = dao.findPhrase(cleaned)
            val now = System.currentTimeMillis()

            val row = when {
                existing == null -> LearnedPhraseEntity(
                    phrase = cleaned,
                    action = action.name,
                    source = source,
                    lastUsedAt = now,
                )

                existing.action == action.name -> existing.copy(
                    // Même association redite : c'est une confirmation.
                    useCount = existing.useCount + 1,
                    lastUsedAt = now,
                )

                else -> existing.copy(
                    action = action.name,
                    source = source,
                    lastUsedAt = now,
                )
            }

            dao.upsertPhrase(row)
        }
    }

    /** Une formulation apprise vient de servir : sa fréquence monte. */
    fun recordPhraseUse(phrase: String) {
        scope.launch { dao.touchPhrase(phrase, System.currentTimeMillis()) }
    }

    /** Retient qu'un nom prononcé désigne telle application. */
    fun learnAppAlias(spokenName: String, packageName: String, label: String) {
        val cleaned = spokenName.trim()
        if (cleaned.isBlank()) return

        scope.launch {
            val existing = dao.findAppAlias(cleaned)
            val now = System.currentTimeMillis()

            dao.upsertAppAlias(
                when {
                    existing == null -> AppAliasEntity(
                        spokenName = cleaned,
                        packageName = packageName,
                        label = label,
                        lastUsedAt = now,
                    )

                    existing.packageName == packageName -> existing.copy(
                        useCount = existing.useCount + 1,
                        lastUsedAt = now,
                        label = label,
                    )

                    else -> existing.copy(
                        packageName = packageName,
                        label = label,
                        lastUsedAt = now,
                    )
                },
            )
        }
    }

    /** Une application désignée par un nom appris vient d'être ouverte. */
    fun recordAppUse(spokenName: String) {
        scope.launch { dao.touchAppAlias(spokenName, System.currentTimeMillis()) }
    }

    /** Efface tout ce qui a été appris. Irréversible, et assumé comme tel. */
    fun clearAll() {
        scope.launch {
            dao.clearPhrases()
            dao.clearAppAliases()
        }
    }

    fun deletePhrase(phrase: String) {
        scope.launch { dao.deletePhrase(phrase) }
    }

    /**
     * Les actions sont stockées par leur nom. Si une action disparaît d'une
     * version à l'autre, l'apprentissage correspondant est simplement ignoré
     * plutôt que de faire planter l'application.
     */
    private fun actionOf(name: String): VoiceAction? =
        VoiceAction.entries.firstOrNull { it.name == name }
}
