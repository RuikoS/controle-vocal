package com.voicecontrol.app.core.settings

import android.content.Context
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.speech.LearnedAlias
import com.voicecontrol.app.core.speech.WakeSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Les réglages de l'application, conservés d'un lancement à l'autre.
 *
 * Un seul réglage pour l'instant : le son de réveil. Une simple mémoire clé/valeur
 * suffit largement pour une valeur unique — une base de données serait du poids
 * mort.
 *
 * L'instance est créée par l'Application et partagée : l'écran d'accueil écrit,
 * le service lit, et le flux [wakeSound] fait le lien entre les deux sans qu'ils
 * aient à se connaître.
 */
class AppSettings(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _wakeSound = MutableStateFlow(readWakeSound())

    /** Son de réveil actuellement choisi. */
    val wakeSound: StateFlow<WakeSound> = _wakeSound.asStateFlow()

    private val _wakeWord = MutableStateFlow(readWakeWord())

    /** Mot-clé qui réveille l'application. */
    val wakeWord: StateFlow<String> = _wakeWord.asStateFlow()

    private val _learnedAliases = MutableStateFlow(readLearnedAliases())

    /** Formulations apprises des corrections de l'utilisateur. */
    val learnedAliases: StateFlow<List<LearnedAlias>> = _learnedAliases.asStateFlow()

    fun setWakeSound(sound: WakeSound) {
        _wakeSound.value = sound
        preferences.edit().putString(KEY_WAKE_SOUND, sound.name).apply()
    }

    /**
     * Change le mot-clé de réveil.
     *
     * Un mot vide est ignoré : sans mot-clé, l'application n'aurait plus aucun
     * moyen de distinguer une commande d'une phrase entendue par hasard, ce qui
     * serait pire que tout.
     */
    fun setWakeWord(word: String) {
        val cleaned = word.trim()
        if (cleaned.isBlank()) return

        _wakeWord.value = cleaned
        preferences.edit().putString(KEY_WAKE_WORD, cleaned).apply()
    }

    private fun readWakeWord(): String =
        preferences.getString(KEY_WAKE_WORD, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WAKE_WORD

    /**
     * Retient qu'une phrase prononcée désigne une action.
     *
     * La même phrase ne peut désigner qu'une seule action : la réapprendre
     * **remplace** l'ancienne. C'est ce qui permet de corriger un apprentissage
     * raté sans avoir à effacer quoi que ce soit.
     */
    fun addLearnedAlias(phrase: String, action: VoiceAction) {
        val cleaned = phrase.trim()
        if (cleaned.isBlank()) return

        val updated = _learnedAliases.value
            .filterNot { it.phrase.equals(cleaned, ignoreCase = true) } +
            LearnedAlias(cleaned, action)

        _learnedAliases.value = updated
        preferences.edit()
            .putString(KEY_LEARNED_ALIASES, serializeAliases(updated))
            .apply()
    }

    /** Relit les formulations apprises. */
    fun removeLearnedAlias(phrase: String) {
        val updated = _learnedAliases.value.filterNot { it.phrase.equals(phrase, ignoreCase = true) }
        _learnedAliases.value = updated
        preferences.edit().putString(KEY_LEARNED_ALIASES, serializeAliases(updated)).apply()
    }

    /**
     * Les formulations sont stockées en JSON, sous la forme `{"phrase": "Action"}`.
     *
     * Le nom de l'action est conservé plutôt que son rang : ajouter une action
     * plus tard ne décalera pas les apprentissages déjà faits.
     */
    private fun readLearnedAliases(): List<LearnedAlias> {
        val stored = preferences.getString(KEY_LEARNED_ALIASES, null) ?: return emptyList()

        return runCatching {
            val json = JSONObject(stored)
            json.keys().asSequence().mapNotNull { phrase ->
                val actionName = json.optString(phrase)
                VoiceAction.entries
                    .firstOrNull { it.name == actionName }
                    ?.let { LearnedAlias(phrase = phrase, action = it) }
            }.toList()
        }.getOrDefault(emptyList())
    }

    private fun serializeAliases(aliases: List<LearnedAlias>): String {
        val json = JSONObject()
        aliases.forEach { alias -> json.put(alias.phrase, alias.action.name) }
        return json.toString()
    }

    /**
     * Relit le réglage stocké. Le nom est conservé plutôt que le rang dans
     * l'énumération : ainsi, ajouter un son plus tard ne décale pas le choix
     * déjà fait par l'utilisateur.
     */
    private fun readWakeSound(): WakeSound {
        val stored = preferences.getString(KEY_WAKE_SOUND, null) ?: return WakeSound.DEFAULT
        return WakeSound.entries.firstOrNull { it.name == stored } ?: WakeSound.DEFAULT
    }

    companion object {
        /**
         * Mot-clé par défaut : un VRAI mot français.
         *
         * C'est le cœur du correctif du mot-clé « capricieux ». Un prénom comme
         * « Gregor » n'existe pas dans le dictionnaire du modèle local : le
         * moteur l'écrit alors au hasard (« regard », « grécité », « grégoire »),
         * et la détection échoue sans raison apparente. « Ordinateur » est dans
         * le dictionnaire, donc reconnu avec constance.
         */
        const val DEFAULT_WAKE_WORD = "ordinateur"

        private const val PREFERENCES_NAME = "voice_control_settings"
        private const val KEY_WAKE_SOUND = "wake_sound"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_LEARNED_ALIASES = "learned_aliases"
    }
}
