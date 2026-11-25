package com.evansappstudio.doapp.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        RepeatSchema.V1_TO_V2.forEach { statement -> db.execSQL(statement) }
    }
}
