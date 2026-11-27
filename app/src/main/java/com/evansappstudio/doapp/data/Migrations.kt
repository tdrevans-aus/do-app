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

/**
 * Provenance, change tracking, soft delete and the event log. Every new column
 * has a default so existing rows and old backups keep working. [updatedAt] is
 * backfilled from createdAt.
 */
object MetaSchema {
    const val ADD_SOURCE = "ALTER TABLE reminder ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'"
    const val ADD_SOURCE_ID = "ALTER TABLE reminder ADD COLUMN sourceId TEXT"
    const val ADD_CATEGORY = "ALTER TABLE reminder ADD COLUMN category TEXT"
    const val ADD_TEMPLATE_ID = "ALTER TABLE reminder ADD COLUMN templateId TEXT"
    const val ADD_TEMPLATE_VERSION = "ALTER TABLE reminder ADD COLUMN templateVersion INTEGER"
    const val ADD_UPDATED_AT = "ALTER TABLE reminder ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
    const val ADD_VERSION = "ALTER TABLE reminder ADD COLUMN version INTEGER NOT NULL DEFAULT 1"
    const val ADD_DELETED_AT = "ALTER TABLE reminder ADD COLUMN deletedAt INTEGER"
    const val ADD_APPROVAL = "ALTER TABLE reminder ADD COLUMN approval TEXT NOT NULL DEFAULT 'NONE'"
    const val ADD_REQUESTED_ALERT_LEVEL = "ALTER TABLE reminder ADD COLUMN requestedAlertLevel TEXT"
    const val BACKFILL_UPDATED_AT = "UPDATE reminder SET updatedAt = createdAt"
    const val CREATE_EVENT_TABLE =
        "CREATE TABLE IF NOT EXISTS `reminder_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`reminderUuid` TEXT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `actor` TEXT NOT NULL, " +
            "`sourceId` TEXT, `detail` TEXT)"
    const val EVENT_INDEX_NAME = "index_reminder_event_reminderUuid_at"
    const val CREATE_EVENT_INDEX =
        "CREATE INDEX IF NOT EXISTS `$EVENT_INDEX_NAME` ON `reminder_event` (`reminderUuid`, `at`)"

    val V4_TO_V5: List<String> = listOf(
        ADD_SOURCE,
        ADD_SOURCE_ID,
        ADD_CATEGORY,
        ADD_TEMPLATE_ID,
        ADD_TEMPLATE_VERSION,
        ADD_UPDATED_AT,
        ADD_VERSION,
        ADD_DELETED_AT,
        ADD_APPROVAL,
        ADD_REQUESTED_ALERT_LEVEL,
        BACKFILL_UPDATED_AT,
        CREATE_EVENT_TABLE,
        CREATE_EVENT_INDEX,
    )
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MetaSchema.V4_TO_V5.forEach { statement -> db.execSQL(statement) }
    }
}
