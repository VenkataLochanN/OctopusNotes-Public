package com.lochan.octopusnotes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The main database class for the application.
 *
 * @property entities The list of all tables in the database.
 * @property version The current version of the database schema.
 *                   This MUST be incremented every time you change the schema.
 */
@Database(entities = [Notebook::class, Folder::class, Drawing::class], version = 8)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notesDao(): NotesDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN pdfPath TEXT")
            }
        }
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN parentId INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN lastOpened INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN inBin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN inBin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    com.lochan.octopusnotes.BuildConfig.DB_NAME
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration() // safety net only
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Closes the current database and clears the cached instance so the next [getDatabase]
         * call builds a fresh one. Used by import, which must release the on-disk database
         * files before replacing them. Without the reset, [getDatabase] would keep handing out
         * the closed instance and every later query would crash with
         * IllegalStateException ("Attempt to re-open an already-closed object…").
         */
        fun closeAndReset() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}