package com.voicecontrol.app.data.source

import android.content.Context
import android.content.Intent
import com.voicecontrol.app.core.text.TextMatcher

/**
 * La liste des applications installées, et le rapprochement d'un nom prononcé
 * avec cette liste.
 *
 * CONTRAINTE À CONNAÎTRE : depuis Android 11, une application ne voit plus les
 * autres par défaut. Sans la permission `QUERY_ALL_PACKAGES` déclarée dans le
 * manifeste, cette classe ne renverrait qu'une liste partielle — et « ouvre
 * Spotify » échouerait pour une raison incompréhensible.
 *
 * La liste est mise en cache : l'interroger coûte un aller-retour avec le
 * système, et elle ne change pas en cours d'exécution.
 */
class InstalledApps(private val context: Context) {

    /** Une application lançable : ce qu'on montre, et ce qu'on lance. */
    data class AppTarget(val label: String, val packageName: String)

    private var cache: List<AppTarget>? = null

    /**
     * Retrouve l'application dont le nom ressemble le plus à [spokenName].
     * Renvoie null si rien de convaincant n'a été trouvé — mieux vaut ne rien
     * faire que d'ouvrir une application au hasard.
     */
    fun resolve(spokenName: String): AppTarget? {
        val query = withoutFillerWords(TextMatcher.normalize(spokenName))
        if (query.isBlank()) return null

        val apps = launcherApps()
        val index = TextMatcher.bestMatchIndex(query, apps.map { it.label }) ?: return null
        return apps[index]
    }

    private fun launcherApps(): List<AppTarget> {
        cache?.let { return it }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = context.packageManager
            .queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(context.packageManager).toString()
                val packageName = info.activityInfo?.packageName
                if (label.isBlank() || packageName == null) null else AppTarget(label, packageName)
            }
            // Une application peut exposer plusieurs activités lançables : on ne
            // garde que la première, sinon les doublons fausseraient le choix.
            .distinctBy { it.packageName }
            .sortedBy { it.label }

        cache = installed
        return installed
    }

    /**
     * Retire les mots inutiles en tête : « ouvre l'application Spotify » doit
     * désigner Spotify. On boucle, car ces mots peuvent s'empiler.
     */
    private fun withoutFillerWords(query: String): String {
        var result = query
        var changed = true

        while (changed) {
            changed = false
            FILLER_WORDS.forEach { filler ->
                if (result.startsWith("$filler ")) {
                    result = result.removePrefix("$filler ")
                    changed = true
                }
            }
        }

        return result.trim()
    }

    private companion object {
        /**
         * Volontairement limité aux articles et au mot « application » : des
         * raccourcis comme « app » rogneraient le début de vrais noms.
         */
        val FILLER_WORDS = listOf("le", "la", "les", "l", "un", "une", "application", "appli")
    }
}
