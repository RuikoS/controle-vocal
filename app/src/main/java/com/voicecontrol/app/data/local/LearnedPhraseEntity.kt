package com.voicecontrol.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Une formulation apprise : une phrase prononcée, rattachée à une action.
 *
 * @property phrase la phrase telle qu'elle a été entendue.
 * @property action le nom de l'action déclenchée. On stocke son **nom** et non
 *           son rang : ajouter une action plus tard ne décalera pas les
 *           apprentissages déjà faits.
 * @property useCount combien de fois cette association a servi. C'est la
 *           fréquence qui départagera les cas ambigus.
 * @property lastUsedAt horodatage du dernier usage, pour privilégier le récent.
 * @property source d'où vient l'apprentissage (`correction`, `import`).
 */
@Entity(
    tableName = "learned_phrases",
    indices = [Index(value = ["phrase"], unique = true)],
)
data class LearnedPhraseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phrase: String,
    val action: String,
    val useCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val source: String = SOURCE_CORRECTION,
) {
    companion object {
        /** L'utilisateur a corrigé une phrase non comprise. */
        const val SOURCE_CORRECTION = "correction"

        /** Récupéré depuis l'ancien stockage en préférences. */
        const val SOURCE_IMPORT = "import"
    }
}
