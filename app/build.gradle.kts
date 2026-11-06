plugins {
    alias(libs.plugins.android.application)
    // Le plugin Kotlin ("org.jetbrains.kotlin.android") n'est PLUS nécessaire :
    // depuis AGP 9.0, le support Kotlin est intégré à AGP (built-in Kotlin).
    // Plugin Compose : depuis Kotlin 2.0, le compilateur Compose fait partie du plugin Kotlin.
    alias(libs.plugins.kotlin.compose)
    // Room génère son code à la compilation. KSP plutôt que kapt : kapt est
    // incompatible avec le Kotlin intégré d'AGP 9.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voicecontrol.app"

    // On compile contre l'API 37 : les bibliothèques AndroidX de 2026 l'exigent.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voicecontrol.app"
        minSdk = 26
        // On cible l'API 36 (Android 16) et non 37 : c'est volontaire. targetSdk
        // active les restrictions de comportement d'une version, et notre app
        // repose justement sur les services de premier plan. On absorbera les
        // nouveautés d'Android 17 quand la fonctionnalité sera stabilisée.
        targetSdk = 36
        versionCode = 1
        // Affiché en bas de l'écran d'accueil : une application installée à la
        // main n'a aucune vitrine pour dire de quelle version il s'agit, et on
        // finit par tester une ancienne sans le savoir.
        versionName = "0.14.0"
    }

    buildTypes {
        release {
            // R8 désactivé pour l'instant : on l'activera quand le code sera complet.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Avec le support Kotlin intégré à AGP 9, le jvmTarget du compilateur Kotlin
        // reprend automatiquement la valeur de targetCompatibility : inutile de le régler.
    }

    buildFeatures {
        compose = true
        // Génère la classe BuildConfig, qui contient le numéro de version.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    // Reconnaissance vocale hors ligne. Le modèle français (40 Mo) est embarqué
    // dans app/src/main/assets/model-fr : l'application ne dépend d'aucun réseau.
    implementation(libs.vosk.android)

    // Stockage local de l'apprentissage.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Uniquement pour l'aperçu @Preview dans Android Studio, absent de l'APK final.
    debugImplementation(libs.androidx.ui.tooling)
}
