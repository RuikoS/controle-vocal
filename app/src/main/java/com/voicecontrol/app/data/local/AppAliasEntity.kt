package com.voicecontrol.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Une correspondance entre un nom d'application **tel qu'il est prononcé** et
 * l'application réelle.
 *
 * C'est la mémoire des erreurs de transcription : le moteur entend « ouvre you
 * tube » là où l'on voulait YouTube. Une fois la correspondance apprise, elle
 * est essayée avant le rapprochement approximatif sur les libellés.
 *
 * @property spokenName le nom tel qu'il a été entendu, normalisé.
 * @property packageName l'application réellement visée.
 * @property label le libellé de l'application, pour l'affichage.
 * @property useCount fréquence d'usage, qui départage les ambiguïtés.
 */
@Entity(
    tableName = "app_aliases",
    indices = [Index(value = ["spokenName"], unique = true)],
)
data class AppAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spokenName: String,
    val packageName: String,
    val label: String,
    val useCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis(),
)
