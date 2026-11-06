package com.voicecontrol.app.core.speech

/**
 * Comment l'application décide d'écouter.
 *
 * Deux usages légitimes, et le choix appartient à l'utilisateur selon ce qu'il
 * est en train de faire.
 */
enum class ActivationMode {

    /**
     * Il faut prononcer le mot-clé avant chaque commande.
     *
     * Le moteur local guette en permanence — économe, et hors ligne. Seule la
     * commande, une fois le mot-clé entendu, part sur Internet. Et le téléphone
     * n'obéit jamais à une phrase entendue par hasard.
     */
    WakeWord,

    /**
     * L'écoute est permanente : les commandes s'exécutent directement, sans
     * mot-clé.
     *
     * Pratique quand on enchaîne les ordres. En contrepartie, le moteur en ligne
     * reste actif en permanence : consommation plus élevée, et tout ce qui est
     * dit à portée du téléphone est transcrit par le service en ligne.
     */
    AlwaysListening,
    ;

    companion object {
        /** Par défaut : le mode prudent. */
        val DEFAULT = WakeWord
    }
}
