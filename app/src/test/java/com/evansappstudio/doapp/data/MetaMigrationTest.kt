package com.evansappstudio.doapp.data

import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.obj
import com.evansappstudio.doapp.backup.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class MetaMigrationTest {
    // AT-1
    @Test
    fun at1Version4RowsKeepTheirColumnsAndGainTheDefaults() {
        withVersion4 { connection ->
            insertV4(connection, "uuid-1", "Bins", createdAt = 1_699_000_000_000L, alert = "CRITICAL")
            insertV4(connection, "uuid-2", "Milk", createdAt = 1_699_000_060_000L, alert = "STANDARD")
            connection.createStatement().use { statement -> MetaSchema.V4_TO_V5.forEach { statement.execute(it) } }

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM reminder ORDER BY id").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("uuid-1", rows.getString("uuid"))
                    assertEquals("Bins", rows.getString("title"))
                    assertEquals(1_700_000_000_000L, rows.getLong("dueAt"))
                    assertEquals(5, rows.getInt("nagMinutes"))
                    assertEquals(0, rows.getInt("isDone"))
                    assertEquals(1_699_000_000_000L, rows.getLong("createdAt"))
                    assertEquals("WEEK", rows.getString("repeatUnit"))
                    assertEquals(1_700_000_000_000L, rows.getLong("patternDueAt"))
                    assertEquals("CRITICAL", rows.getString("alertLevel"))
                    assertDefaults(rows, createdAt = 1_699_000_000_000L)
                    assertTrue(rows.next())
                    assertEquals("uuid-2", rows.getString("uuid"))
                    assertEquals("Milk", rows.getString("title"))
                    assertEquals("STANDARD", rows.getString("alertLevel"))
                    assertDefaults(rows, createdAt = 1_699_000_060_000L)
                    assertFalse(rows.next())
                }
                statement.executeQuery("SELECT COUNT(*) FROM reminder_event").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reminder_event'",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(MetaSchema.EVENT_INDEX_NAME, rows.getString("name"))
                }
            }
        }
    }

    // AT-2
    @Test
    fun at2Schema5IsExportedWithBothTablesAndMatchesTheMigration() {
        val schema = parseJson(projectFile("schemas/com.evansappstudio.doapp.data.AppDatabase/5.json").readText()).obj()
        val database = schema.fields["database"]!!.obj()
        assertEquals(5L, (database.fields["version"] as JsonValue.Num).value)
        val entities = (database.fields["entities"] as JsonValue.Arr).items.map { it.obj() }
        val tables = entities.associateBy { (it.fields["tableName"] as JsonValue.Str).value }
        assertEquals(setOf("reminder", "reminder_event"), tables.keys)
        val newColumns = listOf(
            "source", "sourceId", "category", "templateId", "templateVersion",
            "updatedAt", "version", "deletedAt", "approval", "requestedAlertLevel",
        )
        val reminderColumns = columns(tables.getValue("reminder"))
        assertTrue(reminderColumns.keys.containsAll(newColumns))
        val eventColumns = columns(tables.getValue("reminder_event"))
        assertEquals(setOf("id", "reminderUuid", "at", "type", "actor", "sourceId", "detail"), eventColumns.keys)
        val indexNames = (tables.getValue("reminder_event").fields["indices"] as JsonValue.Arr).items
            .map { (it.obj().fields["name"] as JsonValue.Str).value }
        assertEquals(listOf(MetaSchema.EVENT_INDEX_NAME), indexNames)

        // The migrated version 4 table has exactly the columns Room expects, with the same types,
        // nullability and defaults. This is what Room checks on the phone after MIGRATION_4_5.
        withVersion4 { connection ->
            connection.createStatement().use { statement -> MetaSchema.V4_TO_V5.forEach { statement.execute(it) } }
            assertEquals(reminderColumns, pragmaColumns(connection, "reminder"))
            assertEquals(eventColumns, pragmaColumns(connection, "reminder_event"))
        }
    }

    private data class Column(val type: String, val notNull: Boolean, val default: String?)

    private fun columns(entity: JsonValue.Obj): Map<String, Column> {
        return (entity.fields["fields"] as JsonValue.Arr).items.map { it.obj() }.associate { field ->
            val name = (field.fields["columnName"] as JsonValue.Str).value
            val type = (field.fields["affinity"] as JsonValue.Str).value
            val notNull = (field.fields["notNull"] as? JsonValue.Bool)?.value ?: false
            val default = (field.fields["defaultValue"] as? JsonValue.Str)?.value
            name to Column(type, notNull, default)
        }
    }

    private fun pragmaColumns(connection: Connection, table: String): Map<String, Column> {
        val result = LinkedHashMap<String, Column>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                while (rows.next()) {
                    result[rows.getString("name")] = Column(
                        type = rows.getString("type"),
                        notNull = rows.getInt("notnull") == 1 || rows.getInt("pk") == 1,
                        default = rows.getString("dflt_value"),
                    )
                }
            }
        }
        return result
    }

    private fun assertDefaults(rows: java.sql.ResultSet, createdAt: Long) {
        assertEquals("USER", rows.getString("source"))
        assertEquals(1, rows.getInt("version"))
        assertEquals("NONE", rows.getString("approval"))
        assertEquals(createdAt, rows.getLong("updatedAt"))
        for (column in listOf("sourceId", "category", "templateId", "templateVersion", "deletedAt", "requestedAlertLevel")) {
            assertNull(column, rows.getObject(column))
        }
    }

    private fun withVersion4(block: (Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(RepeatSchema.V1_CREATE)
                RepeatSchema.V1_TO_V2.forEach { statement.execute(it) }
                statement.execute(AlertSchema.V2_TO_V3)
                statement.execute(UuidSchema.ADD_COLUMN)
            }
            block(connection)
        }
    }

    private fun insertV4(connection: Connection, uuid: String, title: String, createdAt: Long, alert: String) {
        connection.prepareStatement(
            "INSERT INTO reminder (uuid, title, dueAt, nagMinutes, isDone, doneAt, createdAt, " +
                "repeatInterval, repeatUnit, repeatFrom, patternDueAt, alertLevel) " +
                "VALUES (?, ?, ?, ?, 0, NULL, ?, 1, 'WEEK', 'DUE_DATE', ?, ?)",
        ).use { insert ->
            insert.setString(1, uuid)
            insert.setString(2, title)
            insert.setLong(3, 1_700_000_000_000L)
            insert.setInt(4, 5)
            insert.setLong(5, createdAt)
            insert.setLong(6, 1_700_000_000_000L)
            insert.setString(7, alert)
            insert.executeUpdate()
        }
    }

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile } ?: error("missing $relative from ${File(".").absolutePath}")
    }
}
