package com.voicecontrol.app.core.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Lecture de l'état des autorisations et création des intents vers les écrans
 * de réglages concernés.
 *
 * Point important : les trois autorisations dont nous avons besoin ont trois
 * mécanismes complètement différents.
 *  - Micro et notifications : autorisations « à l'exécution », on peut afficher
 *    une boîte de dialogue système.
 *  - Service d'accessibilité : AUCUNE boîte de dialogue possible, l'utilisateur
 *    doit l'activer lui-même dans un écran de réglages. On ne peut donc pas la
 *    demander, seulement constater qu'elle est active et y amener l'utilisateur.
 */
object PermissionUtils {

    /** Vrai si l'utilisateur a autorisé le microphone. */
    fun isMicrophoneGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Vrai si l'utilisateur a autorisé les notifications.
     * Avant Android 13 (API 33) cette autorisation n'existe pas : on considère
     * qu'elle est toujours accordée.
     */
    fun areNotificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * Vrai si notre service d'accessibilité est bien activé.
     *
     * Android stocke la liste des services activés dans un réglage sécurisé,
     * sous la forme "paquet/classe:paquet/classe". On découpe cette chaîne et on
     * compare paquet + nom de classe : c'est plus fiable qu'une comparaison de
     * texte brute, car le système peut écrire la classe en forme abrégée.
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<out AccessibilityService>,
    ): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val expected = ComponentName(context, serviceClass)
        return enabledServices
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == expected.packageName && it.className == expected.className }
    }

    /** Ouvre l'écran système « Accessibilité » : c'est là que l'utilisateur active notre service. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * Ouvre l'écran des notifications de notre application.
     * Utilisé si l'utilisateur a refusé définitivement les notifications : la
     * boîte de dialogue ne s'affiche plus, seuls les réglages permettent de corriger.
     */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
