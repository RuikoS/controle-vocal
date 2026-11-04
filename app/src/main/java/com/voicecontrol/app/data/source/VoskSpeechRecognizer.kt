package com.voicecontrol.app.data.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voicecontrol.app.core.speech.SpeechError
import com.voicecontrol.app.core.speech.SpeechEvent
import com.voicecontrol.app.core.speech.VoiceRecognizer
import com.voicecontrol.app.core.speech.VoiceVocabulary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Implémentation de [VoiceRecognizer] avec **Vosk**, un moteur de reconnaissance
 * vocale entièrement hors ligne : ni Internet, ni compte, ni clé d'API.
 *
 * Différences fondamentales avec le moteur d'Android (`AndroidSpeechRecognizer`) :
 *
 * 1. **Il ne s'arrête pas tout seul.** Vosk découpe lui-même le flux en phrases
 *    et continue à écouter ensuite. Il n'y a donc pas de boucle de relance.
 * 2. **C'est nous qui fournissons le son.** On lit le microphone avec
 *    `AudioRecord` et on pousse les octets dans le moteur, ce qui permet de
 *    maîtriser exactement quand le micro est ouvert.
 * 3. **Il faut un modèle sur le disque.** Les 65 Mo du modèle français ne sont
 *    pas lisibles directement depuis les ressources : il faut d'abord les
 *    recopier dans le stockage interne. C'est long, d'où [SpeechEvent.Preparing].
 *
 * La copie du modèle est faite ICI, à la main, et non par la classe
 * `StorageService` fournie avec la bibliothèque. Deux raisons, toutes deux
 * constatées en lisant son code compilé :
 *
 *  - elle exige un fichier `uuid` à la racine du modèle, absent de la
 *    distribution française, et échoue sans lui ;
 *  - elle copie vers `getExternalFilesDir()`, qui peut être indisponible, au lieu
 *    du stockage interne, qui l'est toujours.
 *
 * @param scope sert uniquement à lancer la boucle d'écoute ; celle-ci s'exécute
 *        ensuite sur un thread d'entrée/sortie, jamais sur le thread principal.
 */
class VoskSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
) : VoiceRecognizer {

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    override val events: Flow<SpeechEvent> = _events.asSharedFlow()

    /**
     * Modèle chargé une seule fois et conservé : il pèse 65 Mo et met plusieurs
     * secondes à s'ouvrir.
     */
    private var model: Model? = null

    private var job: Job? = null

    /** Vrai quand l'application ne se resservira plus du moteur. */
    @Volatile
    private var released = false

    override fun start() {
        if (job != null) return

        if (!hasMicrophonePermission()) {
            _events.tryEmit(SpeechEvent.Failed(SpeechError.MicrophonePermission))
            return
        }

        // On ne signale la préparation que si le modèle n'est pas encore ouvert.
        if (model == null) {
            _events.tryEmit(SpeechEvent.Preparing)
        }

        job = scope.launch(Dispatchers.IO) { runRecognitionLoop() }
    }

    override fun stop() {
        // La lecture audio est bloquante, mais pour un tampon d'environ 100 ms
        // seulement : annuler la coroutine suffit donc à rendre le microphone
        // rapidement. La libération des ressources se fait dans le bloc finally
        // de la boucle.
        job?.cancel()
        job = null
    }

    override fun release() {
        released = true
        job?.cancel()
        job = null
        // Le modèle n'est volontairement PAS fermé ici : la boucle s'en sert
        // peut-être encore le temps qu'elle s'arrête, et le libérer sous ses
        // pieds ferait planter le code natif. C'est la boucle qui s'en charge.
    }

    private suspend fun runRecognitionLoop() {
        val loadedModel = openModel() ?: return

        // Témoin de progression : on veut VOIR que cette étape est franchie.
        _events.tryEmit(SpeechEvent.Diagnostic("Modèle français chargé"))

        // Arrêt demandé pendant le chargement : on referme ce qu'on vient d'ouvrir.
        if (released) {
            loadedModel.close()
            model = null
            return
        }

        val recognizer = createRecognizer(loadedModel)
        val audioRecord = createAudioRecord()

        // Témoin : si cette ligne n'apparaît pas, le problème est du côté du micro.
        if (audioRecord != null) {
            _events.tryEmit(
                SpeechEvent.Diagnostic("Micro ouvert — 16 kHz mono, blocs de $CHUNK_SIZE_BYTES octets"),
            )
        }

        if (recognizer == null || audioRecord == null) {
            recognizer?.close()
            audioRecord?.release()
            _events.tryEmit(
                SpeechEvent.Failed(
                    if (recognizer == null) SpeechError.EngineUnavailable else SpeechError.Audio,
                ),
            )
            return
        }

        try {
            audioRecord.startRecording()
            _events.tryEmit(SpeechEvent.ReadyForSpeech)

            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            while (coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read < 0) {
                    // Code négatif : erreur de lecture du micro. Sans ce message,
                    // la boucle tournerait indéfiniment dans le vide en silence.
                    _events.tryEmit(SpeechEvent.Diagnostic("Lecture du micro en erreur (code $read)"))
                    continue
                }
                if (read == 0) continue

                if (recognizer.acceptWaveForm(buffer, read)) {
                    // Vosk a entendu la fin d'une phrase : le résultat est définitif.
                    val text = jsonField(recognizer.result, "text")
                    if (isUsable(text)) {
                        _events.tryEmit(SpeechEvent.FinalResult(text))
                    }
                } else {
                    // Sinon on est en cours de phrase : transcription provisoire.
                    val partial = jsonField(recognizer.partialResult, "partial")
                    if (isUsable(partial)) {
                        _events.tryEmit(SpeechEvent.PartialResult(partial))
                    }
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recognizer.close()
            if (released) {
                model?.close()
                model = null
            }
        }
    }

    /**
     * Ouvre le modèle, en le recopiant depuis les ressources la toute première fois.
     * Renvoie null si c'est impossible — et le dit à l'écran avec la cause exacte.
     */
    private suspend fun openModel(): Model? {
        model?.let { return it }

        return try {
            val loaded = loadModel()
            model = loaded
            loaded
        } catch (error: Exception) {
            // Message brut du moteur, affiché tel quel : c'est ce qui permet de
            // savoir POURQUOI le modèle a été refusé, au lieu de le deviner.
            _events.tryEmit(
                SpeechEvent.Diagnostic(
                    "Échec du modèle : ${error.javaClass.simpleName} — ${error.message.orEmpty()}",
                ),
            )
            _events.tryEmit(SpeechEvent.Failed(SpeechError.ModelUnavailable))
            null
        }
    }

    private suspend fun loadModel(): Model = withContext(Dispatchers.IO) {
        // WARNINGS : on ne garde que les avertissements. Le niveau DEBUG inonde
        // le journal du système à chaque tampon audio analysé.
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        val modelDirectory = File(context.filesDir, MODEL_DIRECTORY_NAME)

        // Le marqueur n'est écrit qu'APRÈS une copie réussie : une copie
        // interrompue (manque de place, arrêt brutal) ne peut donc pas passer
        // pour un modèle complet.
        if (!File(modelDirectory, READY_MARKER_FILE_NAME).exists()) {
            val copiedFiles = copyAssetDirectory(MODEL_ASSET_DIRECTORY, modelDirectory)
            File(modelDirectory, READY_MARKER_FILE_NAME).writeText(READY_MARKER_CONTENT)
            _events.tryEmit(SpeechEvent.Diagnostic("Modèle copié : $copiedFiles fichiers"))
        }

        Model(modelDirectory.absolutePath)
    }

    /**
     * Recopie récursivement un dossier de ressources vers le stockage interne.
     *
     * `AssetManager.list()` renvoie un tableau vide pour un FICHIER et la liste
     * de ses enfants pour un DOSSIER : c'est ce qui permet de distinguer les deux
     * sans le moindre fichier d'index.
     *
     * @return le nombre de fichiers copiés.
     */
    private fun copyAssetDirectory(assetPath: String, targetDirectory: File): Int {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            // C'est un fichier (éventuellement vide, cas de certains .conf).
            targetDirectory.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(targetDirectory).use { output -> input.copyTo(output) }
            }
            return 1
        }

        targetDirectory.mkdirs()
        return children.sumOf { child ->
            copyAssetDirectory("$assetPath/$child", File(targetDirectory, child))
        }
    }

    /**
     * Reconnaissance LIBRE, volontairement : une première version restreignait le
     * moteur au vocabulaire de l'application (grammaire Vosk), et le résultat
     * s'est révélé MOINS bon qu'en libre sur ce modèle français « small ».
     * Le rapprochement avec le vocabulaire est donc fait en Kotlin, dans
     * [VoiceVocabulary], où l'on maîtrise la tolérance.
     *
     * La fréquence est celle du modèle français : une autre valeur dégraderait
     * tout.
     */
    private fun createRecognizer(loadedModel: Model): Recognizer? =
        try {
            Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())
        } catch (error: Exception) {
            _events.tryEmit(
                SpeechEvent.Diagnostic("Échec du moteur : ${error.message.orEmpty()}"),
            )
            null
        }

    private fun createAudioRecord(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null

        val record = try {
            AudioRecord(
                // Source pensée pour la reconnaissance : le système applique un
                // traitement adapté à la voix plutôt qu'à la musique.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * INTERNAL_BUFFER_MULTIPLIER,
            )
        } catch (error: SecurityException) {
            return null
        }

        return if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            record.release()
            null
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Les résultats de Vosk sont des chaînes JSON : `{"text": "..."}` ou `{"partial": "..."}`. */
    private fun jsonField(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(field) }.getOrDefault("")
    }

    /**
     * Le moteur signale par « [unk] » qu'il n'a pas su écrire ce qu'il a entendu.
     * Transmettre ce jeton n'aurait aucun intérêt : ce n'est pas une parole.
     */
    private fun isUsable(text: String): Boolean =
        text.isNotBlank() && !text.contains(VoiceVocabulary.UNKNOWN_TOKEN)

    private companion object {
        /** Fréquence attendue par le modèle français. */
        const val SAMPLE_RATE_HZ = 16_000

        /** 100 ms de son : compromis entre réactivité et nombre d'appels natifs. */
        const val CHUNK_SIZE_BYTES = 3_200

        /** Tampon interne du micro, exprimé en multiples du minimum imposé par le système. */
        const val INTERNAL_BUFFER_MULTIPLIER = 4

        /** Dossier du modèle dans les ressources de l'application. */
        const val MODEL_ASSET_DIRECTORY = "model-fr"

        /** Dossier de destination dans le stockage interne de l'application. */
        const val MODEL_DIRECTORY_NAME = "model-fr"

        /** Écrit seulement après une copie complète : sert de preuve de réussite. */
        const val READY_MARKER_FILE_NAME = ".modele-pret"

        const val READY_MARKER_CONTENT = "ok"
    }
}
