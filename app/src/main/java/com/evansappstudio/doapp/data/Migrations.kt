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

/** Existing rows get a new random uuid. New rows set one at insert. */
object UuidSchema {
    const val ADD_COLUMN = "ALTER TABLE reminder ADD COLUMN uuid TEXT NOT NULL DEFAULT ''"
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(UuidSchema.ADD_COLUMN)
        val ids = ArrayList<Long>()
        val cursor = db.query("SELECT id FROM reminder WHERE uuid IS NULL OR uuid = ''")
        try {
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        } finally {
            cursor.close()
        }
        for (id in ids) {
            db.execSQL(
                "UPDATE reminder SET uuid = ? WHERE id = ?",
                arrayOf<Any>(java.util.UUID.randomUUID().toString(), id),
            )
        }
    }
}
