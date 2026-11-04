// Script de build racine : on déclare les plugins sans les appliquer ici,
// chaque module choisit ceux dont il a besoin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
