package com.voicecontrol.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.app.BuildConfig
import com.voicecontrol.app.VoiceControlApp
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.permissions.PermissionUtils
import com.voicecontrol.app.core.speech.WakeSound
import com.voicecontrol.app.data.source.ToneSoundFeedback
import com.voicecontrol.app.service.VoiceAccessibilityService
import com.voicecontrol.app.service.VoiceControlService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel de l'écran d'accueil (rôle « VM » de l'architecture MVVM).
 *
 * Il ne connaît ni Compose ni les détails d'affichage : il lit l'état réel du
 * téléphone (autorisations, service, réglages) et expose un unique flux d'état.
 *
 * Il hérite d'AndroidViewModel parce qu'il a besoin d'un Context pour interroger
 * les autorisations et démarrer le service ; ce Context est celui de
 * l'Application, donc aucun risque de fuite mémoire.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VoiceControlApp

    /**
     * Générateur de tonalités réservé à l'aperçu des réglages : quand on choisit
     * un son, on veut l'entendre tout de suite. Le service en a le sien, pour ne
     * pas se disputer la sortie audio avec l'écran.
     */
    private val previewFeedback = ToneSoundFeedback(app.settings)

    /** Dernier état connu des autorisations, tel que lu depuis Android. */
    private val permissions = MutableStateFlow(PermissionSnapshot())

    /** Version installée, connue à la compilation : aucune lecture système à faire. */
    private val appVersion: String = BuildConfig.VERSION_NAME

    /**
     * Phrase non comprise que l'utilisateur a déjà écartée.
     *
     * Le service la publiera encore — il ne sait pas qu'on y a renoncé — donc
     * c'est ici qu'on la masque, en comparant avec ce souvenir.
     */
    private val dismissedUnknown = MutableStateFlow<String?>(null)

    /**
     * Les réglages regroupés en un seul flux : `combine` n'accepte que cinq
     * sources, et l'état de l'écran en a besoin de quatre plus celle-ci.
     */
    private val settingsState = combine(
        app.settings.wakeSound,
        app.settings.wakeWord,
        dismissedUnknown,
    ) { wakeSound, wakeWord, dismissed ->
        SettingsState(
            wakeSound = wakeSound,
            wakeWord = wakeWord,
            dismissedUnknown = dismissed,
        )
    }

    /**
     * État exposé à l'écran.
     *
     * Quatre sources : les autorisations (relues à chaque retour sur l'écran),
     * l'état du service, l'état d'écoute publié par la boucle vocale, et les
     * réglages.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        permissions,
        VoiceControlService.isRunning,
        VoiceControlService.listeningState,
        settingsState,
    ) { snapshot, serviceRunning, listening, settings ->
        HomeUiState(
            serviceRunning = serviceRunning,
            microphoneGranted = snapshot.microphone,
            accessibilityEnabled = snapshot.accessibility,
            notificationsGranted = snapshot.notifications,
            listening = listening,
            appVersion = appVersion,
            wakeSound = settings.wakeSound,
            wakeWord = settings.wakeWord,
            pendingCorrection = listening.unknownPhrase
                ?.takeIf { it != settings.dismissedUnknown },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(),
    )

    init {
        // Premier remplissage, sinon l'écran s'afficherait vide une fraction de seconde.
        refreshPermissions()
    }

    /**
     * Relit l'état réel des autorisations auprès d'Android.
     * À appeler au démarrage de l'écran ET à chaque retour depuis les réglages
     * système : c'est le seul moyen de savoir que l'utilisateur a activé le
     * service d'accessibilité entre-temps.
     */
    fun refreshPermissions() {
        val context = getApplication<Application>()
        permissions.value = PermissionSnapshot(
            microphone = PermissionUtils.isMicrophoneGranted(context),
            accessibility = PermissionUtils.isAccessibilityServiceEnabled(
                context = context,
                serviceClass = VoiceAccessibilityService::class.java,
            ),
            notifications = PermissionUtils.areNotificationsGranted(context),
        )
    }

    /**
     * Allume ou éteint le contrôle vocal.
     * On ne démarre jamais le service si les autorisations manquent : le
     * microphone est exigé par Android pour un service de premier plan de ce type.
     */
    fun setServiceEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (enabled) {
            VoiceControlService.start(context)
        } else {
            VoiceControlService.stop(context)
        }
    }

    /**
     * Change le son de réveil et le fait écouter immédiatement : choisir un son
     * sans l'entendre n'aurait aucun sens.
     */
    fun setWakeSound(sound: WakeSound) {
        app.settings.setWakeSound(sound)
        previewFeedback.previewWakeSound(sound)
    }

    /**
     * Change le mot-clé de réveil. Aucun aperçu n'est possible ici : il faudrait
     * prononcer le mot pour l'entendre, ce qui n'a pas de sens dans un réglage.
     */
    fun setWakeWord(word: String) {
        app.settings.setWakeWord(word)
    }

    /**
     * Apprend qu'une phrase non comprise correspond à une action.
     *
     * C'est le geste d'apprentissage : la phrase devient un synonyme et
     * fonctionnera dès la prochaine fois, sans redémarrage.
     */
    fun bindUnknownPhrase(phrase: String, action: VoiceAction) {
        app.settings.addLearnedAlias(phrase, action)
        dismissedUnknown.value = phrase
    }

    /** Écarte une phrase non comprise sans rien apprendre. */
    fun dismissCorrection(phrase: String) {
        dismissedUnknown.value = phrase
    }

    override fun onCleared() {
        previewFeedback.release()
        super.onCleared()
    }

    /** Petit conteneur interne : état des trois autorisations, sans logique métier. */
    private data class PermissionSnapshot(
        val microphone: Boolean = false,
        val accessibility: Boolean = false,
        val notifications: Boolean = false,
    )

    /** Réglages regroupés, pour ne pas dépasser la limite de `combine`. */
    private data class SettingsState(
        val wakeSound: WakeSound,
        val wakeWord: String,
        val dismissedUnknown: String?,
    )

    private companion object {
        /** On garde l'écoute 5 s après que l'écran est parti, pour éviter les relances inutiles. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
