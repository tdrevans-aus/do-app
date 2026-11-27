package com.evansappstudio.doapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * QA (Spec 9), independent of the builder's tests.
 *
 * The builder's AT-1 migrates two near-identical rows, and every DAO behaviour test runs
 * against the in-memory fake in SchedulerFixture, which re-implements the SQL by hand.
 * These tests migrate a realistic version 4 table (plain, repeating from due, repeating
 * from completion, weekly on set days, done, Critical, no nag, non-ASCII title) and run
 * the real @Query text from ReminderDao.kt and ReminderEventDao.kt against it with
 * sqlite-jdbc, so a missing deletedAt filter, a double version bump or a wrong trim in the
 * real SQL is caught on the host.
 */
class Spec9QaMigrationTest {
    private val v4Columns = listOf(
        "id", "title", "dueAt", "nagMinutes", "isDone", "doneAt", "createdAt",
        "repeatInterval", "repeatUnit", "repeatFrom", "repeatWeekDays", "repeatMonthOn",
        "patternDueAt", "alertLevel", "uuid",
    )

    @Test
    fun qaRealisticVersion4RowsSurviveTheMigrationUnchanged() {
        withMigrated(seed = true) { connection, before ->
            val after = readRows(connection, v4Columns)
            assertEquals("every version 4 column of every row is untouched", before, after)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM reminder ORDER BY id").use { rows ->
                    var count = 0
                    while (rows.next()) {
                        count++
                        assertEquals("USER", rows.getString("source"))
                        assertEquals(1, rows.getInt("version"))
                        assertEquals("NONE", rows.getString("approval"))
                        assertEquals(rows.getLong("createdAt"), rows.getLong("updatedAt"))
                        for (column in listOf("sourceId", "category", "templateId", "templateVersion", "deletedAt", "requestedAlertLevel")) {
                            assertNull(column, rows.getObject(column))
                        }
                    }
                    assertEquals(SEED.size, count)
                }
            }
        }
    }

    @Test
    fun qaRealDaoReadsSkipASoftDeletedRowAndGetByIdStillReturnsIt() {
        withMigrated(seed = true) { connection, _ ->
            val dao = sql(DAO_FILE)
            // Soft delete the Critical row (id 4) with the real query.
            assertEquals(1, update(connection, dao.getValue("softDelete"), 4L, NOW))
            assertEquals("a second soft delete changes nothing", 0, update(connection, dao.getValue("softDelete"), 4L, NOW + 1))

            val observed = ids(connection, dao.getValue("observeAll"))
            val active = ids(connection, dao.getValue("active"))
            val all = ids(connection, dao.getValue("all"))
            val every = ids(connection, dao.getValue("allIncludingDeleted"))
            assertFalse(4L in observed)
            assertFalse(4L in active)
            assertFalse(4L in all)
            assertTrue(4L in every)
            assertEquals(active.size.toLong(), scalar(connection, dao.getValue("activeCount")))
            assertEquals(listOf(4L), ids(connection, dao.getValue("getById"), 4L))
            assertEquals(listOf(4L), ids(connection, dao.getValue("deletedBefore"), NOW + 1))
            assertEquals(emptyList<Long>(), ids(connection, dao.getValue("deletedBefore"), NOW))

            // Every live-row write leaves the deleted row alone.
            assertEquals(0, update(connection, dao.getValue("markDone"), 4L, NOW, NOW))
            assertEquals(0, update(connection, dao.getValue("updateDueAt"), 4L, NOW, NOW))
            assertEquals(0, update(connection, dao.getValue("touch"), 4L, NOW))
            assertEquals(0, update(connection, dao.getValue("updateDueAndPattern"), 4L, NOW, null, NOW))
            assertEquals(0, update(connection, dao.getValue("advanceOccurrence"), 4L, NOW, null, NOW))
            assertEquals(2L, scalar(connection, "SELECT version FROM reminder WHERE id = 4"))

            // Restore bumps once and the row is visible again.
            assertEquals(1, update(connection, dao.getValue("restore"), 4L, NOW + 5))
            assertEquals(3L, scalar(connection, "SELECT version FROM reminder WHERE id = 4"))
            assertEquals(NOW + 5, scalar(connection, "SELECT updatedAt FROM reminder WHERE id = 4"))
            assertTrue(4L in ids(connection, dao.getValue("active")))
        }
    }

    @Test
    fun qaRealDaoWritesBumpVersionExactlyOnce() {
        withMigrated(seed = true) { connection, _ ->
            val dao = sql(DAO_FILE)
            // A done row (id 5) cannot be marked done again, and the bump is one per call.
            assertEquals(0, update(connection, dao.getValue("markDone"), 5L, NOW, NOW))
            assertEquals(1L, scalar(connection, "SELECT version FROM reminder WHERE id = 5"))
            assertEquals(1, update(connection, dao.getValue("markDone"), 1L, NOW, NOW + 1))
            assertEquals(2L, scalar(connection, "SELECT version FROM reminder WHERE id = 1"))
            assertEquals(NOW + 1, scalar(connection, "SELECT updatedAt FROM reminder WHERE id = 1"))
            assertEquals(1, update(connection, dao.getValue("advanceOccurrence"), 2L, NOW + DAY, NOW + DAY, NOW + 2))
            assertEquals(2L, scalar(connection, "SELECT version FROM reminder WHERE id = 2"))
            assertEquals(1, update(connection, dao.getValue("touch"), 2L, NOW + 3))
            assertEquals(3L, scalar(connection, "SELECT version FROM reminder WHERE id = 2"))
        }
    }

    @Test
    fun qaRealPurgeRemovesOnlyRowsDeletedBeforeTheCutoff() {
        withMigrated(seed = true) { connection, _ ->
            val dao = sql(DAO_FILE)
            update(connection, dao.getValue("softDelete"), 1L, NOW - 31 * DAY)
            update(connection, dao.getValue("softDelete"), 2L, NOW - 29 * DAY)
            update(connection, dao.getValue("softDelete"), 3L, NOW - 30 * DAY)
            val cutoff = NOW - 30 * DAY
            assertEquals(listOf(1L), ids(connection, dao.getValue("deletedBefore"), cutoff))
            assertEquals(1, update(connection, dao.getValue("purgeDeletedBefore"), cutoff))
            assertEquals(SEED.size.toLong() - 1, scalar(connection, "SELECT COUNT(*) FROM reminder"))
            assertNull(ids(connection, dao.getValue("getById"), 1L).firstOrNull())
        }
    }

    @Test
    fun qaRealTrimKeepsTheNewest200AndLeavesOtherRemindersAlone() {
        withMigrated(seed = false) { connection, _ ->
            val events = sql(EVENT_DAO_FILE)
            connection.prepareStatement(
                "INSERT INTO reminder_event (reminderUuid, at, type, actor, sourceId, detail) VALUES (?, ?, 'NAGGED', 'SYSTEM', NULL, NULL)",
            ).use { insert ->
                for (index in 0 until 205) {
                    insert.setString(1, "u-1")
                    // Two events share each timestamp, as two fires in the same millisecond can.
                    insert.setLong(2, 1_000L + index / 2)
                    insert.executeUpdate()
                }
                for (index in 0 until 3) {
                    insert.setString(1, "u-2")
                    insert.setLong(2, 1L + index)
                    insert.executeUpdate()
                }
            }
            update(connection, events.getValue("trim"), "u-1", 200)
            assertEquals(200L, scalar(connection, "SELECT COUNT(*) FROM reminder_event WHERE reminderUuid = 'u-1'"))
            assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM reminder_event WHERE reminderUuid = 'u-2'"))
            // The five oldest inserts (ids 1 to 5) went; id 6 onwards stayed.
            assertEquals(6L, scalar(connection, "SELECT MIN(id) FROM reminder_event WHERE reminderUuid = 'u-1'"))
            val newest = ids(connection, events.getValue("forReminder"), "u-1", 1)
            assertEquals(listOf(205L), newest)
        }
    }

    @Test
    fun qaMigratedReminderTableHasTheIndicesRoomExpects() {
        // Room validates indices as well as columns after MIGRATION_4_5. 5.json declares no
        // index on reminder, so the migrated table must have none either.
        val schema = projectFile("schemas/com.evansappstudio.doapp.data.AppDatabase/5.json").readText()
        val reminderEntity = schema.substringAfter("\"tableName\": \"reminder\"").substringBefore("\"tableName\": \"reminder_event\"")
        assertFalse("5.json declares an index on reminder", reminderEntity.contains("\"indices\""))
        withMigrated(seed = false) { connection, _ ->
            val names = ArrayList<String>()
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reminder' AND sql IS NOT NULL",
                ).use { rows -> while (rows.next()) names += rows.getString(1) }
            }
            assertEquals(emptyList<String>(), names)
        }
    }

    // --- helpers ---

    private data class Seed(
        val title: String,
        val dueAt: Long,
        val nag: Int?,
        val isDone: Int,
        val doneAt: Long?,
        val createdAt: Long,
        val interval: Int?,
        val unit: String?,
        val from: String?,
        val weekDays: String?,
        val monthOn: String?,
        val pattern: Long?,
        val alert: String,
        val uuid: String,
    )

    private fun withMigrated(seed: Boolean, block: (Connection, List<List<Any?>>) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(RepeatSchema.V1_CREATE)
                RepeatSchema.V1_TO_V2.forEach { statement.execute(it) }
                statement.execute(AlertSchema.V2_TO_V3)
                statement.execute(UuidSchema.ADD_COLUMN)
            }
            if (seed) insertSeed(connection)
            val before = readRows(connection, v4Columns)
            connection.createStatement().use { statement -> MetaSchema.V4_TO_V5.forEach { statement.execute(it) } }
            block(connection, before)
        }
    }

    private fun insertSeed(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO reminder (title, dueAt, nagMinutes, isDone, doneAt, createdAt, repeatInterval, repeatUnit, " +
                "repeatFrom, repeatWeekDays, repeatMonthOn, patternDueAt, alertLevel, uuid) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            for (row in SEED) {
                val values = listOf(
                    row.title, row.dueAt, row.nag, row.isDone, row.doneAt, row.createdAt, row.interval, row.unit,
                    row.from, row.weekDays, row.monthOn, row.pattern, row.alert, row.uuid,
                )
                values.forEachIndexed { index, value -> insert.setObject(index + 1, value) }
                insert.executeUpdate()
            }
        }
    }

    private fun readRows(connection: Connection, columns: List<String>): List<List<Any?>> {
        val out = ArrayList<List<Any?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT ${columns.joinToString(", ")} FROM reminder ORDER BY id").use { rows ->
                while (rows.next()) out += columns.map { rows.getObject(it) }
            }
        }
        return out
    }

    private fun update(connection: Connection, query: String, vararg args: Any?): Int =
        connection.prepareStatement(bindable(query)).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun ids(connection: Connection, query: String, vararg args: Any?): List<Long> =
        connection.prepareStatement(bindable(query)).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val out = ArrayList<Long>()
                while (rows.next()) out += rows.getLong("id")
                out
            }
        }

    private fun scalar(connection: Connection, query: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                assertTrue(rows.next())
                rows.getLong(1)
            }
        }

    /**
     * Room's `:name` parameters become `?N`, where N is the name's position in the Kotlin
     * function's parameter list, so callers pass arguments in the DAO function's order and
     * a repeated name binds once. A query with no recorded signature numbers by first use.
     */
    private fun bindable(query: String): String {
        val names = SIGNATURES[query] ?: emptyList()
        val order = LinkedHashMap<String, Int>()
        return PARAM.replace(query) { match ->
            val name = match.groupValues[1]
            val known = names.indexOf(name)
            val index = if (known >= 0) known + 1 else order.getOrPut(name) { order.size + 1 }
            "?$index"
        }
    }

    /** Every @Query in a DAO source file, by function name, with the string pieces joined. */
    private fun sql(relative: String): Map<String, String> {
        val text = projectFile(relative).readText()
        val out = LinkedHashMap<String, String>()
        var from = 0
        while (true) {
            val at = text.indexOf("@Query(", from)
            if (at < 0) break
            val fn = FUN.find(text, at) ?: break
            val body = text.substring(at, fn.range.first)
            val query = LITERAL.findAll(body).joinToString("") { it.groupValues[1] }
            val close = text.indexOf(')', fn.range.last)
            SIGNATURES[query] = PARAM_DECL.findAll(text.substring(fn.range.last + 1, close)).map { it.groupValues[1] }.toList()
            out[fn.groupValues[1]] = query
            from = fn.range.last
        }
        check(out.isNotEmpty()) { "no @Query found in $relative" }
        return out
    }

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile } ?: error("missing $relative from ${File(".").absolutePath}")
    }

    private companion object {
        const val NOW = 1_764_212_400_000L
        const val DAY = 24L * 60L * 60L * 1000L
        const val DAO_FILE = "src/main/java/com/evansappstudio/doapp/data/ReminderDao.kt"
        const val EVENT_DAO_FILE = "src/main/java/com/evansappstudio/doapp/data/ReminderEventDao.kt"
        val PARAM = Regex(":([A-Za-z_][A-Za-z0-9_]*)")
        val PARAM_DECL = Regex("(\\w+)\\s*:")
        val SIGNATURES = HashMap<String, List<String>>()
        val FUN = Regex("fun\\s+(\\w+)\\s*\\(")
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

        val SEED = listOf(
            Seed("Isabelle book list", NOW + DAY, 5, 0, null, NOW - 40 * DAY, null, null, null, null, null, null, "STANDARD", "11111111-1111-4111-8111-111111111111"),
            Seed("Bins out", NOW + 2 * DAY, 15, 0, null, NOW - 30 * DAY, 1, "WEEK", "DUE_DATE", null, null, NOW - 5 * DAY, "STANDARD", "22222222-2222-4222-8222-222222222222"),
            Seed("Water plants", NOW + 3 * DAY, null, 0, null, NOW - 20 * DAY, 3, "DAY", "COMPLETION", null, null, null, "STANDARD", "33333333-3333-4333-8333-333333333333"),
            Seed("Pills", NOW + 60_000L, 1, 0, null, NOW - 10 * DAY, 1, "WEEK", "DUE_DATE", "MON,WED,FRI", null, NOW - 2 * DAY, "CRITICAL", "44444444-4444-4444-8444-444444444444"),
            Seed("Rego paid", NOW - DAY, 5, 1, NOW - DAY + 3_600_000L, NOW - 90 * DAY, null, null, null, null, null, null, "PROMINENT", "55555555-5555-4555-8555-555555555555"),
            Seed("Café ☕ – rent", NOW + 10 * DAY, 30, 0, null, NOW - 5 * DAY, 1, "MONTH", "DUE_DATE", null, "DAY_OF_MONTH", NOW + 10 * DAY, "STANDARD", "66666666-6666-4666-8666-666666666666"),
        )
    }
}
