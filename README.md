# Contrôle vocal

Application Android native qui pilote le téléphone à la voix, en français :
glissements, défilement, navigation système, lancement d'applications, dictée de
texte — et apprentissage de vos propres formulations.

## Fonctionnement

L'écoute se fait en deux temps, avec deux moteurs différents :

1. **Veille** — un moteur **local** (Vosk, modèle français embarqué) écoute en
   permanence à la recherche du seul mot-clé d'activation. Aucun service en ligne
   ne sait surveiller un mot en continu, et cette partie doit fonctionner sans
   réseau.
2. **Commande** — dès le mot-clé reconnu, un court bip indique qu'il faut parler,
   puis le moteur **du système** transcrit la commande. Sa précision est bien
   meilleure, et seule la commande quitte alors le téléphone — pas ce qu'il
   entend autour.

Les actions sont exécutées par le **service d'accessibilité** (gestes, boutons
système, écriture dans le champ actif) et par le gestionnaire de paquets
(lancement d'application).

## Commandes

| Vous dites | Effet |
|---|---|
| glisse à droite / à gauche / en haut / en bas | glissement du doigt |
| fais défiler vers le bas / vers le haut | défilement d'une page |
| retour · accueil · applications récentes | navigation système |
| verrouille | verrouillage de l'écran (Android 9+) |
| ouvre *\<nom d'application\>* | lancement, avec correspondance approximative |
| écris *\<texte\>* | écriture dans le champ de saisie actif |

Des synonymes sont acceptés (« swipe à droite », « reviens », « défile vers le
bas »…).

## Apprentissage

Quand une phrase n'est pas comprise, l'application propose de la rattacher à une
action. La formulation est ensuite reconnue, **et ses variantes proches aussi** :
apprendre « remonte en haut » fait fonctionner « remonte », « remonte un peu » ou
« remonter ».

Ce n'est pas un réentraînement du modèle acoustique — c'est impossible sur un
téléphone, et inutile. C'est le lien entre le texte reconnu et l'action qui
s'apprend.

## Prérequis

- Android 8.0 (API 26) ou plus récent
- Autorisations : microphone, notifications, service d'accessibilité
- Pour un APK installé hors du Play Store : autoriser les **paramètres
  restreints** de l'application dans ses informations système, sans quoi Android
  refuse l'activation du service d'accessibilité

## Compiler

```bash
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`. Le projet s'ouvre aussi
directement dans Android Studio.

Pile technique : Gradle 9.6, AGP 9.5 (Kotlin intégré), Kotlin 2.2, Compose.
`compileSdk 37`, `targetSdk 36`, `minSdk 26`.

## Architecture

```
core/       logique pure, sans Android (vocabulaire, rapprochement, intentions)
data/       implémentations Android (Vosk, moteur système, accessibilité, tonalités)
service/    service de premier plan et service d'accessibilité
ui/         Compose, MVVM — l'interface ne calcule rien, elle affiche un état
```

Le chef d'orchestre (`VoiceListeningController`) ne connaît ni Android ni Compose :
tout lui arrive par interfaces, ce qui permet de remplacer un moteur sans toucher
au reste.

## Limites connues

- Le modèle local est un modèle « small » (40 Mo) : sa transcription est moins
  précise qu'un moteur en ligne, ce qui n'a d'importance que pour le mot-clé.
- Le réglage de langue n'existe pas : un modèle local est nécessaire par langue.
- La formulation de commandes composées (« ouvre ma banque puis verrouille »)
  n'est pas gérée.

## Crédits

Le modèle de reconnaissance français provient de [Vosk](https://alphacephei.com/vosk/)
(Alpha Cephei), distribué sous licence Apache 2.0.
