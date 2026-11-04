package com.voicecontrol.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.voicecontrol.app.R
import com.voicecontrol.app.VoiceControlApp
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.notifications.NotificationHelper
import com.voicecontrol.app.core.speech.ListeningState
import com.voicecontrol.app.core.speech.VoiceListeningController
import com.voicecontrol.app.data.source.AndroidActionPerformer
import com.voicecontrol.app.data.source.AndroidSpeechRecognizer
import com.voicecontrol.app.data.source.ToneSoundFeedback
import com.voicecontrol.app.data.source.VoskSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui porte l'écoute vocale.
 *
 * C'est lui qui a le droit de garder le microphone en vie quand l'écran est
 * éteint ou que l'application passe en arrière-plan — une Activity ne le peut
 * pas. Son rôle se limite à trois choses :
 *
 *  1. afficher la notification permanente exigée par Android ;
 *  2. assembler les deux moteurs d'écoute et le chef d'orchestre ;
 *  3. publier l'état d'écoute pour que l'interface puisse l'afficher.
 *
 * Toute la logique vit dans [VoiceListeningController], qui ne connaît pas Android.
 */
class VoiceControlService : Service() {

    private lateinit var scope: CoroutineScope
    private var controller: VoiceListeningController? = null

    override fun onCreate() {
        super.onCreate()
        // Doit être appelé dans les 5 secondes après startForegroundService(),
        // sinon Android arrête le service. Le type déclaré est "microphone".
        startForeground(
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            NotificationHelper.buildServiceNotification(this),
        )
        _isRunning.value = true

        // Le contrôleur et l'état sont manipulés depuis le thread principal.
        // Le moteur Vosk bascule sa boucle d'écoute sur un thread d'entrée/sortie,
        // et le moteur d'Android exige le thread principal : chacune de ces
        // contraintes est réglée dans son implémentation, pas ici.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val app = application as VoiceControlApp
        val settings = app.settings
        val learning = app.learning

        val listeningController = VoiceListeningController(
            // VEILLE : moteur local et hors ligne. Il tourne en permanence pour
            // guetter le mot-clé, sans consommer de données ni de batterie réseau.
            wakeWordRecognizer = VoskSpeechRecognizer(applicationContext, scope),
            // COMMANDE : moteur d'Android. Sa précision est bien meilleure, et
            // il n'est sollicité que quelques secondes après le mot-clé.
            commandRecognizer = AndroidSpeechRecognizer(applicationContext, scope),
            // Gestes et écriture par l'accessibilité, lancement d'application par
            // le gestionnaire de paquets.
            performer = AndroidActionPerformer(applicationContext),
            // Le bip d'activation, et les sons de retour de commande.
            soundFeedback = ToneSoundFeedback(settings),
            // Le mot-clé se règle depuis l'écran : on le relit à chaque usage.
            wakeWordProvider = { settings.wakeWord.value },
            // Les formulations apprises, pour que les corrections soient
            // appliquées immédiatement.
            learnedAliasesProvider = { learning.learnedAliases.value },
            // Compteur d'usage : une formulation apprise qui sert gagne en poids.
            onLearnedPhraseUsed = learning::recordPhraseUse,
            scope = scope,
        )
        controller = listeningController

        scope.launch {
            listeningController.state.collect { state -> _listeningState.value = state }
        }

        listeningController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // L'écran peut demander l'exécution d'une action choisie parmi les
        // propositions : le service est le seul à pouvoir agir réellement.
        intent?.getStringExtra(EXTRA_ACTION)?.let { actionName ->
            val action = VoiceAction.entries.firstOrNull { it.name == actionName }
            if (action != null) controller?.performFromUi(action)
        }

        // START_STICKY : si Android tue le service faute de mémoire, il le relance.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Libère les deux moteurs et le générateur de tonalités, puis coupe les coroutines.
        controller?.release()
        controller = null
        scope.cancel()

        _isRunning.value = false
        _listeningState.value = ListeningState()
        super.onDestroy()
    }

    companion object {
        /** Action choisie sur l'écran, transmise au service. */
        private const val EXTRA_ACTION = "extra_action"

        /**
         * État observable du service, exposé à l'UI.
         * C'est ce flux qui évite d'avoir à deviner si le service tourne encore :
         * il reflète la réalité du cycle de vie du service.
         */
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        /** Dernier état d'écoute connu, pour l'affichage de l'écran d'accueil. */
        private val _listeningState = MutableStateFlow(ListeningState())
        val listeningState = _listeningState.asStateFlow()

        /** Démarre l'écoute (depuis une Activity au premier plan). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceControlService::class.java),
            )
        }

        /** Arrête l'écoute et retire la notification permanente. */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceControlService::class.java))
        }

        /**
         * Exécute une action choisie par l'utilisateur sur l'écran.
         *
         * Le service tourne déjà : un simple `startService` lui transmet la
         * demande, sans repasser par une notification ni un nouveau démarrage.
         */
        fun perform(context: Context, action: VoiceAction) {
            val intent = Intent(context, VoiceControlService::class.java)
                .putExtra(EXTRA_ACTION, action.name)
            context.startService(intent)
        }
    }
}
