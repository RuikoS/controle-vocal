package com.voicecontrol.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux apprentissages.
 *
 * Les lectures sont exposées en `Flow` : tout changement est immédiatement
 * répercuté dans l'écran et dans la logique de reconnaissance, sans qu'aucun des
 * deux n'ait à demander un rafraîchissement.
 */
@Dao
interface LearningDao {

    // ---- Formulations apprises ----

    @Query("SELECT * FROM learned_phrases ORDER BY useCount DESC, lastUsedAt DESC")
    fun observePhrases(): Flow<List<LearnedPhraseEntity>>

    @Query("SELECT * FROM learned_phrases WHERE phrase = :phrase LIMIT 1")
    suspend fun findPhrase(phrase: String): LearnedPhraseEntity?

    @Query("SELECT * FROM learned_phrases ORDER BY useCount DESC")
    suspend fun allPhrases(): List<LearnedPhraseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhrase(entity: LearnedPhraseEntity)

    /** Une formulation qui sert encore gagne en importance. */
    @Query("UPDATE learned_phrases SET useCount = useCount + 1, lastUsedAt = :now WHERE phrase = :phrase")
    suspend fun touchPhrase(phrase: String, now: Long)

    @Query("DELETE FROM learned_phrases WHERE phrase = :phrase")
    suspend fun deletePhrase(phrase: String)

    @Query("DELETE FROM learned_phrases")
    suspend fun clearPhrases()

    // ---- Correspondances d'applications ----

    @Query("SELECT * FROM app_aliases ORDER BY useCount DESC, lastUsedAt DESC")
    fun observeAppAliases(): Flow<List<AppAliasEntity>>

    @Query("SELECT * FROM app_aliases WHERE spokenName = :spokenName LIMIT 1")
    suspend fun findAppAlias(spokenName: String): AppAliasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppAlias(entity: AppAliasEntity)

    @Query("UPDATE app_aliases SET useCount = useCount + 1, lastUsedAt = :now WHERE spokenName = :spokenName")
    suspend fun touchAppAlias(spokenName: String, now: Long)

    @Query("DELETE FROM app_aliases")
    suspend fun clearAppAliases()
}
