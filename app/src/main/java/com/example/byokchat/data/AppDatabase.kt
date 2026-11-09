package com.example.byokchat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, NoteEntity::class, TodoEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun notes(): NoteDao
    abstract fun todos(): TodoDao

    companion object {
        /** v1 -> v2: branch parent and pinned flag on conversations (existing data is kept). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "byokchat.db")
                .addMigrations(MIGRATION_1_2)
                // v2 -> v3 drops the per-conversation systemPrompt column, and v3 -> v4 drops the
                // per-conversation providerId/model columns (the provider/model choice is now a
                // single global choice in Settings, shared by every conversation). No migration is
                // written for either: the app is still being built and has no user chats to keep,
                // so the database is recreated instead.
                .fallbackToDestructiveMigration()
                .build()
    }
}
