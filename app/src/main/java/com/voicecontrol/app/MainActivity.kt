package com.voicecontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voicecontrol.app.ui.home.HomeScreen
import com.voicecontrol.app.ui.theme.VoiceControlTheme

/**
 * Unique Activity de l'application.
 *
 * Elle ne contient aucune logique métier : elle installe simplement l'arbre
 * Compose (thème + écran d'accueil). Toute la logique vit dans les ViewModels.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Affichage bord à bord (la barre de statut devient transparente).
        enableEdgeToEdge()
        setContent {
            VoiceControlTheme {
                HomeScreen()
            }
        }
    }
}
