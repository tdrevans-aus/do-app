package com.evansappstudio.doapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class UuidMigrationTest {
    @Test
    fun version3RowsGainUniqueUuidsAndKeepTheirOtherColumns() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(RepeatSchema.V1_CREATE)
                RepeatSchema.V1_TO_V2.forEach { statement.execute(it) }
                statement.execute(AlertSchema.V2_TO_V3)
            }
            connection.prepareStatement(
                "INSERT INTO reminder (title, dueAt, nagMinutes, isDone, doneAt, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, "Bins")
                insert.setLong(2, 1_700_000_000_000L)
                insert.setInt(3, 5)
                insert.setInt(4, 0)
                insert.setNull(5, java.sql.Types.INTEGER)
                insert.setLong(6, 1_699_000_000_000L)
                insert.executeUpdate()
                insert.setString(1, "Milk")
                insert.setLong(2, 1_700_000_060_000L)
                insert.executeUpdate()
            }
            connection.createStatement().use { it.execute(UuidSchema.ADD_COLUMN) }
            val ids = ArrayList<Long>()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, uuid FROM reminder ORDER BY id").use { rows ->
                    while (rows.next()) {
                        assertEquals("", rows.getString("uuid"))
                        ids += rows.getLong("id")
                    }
                }
            }
            assertEquals(listOf(1L, 2L), ids)
            connection.prepareStatement("UPDATE reminder SET uuid = ? WHERE id = ? AND (uuid IS NULL OR uuid = '')").use { update ->
                for (id in ids) {
                    update.setString(1, UUID.randomUUID().toString())
                    update.setLong(2, id)
                    update.executeUpdate()
                }
            }
            val uuids = ArrayList<String>()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title, uuid, alertLevel FROM reminder ORDER BY id").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Bins", rows.getString("title"))
                    assertEquals("STANDARD", rows.getString("alertLevel"))
                    uuids += rows.getString("uuid")
                    assertTrue(rows.next())
                    assertEquals("Milk", rows.getString("title"))
                    uuids += rows.getString("uuid")
                }
            }
            assertEquals(2, uuids.distinct().size)
            uuids.forEach { uuid ->
                assertTrue(uuid.isNotBlank())
                assertNotEquals("", uuid)
            }
        }
    }
}
