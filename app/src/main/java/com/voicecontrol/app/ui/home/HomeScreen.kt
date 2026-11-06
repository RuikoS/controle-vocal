package com.voicecontrol.app.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicecontrol.app.R
import com.voicecontrol.app.core.actions.VoiceAction
import com.voicecontrol.app.core.actions.VoiceIntent
import com.voicecontrol.app.core.permissions.PermissionUtils
import com.voicecontrol.app.core.speech.ActivationMode
import com.voicecontrol.app.core.speech.Ambiguity
import com.voicecontrol.app.core.speech.ListeningState
import com.voicecontrol.app.core.speech.ListeningStatus
import com.voicecontrol.app.core.speech.SpeechError
import com.voicecontrol.app.core.speech.VoiceVocabulary
import com.voicecontrol.app.core.speech.WakeSound
import com.voicecontrol.app.ui.theme.StatusGreen
import com.voicecontrol.app.ui.theme.VoiceControlTheme

/**
 * Écran d'accueil : la vue (le « V » de MVVM).
 *
 * Elle se contente de deux choses :
 *  1. relayer les intentions de l'utilisateur vers le ViewModel ;
 *  2. dessiner l'état que le ViewModel lui donne, sans le modifier.
 *
 * @param viewModel injecté automatiquement par Compose, aucune usine à gaz à écrire.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Micro et notifications sont des autorisations « à l'exécution » : on peut
    // afficher la boîte de dialogue système. Le résultat arrive dans le callback,
    // on en profite pour relire l'état réel des autorisations.
    val microphoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    // Cœur de l'assistant : quand on revient sur l'écran (par exemple après avoir
    // activé le service d'accessibilité dans les réglages), on relit tout.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    HomeContent(
        uiState = uiState,
        onRequestMicrophone = {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Avant Android 13 il n'y a pas de boîte de dialogue : on ouvre les réglages.
                context.startActivity(PermissionUtils.appNotificationSettingsIntent(context))
            }
        },
        onOpenAccessibilitySettings = {
            // Le service d'accessibilité n'a pas de boîte de dialogue : on amène
            // l'utilisateur sur l'écran de réglages, il l'active lui-même.
            context.startActivity(PermissionUtils.accessibilitySettingsIntent())
        },
        onToggleService = viewModel::setServiceEnabled,
        onSelectWakeSound = viewModel::setWakeSound,
        onSaveWakeWord = viewModel::setWakeWord,
        onBindUnknownPhrase = viewModel::bindUnknownPhrase,
        onDismissCorrection = viewModel::dismissCorrection,
        onChooseSuggestion = viewModel::chooseSuggestion,
        onSelectActivationMode = viewModel::setActivationMode,
        onCaptureCommand = viewModel::captureCommand,
    )
}

/**
 * Partie « sans état » de l'écran : elle ne reçoit que des données et des
 * callbacks. C'est cette fonction qui est prévisualisable dans Android Studio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onSelectWakeSound: (WakeSound) -> Unit = {},
    onSaveWakeWord: (String) -> Unit = {},
    onBindUnknownPhrase: (String, VoiceAction) -> Unit = { _, _ -> },
    onDismissCorrection: (String) -> Unit = {},
    onChooseSuggestion: (VoiceAction) -> Unit = {},
    onSelectActivationMode: (ActivationMode) -> Unit = {},
    onCaptureCommand: () -> Unit = {},
) {
    // Les trois étapes de l'assistant de configuration.
    val permissionItems = listOf(
        PermissionItem(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.permission_microphone_title),
            description = stringResource(R.string.permission_microphone_description),
            granted = uiState.microphoneGranted,
            actionLabel = stringResource(R.string.action_grant),
            onAction = onRequestMicrophone,
        ),
        PermissionItem(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.permission_notifications_title),
            description = stringResource(R.string.permission_notifications_description),
            granted = uiState.notificationsGranted,
            actionLabel = stringResource(R.string.action_grant),
            onAction = onRequestNotifications,
        ),
        PermissionItem(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.permission_accessibility_title),
            description = stringResource(R.string.permission_accessibility_description),
            granted = uiState.accessibilityEnabled,
            actionLabel = stringResource(R.string.action_open_accessibility_settings),
            onAction = onOpenAccessibilitySettings,
        ),
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ServiceToggleCard(uiState = uiState, onToggleService = onToggleService)

            // Déclenchement manuel : l'alternative au mot-clé. Désactivé en
            // écoute permanente, où le moteur écoute déjà — un bouton sans effet
            // serait une promesse non tenue.
            Button(
                onClick = onCaptureCommand,
                enabled = uiState.serviceRunning &&
                    uiState.activationMode != ActivationMode.AlwaysListening,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.capture_button))
            }

            RecognitionCard(listening = uiState.listening, wakeWord = uiState.wakeWord)

            // Phrase comprise à moitié : on propose, l'utilisateur tranche, et
            // son choix vaut apprentissage.
            uiState.pendingAmbiguity?.let { ambiguity ->
                AmbiguityCard(
                    ambiguity = ambiguity,
                    onChoose = onChooseSuggestion,
                    onDismiss = { onDismissCorrection(ambiguity.phrase) },
                )
            }

            // Phrase non comprise : on propose de l'apprendre plutôt que de
            // laisser l'utilisateur se demander pourquoi ça n'a pas marché.
            uiState.pendingCorrection?.let { phrase ->
                CorrectionCard(
                    phrase = phrase,
                    onBind = { action -> onBindUnknownPhrase(phrase, action) },
                    onDismiss = { onDismissCorrection(phrase) },
                )
            }

            WakeSoundCard(selected = uiState.wakeSound, onSelect = onSelectWakeSound)

            WakeWordCard(wakeWord = uiState.wakeWord, onSave = onSaveWakeWord)

            ActivationModeCard(
                selected = uiState.activationMode,
                onSelect = onSelectActivationMode,
            )

            PermissionAssistant(permissionItems = permissionItems)

            // Numéro de version : une application installée à la main n'a aucune
            // vitrine pour l'indiquer, et on finit par tester une ancienne
            // version en croyant tester la nouvelle.
            if (uiState.appVersion.isNotBlank()) {
                Text(
                    text = stringResource(R.string.app_version_label, uiState.appVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/**
 * Carte principale : le bouton ON/OFF et l'état courant en grand.
 * L'interrupteur est désactivé tant que les autorisations ne sont pas complètes,
 * ce qui évite un démarrage voué à l'échec.
 */
@Composable
private fun ServiceToggleCard(
    uiState: HomeUiState,
    onToggleService: (Boolean) -> Unit,
) {
    val hintRes = when {
        uiState.serviceRunning -> R.string.home_state_hint_on
        uiState.permissionsGranted -> R.string.home_state_hint_off
        else -> R.string.home_state_hint_missing
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_toggle_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (uiState.serviceRunning) R.string.home_state_on else R.string.home_state_off,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.serviceRunning) {
                        StatusGreen
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.serviceRunning,
                onCheckedChange = onToggleService,
                enabled = uiState.permissionsGranted,
            )
        }
    }
}

/**
 * Carte de la reconnaissance vocale : où en est l'écoute, ce qui est en train
 * d'être compris, et la dernière phrase reconnue.
 */
@Composable
private fun RecognitionCard(listening: ListeningState, wakeWord: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.recognition_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = recognitionStatusLabel(listening, wakeWord),
                style = MaterialTheme.typography.labelLarge,
                color = when (listening.status) {
                    ListeningStatus.Error -> MaterialTheme.colorScheme.error
                    ListeningStatus.Listening -> StatusGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // Transcription provisoire : elle change à chaque syllabe comprise.
            if (listening.partialText.isNotBlank()) {
                Text(
                    text = listening.partialText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.recognition_last_phrase),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val nothingHeard = listening.lastPhrase.isBlank()
            Text(
                text = if (nothingHeard) {
                    stringResource(R.string.recognition_nothing_yet)
                } else {
                    listening.lastPhrase
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (nothingHeard) FontWeight.Normal else FontWeight.SemiBold,
            )

            // Ce que le téléphone vient de faire : c'est notre seul retour
            // puisqu'il ne parle pas.
            Text(
                text = stringResource(R.string.recognition_last_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val intentLabel = listening.lastIntent?.let { intentLabel(it) }
            Text(
                text = when {
                    intentLabel == null -> stringResource(R.string.recognition_no_action)
                    listening.actionFailed -> stringResource(R.string.recognition_action_failed, intentLabel)
                    else -> intentLabel
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (listening.lastIntent != null) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    // Rouge si l'action a échoué, vert si elle a abouti : la
                    // couleur doit suffire à lire le résultat sans lire le texte.
                    listening.actionFailed -> MaterialTheme.colorScheme.error
                    listening.lastIntent != null -> StatusGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // Ligne technique de mise au point : elle dit exactement où en est le
            // moteur. À retirer une fois la reconnaissance stabilisée.
            listening.diagnostic?.let { diagnostic ->
                Text(
                    text = diagnostic,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Proposition de trancher entre plusieurs actions possibles.
 *
 * C'est le niveau intermédiaire du score de confiance : la phrase ressemble à
 * plusieurs commandes sans être tranchante. Les options sont classées de la plus
 * probable à la moins probable, et le choix de l'utilisateur est **appris** —
 * donc la prochaine fois, la question ne se posera plus.
 */
@Composable
private fun AmbiguityCard(
    ambiguity: Ambiguity,
    onChoose: (VoiceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ambiguity_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.ambiguity_hint, ambiguity.phrase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Une ligne par option : des libellés longs ne tiennent pas côte à côte.
            ambiguity.options.forEach { action ->
                OutlinedButton(
                    onClick = { onChoose(action) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(actionLabelRes(action)))
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.correction_dismiss))
            }
        }
    }
}

/**
 * Proposition de rattacher une phrase non comprise à une action.
 *
 * C'est le mécanisme d'apprentissage de l'application : une validation suffit
 * pour que la phrase devienne un synonyme et fonctionne la fois suivante. Aucun
 * modèle n'est réentraîné — on apprend simplement **le vocabulaire de la
 * personne**, ce qui est le seul apprentissage utile ici.
 */
@Composable
private fun CorrectionCard(
    phrase: String,
    onBind: (VoiceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // La clé `phrase` remet le menu à zéro quand une nouvelle phrase arrive.
    var actionsOpen by rememberSaveable(phrase) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.correction_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.correction_hint, phrase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // `Box` sert d'ancre au menu déroulant : il s'ouvre sous le bouton.
            Box {
                OutlinedButton(onClick = { actionsOpen = true }) {
                    Text(stringResource(R.string.correction_choose))
                }
                DropdownMenu(
                    expanded = actionsOpen,
                    onDismissRequest = { actionsOpen = false },
                ) {
                    // Une entrée par action, pas par synonyme : on choisit ce que
                    // la phrase doit FAIRE, pas comment on le dit.
                    VoiceVocabulary.COMMANDS.distinctBy { it.action }.forEach { command ->
                        DropdownMenuItem(
                            text = { Text(stringResource(actionLabelRes(command.action))) },
                            onClick = {
                                actionsOpen = false
                                // Choisir l'action suffit : aucune confirmation
                                // supplémentaire. L'utilisateur vient d'exprimer
                                // ce que sa phrase voulait dire.
                                onBind(command.action)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.correction_dismiss))
                }
            }
        }
    }
}

/**
 * Choix du mode d'écoute.
 *
 * Arbitrage que l'utilisateur est seul à pouvoir faire : le mot-clé protège la
 * batterie et la vie privée, l'écoute permanente évite de le répéter quand on
 * enchaîne les ordres. Les deux sont légitimes selon le moment.
 */
@Composable
private fun ActivationModeCard(selected: ActivationMode, onSelect: (ActivationMode) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.activation_title),
                style = MaterialTheme.typography.titleMedium,
            )

            ActivationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == selected,
                            onClick = { onSelect(mode) },
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = mode == selected, onClick = null)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            // Aligne le texte sur la pastille du bouton radio.
                            .padding(top = 12.dp),
                    ) {
                        Text(
                            text = stringResource(activationLabelRes(mode)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(activationHintRes(mode)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Libellé d'un mode d'écoute. */
private fun activationLabelRes(mode: ActivationMode): Int = when (mode) {
    ActivationMode.WakeWord -> R.string.activation_wake_word
    ActivationMode.AlwaysListening -> R.string.activation_always
}

/** Explication d'un mode d'écoute, avec son compromis. */
private fun activationHintRes(mode: ActivationMode): Int = when (mode) {
    ActivationMode.WakeWord -> R.string.activation_wake_word_hint
    ActivationMode.AlwaysListening -> R.string.activation_always_hint
}

/**
 * Réglage du mot-clé de réveil.
 *
 * C'est le réglage le plus important de l'application : c'est lui qui décide de
 * la fiabilité de la détection. Le texte n'est appliqué qu'au moment de
 * l'enregistrement, pour ne pas changer le mot-clé à chaque lettre tapée.
 */
@Composable
private fun WakeWordCard(wakeWord: String, onSave: (String) -> Unit) {
    // La saisie en cours reste locale : elle ne doit pas être publiée à chaque
    // frappe. La clé `wakeWord` remet le brouillon à jour si le réglage change
    // ailleurs — après un enregistrement, par exemple.
    var draft by rememberSaveable(wakeWord) { mutableStateOf(wakeWord) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_wake_word_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_wake_word_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_wake_word_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { draft = wakeWord },
                    enabled = draft != wakeWord,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft.isNotBlank() && draft != wakeWord,
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

/**
 * Choix du son de réveil.
 *
 * Toucher une ligne l'adopte ET la fait écouter aussitôt : on ne peut pas
 * choisir un son qu'on n'entend pas.
 */
@Composable
private fun WakeSoundCard(selected: WakeSound, onSelect: (WakeSound) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_wake_sound_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_wake_sound_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WakeSound.entries.forEach { sound ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // `selectable` plutôt que `clickable` : le lecteur d'écran
                        // annonce alors correctement le choix, et l'état sélectionné.
                        .selectable(
                            selected = sound == selected,
                            onClick = { onSelect(sound) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = sound == selected, onClick = null)
                    Text(
                        text = stringResource(wakeSoundLabelRes(sound)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** Libellé français d'un son de réveil. */
private fun wakeSoundLabelRes(sound: WakeSound): Int = when (sound) {
    WakeSound.Beep -> R.string.sound_beep
    WakeSound.Pip -> R.string.sound_pip
    WakeSound.Acknowledge -> R.string.sound_acknowledge
    WakeSound.Negative -> R.string.sound_negative
    WakeSound.Alert -> R.string.sound_alert
    WakeSound.Silent -> R.string.sound_silent
}

/**
 * Libellé français d'une intention, pour l'affichage.
 *
 * L'intention est traduite ici, et non dans la couche vocale : celle-ci ne
 * produit que des types, jamais de texte destiné à l'utilisateur.
 */
@Composable
private fun intentLabel(intent: VoiceIntent): String = when (intent) {
    is VoiceIntent.Execute -> stringResource(actionLabelRes(intent.action))
    is VoiceIntent.LaunchApp -> stringResource(R.string.intent_launch_app, intent.spokenName)
    is VoiceIntent.TypeText -> stringResource(R.string.intent_type_text, intent.text)
}

/** Libellé français d'une action, pour l'affichage. */
private fun actionLabelRes(action: VoiceAction): Int = when (action) {
    VoiceAction.SwipeRight -> R.string.action_swipe_right
    VoiceAction.SwipeLeft -> R.string.action_swipe_left
    VoiceAction.SwipeUp -> R.string.action_swipe_up
    VoiceAction.SwipeDown -> R.string.action_swipe_down
    VoiceAction.ScrollDown -> R.string.action_scroll_down
    VoiceAction.ScrollUp -> R.string.action_scroll_up
    VoiceAction.Back -> R.string.action_back
    VoiceAction.Home -> R.string.action_home
    VoiceAction.Recents -> R.string.action_recents
    VoiceAction.LockScreen -> R.string.action_lock_screen
}

/**
 * Nombre de tours d'écoute ratés d'affilée au-delà duquel on l'affiche.
 * En dessous, c'est du silence normal entre deux phrases.
 */
private const val SILENCE_HINT_THRESHOLD = 3

/** Libellé français de l'état d'écoute, ou le message de la panne en cours. */
@Composable
private fun recognitionStatusLabel(listening: ListeningState, wakeWord: String): String = when (listening.status) {
    ListeningStatus.Idle -> stringResource(R.string.recognition_status_idle)

    ListeningStatus.Preparing -> stringResource(R.string.recognition_status_preparing)

    // Veille : le moteur écoute, mais n'obéira pas avant le mot-clé.
    ListeningStatus.WaitingForWakeWord -> stringResource(
        R.string.recognition_status_waiting_wake_word,
        VoiceVocabulary.displayWakeWord(wakeWord),
    )

    ListeningStatus.Listening ->
        // Diagnostic essentiel : si le moteur enchaîne les tours sans rien
        // comprendre, il tourne mais n'entend rien d'exploitable. Sans ce
        // message, ce cas serait indiscernable d'une écoute normale.
        if (listening.silenceCount >= SILENCE_HINT_THRESHOLD) {
            stringResource(R.string.recognition_status_listening_deaf, listening.silenceCount)
        } else {
            stringResource(R.string.recognition_status_listening)
        }

    ListeningStatus.Processing -> stringResource(R.string.recognition_status_processing)

    ListeningStatus.Error -> when (val error = listening.error) {
        null -> stringResource(R.string.recognition_status_error)

        // Le code brut du moteur est affiché en plus du message : c'est un outil
        // de diagnostic pendant la mise au point. On le retirera ensuite.
        else -> listening.lastErrorCode
            ?.let { code ->
                stringResource(R.string.speech_error_with_code, stringResource(speechErrorRes(error)), code)
            }
            ?: stringResource(speechErrorRes(error))
    }
}

/**
 * Traduction d'une panne de reconnaissance en texte affichable.
 * Le mapping vit ici, dans la couche interface : la couche vocale, elle, ne
 * connaît que [SpeechError] et n'a aucun texte en dur.
 */
private fun speechErrorRes(error: SpeechError): Int = when (error) {
    SpeechError.Unavailable -> R.string.speech_error_unavailable
    SpeechError.ModelUnavailable -> R.string.speech_error_model
    SpeechError.EngineUnavailable -> R.string.speech_error_engine
    SpeechError.MicrophonePermission -> R.string.speech_error_microphone
    SpeechError.LanguageUnsupported -> R.string.speech_error_language
    SpeechError.Busy -> R.string.speech_error_busy
    SpeechError.Network -> R.string.speech_error_network
    SpeechError.Audio -> R.string.speech_error_audio
    SpeechError.Unknown -> R.string.speech_error_unknown
}

/** L'assistant : titre, progression, puis une carte par autorisation. */
@Composable
private fun PermissionAssistant(permissionItems: List<PermissionItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.assistant_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.assistant_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val grantedCount = permissionItems.count { it.granted }
        val allGranted = grantedCount == permissionItems.size
        Text(
            text = if (allGranted) {
                stringResource(R.string.assistant_all_granted)
            } else {
                stringResource(R.string.assistant_progress, grantedCount, permissionItems.size)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (allGranted) StatusGreen else MaterialTheme.colorScheme.error,
        )

        permissionItems.forEach { item ->
            PermissionCard(item = item)
        }
    }
}

/**
 * Carte d'une autorisation : icône, titre, état, explication, et bouton d'action
 * tant que l'autorisation manque. Une fois accordée, le bouton disparaît pour
 * que l'écran reste lisible.
 */
@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = if (item.granted) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(
                            if (item.granted) {
                                R.string.permission_state_granted
                            } else {
                                R.string.permission_state_denied
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.granted) StatusGreen else MaterialTheme.colorScheme.error,
                    )
                }
                if (item.granted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = StatusGreen,
                    )
                }
            }

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!item.granted) {
                Button(
                    onClick = item.onAction,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(item.actionLabel)
                }
            }
        }
    }
}

/** Données d'affichage d'une autorisation. Type privé : c'est un détail de cette vue. */
private data class PermissionItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit,
)

/** Aperçu dans Android Studio : état « partiellement configuré ». */
@Preview(showBackground = true, name = "Accueil - configuration en cours")
@Composable
private fun HomeContentPreview() {
    VoiceControlTheme {
        HomeContent(
            uiState = HomeUiState(
                serviceRunning = false,
                microphoneGranted = true,
                accessibilityEnabled = false,
                notificationsGranted = true,
            ),
            onRequestMicrophone = {},
            onRequestNotifications = {},
            onOpenAccessibilitySettings = {},
            onToggleService = {},
        )
    }
}

/** Aperçu : tout est prêt, le service tourne et vient de comprendre une phrase. */
@Preview(showBackground = true, name = "Accueil - en écoute")
@Composable
private fun HomeContentActivePreview() {
    VoiceControlTheme {
        HomeContent(
            uiState = HomeUiState(
                serviceRunning = true,
                microphoneGranted = true,
                accessibilityEnabled = true,
                notificationsGranted = true,
                listening = ListeningState(
                    status = ListeningStatus.Listening,
                    partialText = "ouvre",
                    lastPhrase = "allume la lumière du salon",
                ),
            ),
            onRequestMicrophone = {},
            onRequestNotifications = {},
            onOpenAccessibilitySettings = {},
            onToggleService = {},
        )
    }
}
