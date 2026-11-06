package com.voicecontrol.app.data.source

import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.speech.AiFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Interroge un modèle de langage pour comprendre une phrase qu'aucune commande
 * locale n'a su interpréter.
 *
 * On parle le format **OpenAI chat completions**, parce qu'il est devenu le
 * format commun : OpenAI, Mistral, Groq, Ollama et d'autres le comprennent. Un
 * seul client, plusieurs fournisseurs — y compris un serveur local, sans clé.
 *
 * Le contrat est minimal : on envoie la phrase **seule** (rien d'autre de
 * l'utilisateur), la liste des actions connues, et on attend un JSON de la forme
 * `{"action":"SCROLL_UP"}`.
 *
 * @param baseUrl adresse complète du point d'accès, ex. `https://api.openai.com/v1/chat/completions`.
 * @param apiKey clé du fournisseur ; vide pour un serveur local (Ollama).
 */
class LlmIntentResolver(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : AiFallback {

    /** Sans adresse ni modèle, le filet de sécurité reste éteint. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && baseUrl != "null"

    override suspend fun resolveAction(phrase: String): VoiceAction? = withContext(Dispatchers.IO) {
        if (!isConfigured || phrase.isBlank()) return@withContext null

        val response = runCatching { post(buildRequest(phrase)) }.getOrNull() ?: return@withContext null
        parseAction(response)
    }

    private fun buildRequest(phrase: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", phrase))

        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            // On veut une réponse reproductible, pas de la créativité.
            .put("temperature", 0)
            .toString()
    }

    private fun post(body: String): String {
        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) {
                return ""
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Lit la réponse, en tolérant les écarts de forme : certains modèles
     * entourent le JSON de balises Markdown malgré la consigne.
     */
    private fun parseAction(response: String): VoiceAction? {
        if (response.isBlank()) return null

        return runCatching {
            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")

            val cleaned = content
                .substringAfter("{", content)
                .substringBeforeLast("}", content)

            val name = JSONObject("{$cleaned}").optString("action")
            VoiceAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }.getOrNull()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 12_000

        /**
         * Le prompt décrit chaque action en français : le modèle doit choisir
         * parmi elles, et rien d'autre.
         *
         * `by lazy` n'est pas décoratif : dans un compagnon, les propriétés
         * s'initialisent dans l'ordre de déclaration, et celle-ci lit
         * [ACTION_HINTS] déclarée plus bas. Paresseuse, elle est construite au
         * premier accès, quand tout est prêt.
         */
        val SYSTEM_PROMPT: String by lazy { buildString {
            appendLine("Tu analyses une phrase prononcée par un utilisateur francophone")
            appendLine("pour une application Android de contrôle vocal.")
            appendLine("Choisis l'action correspondante, et réponds UNIQUEMENT par un JSON :")
            appendLine("""{"action":"NOM_DE_L_ACTION"}""")
            appendLine("""ou {"action":"AUCUNE"} si aucune ne correspond.""")
            appendLine()
            appendLine("Actions disponibles :")
            ACTION_HINTS.forEach { (action, hint) ->
                appendLine("${action.name} : $hint")
            }
        } }

        /** Ce que veut dire chaque action, en une phrase. */
        val ACTION_HINTS: Map<VoiceAction, String> = mapOf(
            VoiceAction.SwipeRight to "glisser le doigt vers la droite",
            VoiceAction.SwipeLeft to "glisser le doigt vers la gauche",
            VoiceAction.SwipeUp to "glisser le doigt vers le haut",
            VoiceAction.SwipeDown to "glisser le doigt vers le bas",
            VoiceAction.ScrollDown to "faire défiler vers le bas, voir la suite de la page",
            VoiceAction.ScrollUp to "faire défiler vers le haut, revenir en arrière dans la page",
            VoiceAction.Back to "revenir à l'écran précédent",
            VoiceAction.Home to "aller à l'écran d'accueil du téléphone",
            VoiceAction.Recents to "voir les applications récemment utilisées",
            VoiceAction.LockScreen to "verrouiller le téléphone",
            VoiceAction.OpenNotifications to "ouvrir le volet des notifications",
            VoiceAction.QuickSettings to "ouvrir les réglages rapides du système",
            VoiceAction.Screenshot to "prendre une capture d'écran",
            VoiceAction.PowerMenu to "ouvrir le menu d'alimentation",
            VoiceAction.TapCenter to "appuyer au centre de l'écran",
            VoiceAction.LongPress to "appuyer longuement au centre de l'écran",
            VoiceAction.DoubleTap to "appuyer deux fois au centre de l'écran",
            VoiceAction.ScrollToTop to "aller tout en haut de la page",
            VoiceAction.ScrollToBottom to "aller tout en bas de la page",
            VoiceAction.ZoomIn to "agrandir, zoomer",
            VoiceAction.ZoomOut to "réduire, dézoomer",
            VoiceAction.VolumeUp to "monter le volume",
            VoiceAction.VolumeDown to "baisser le volume",
            VoiceAction.VolumeMute to "couper le son",
            VoiceAction.MediaPlayPause to "mettre la lecture en pause ou la reprendre",
            VoiceAction.MediaNext to "passer à la piste suivante",
            VoiceAction.MediaPrevious to "revenir à la piste précédente",
        )
    }
}
