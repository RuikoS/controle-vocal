package com.voicecontrol.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base locale de l'apprentissage. Rien ne sort du téléphone.
 *
 * `exportSchema = false` : le schéma n'est pas publié. Si le format évolue, il
 * faudra écrire une migration — c'est justement pour ne pas oublier cette
 * question que Room la signale dès le premier changement.
 */
@Database(
    entities = [LearnedPhraseEntity::class, AppAliasEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VoiceControlDatabase : RoomDatabase() {

    abstract fun learningDao(): LearningDao

    companion object {
        private const val DATABASE_NAME = "voice-control.db"

        @Volatile
        private var instance: VoiceControlDatabase? = null

        /** Une seule instance pour toute l'application : Room la gère lui-même en interne. */
        fun get(context: Context): VoiceControlDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(context.applicationContext, VoiceControlDatabase::class.java, DATABASE_NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
