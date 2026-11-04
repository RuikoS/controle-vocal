package com.voicecontrol.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Palettes claire et sombre de l'application.
 * Seules les couleurs de marque sont fixées, le reste vient des valeurs
 * par défaut de Material 3 (cohérence garantie sur tous les composants).
 */
private val LightColors = lightColorScheme(
    primary = VoiceBlue,
    primaryContainer = VoiceBlueLight,
)

private val DarkColors = darkColorScheme(
    primary = VoiceBlueLight,
    primaryContainer = VoiceBlueDark,
)

/**
 * Thème global de l'application.
 *
 * La couleur dynamique (Material You, Android 12+) est volontairement
 * désactivée : on veut que les couleurs d'état (vert = accordé, rouge =
 * manquant) restent parfaitement lisibles et identiques sur tous les téléphones.
 *
 * @param darkTheme suit le réglage du système par défaut.
 */
@Composable
fun VoiceControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
