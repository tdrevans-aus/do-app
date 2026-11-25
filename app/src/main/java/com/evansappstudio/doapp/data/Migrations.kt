package com.evansappstudio.doapp.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        RepeatSchema.V1_TO_V2.forEach { statement -> db.execSQL(statement) }
    }
}

/** Existing rows become Standard. New reminders can choose another level. */
object AlertSchema {
    const val V2_TO_V3: String =
        "ALTER TABLE reminder ADD COLUMN alertLevel TEXT NOT NULL DEFAULT 'STANDARD'"
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(AlertSchema.V2_TO_V3)
    }
}
