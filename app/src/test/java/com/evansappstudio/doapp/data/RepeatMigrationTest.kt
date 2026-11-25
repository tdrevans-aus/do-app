package com.evansappstudio.doapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

class RepeatMigrationTest {
    @Test
    fun version1OneShotRowStaysRepeatOff() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.execute(RepeatSchema.V1_CREATE) }
            connection.prepareStatement(
                "INSERT INTO reminder (title, dueAt, nagMinutes, isDone, doneAt, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, "Bins")
                insert.setLong(2, 1_700_000_000_000L)
                insert.setInt(3, 5)
                insert.setInt(4, 0)
                insert.setNull(5, java.sql.Types.INTEGER)
                insert.setLong(6, 1_699_000_000_000L)
                insert.executeUpdate()
            }
            connection.createStatement().use { statement ->
                RepeatSchema.V1_TO_V2.forEach { statement.execute(it) }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM reminder WHERE id = 1").use { row ->
                    assertTrueRow(row.next())
                    assertEquals("Bins", row.getString("title"))
                    assertEquals(1_700_000_000_000L, row.getLong("dueAt"))
                    assertEquals(5, row.getInt("nagMinutes"))
                    assertEquals(0, row.getInt("isDone"))
                    row.getLong("doneAt")
                    assertTrueRow(row.wasNull())
                    assertEquals(1_699_000_000_000L, row.getLong("createdAt"))
                    row.getInt("repeatInterval")
                    assertTrueRow(row.wasNull())
                    assertNull(row.getString("repeatUnit"))
                    assertNull(row.getString("repeatFrom"))
                    assertNull(row.getString("repeatWeekDays"))
                    assertNull(row.getString("repeatMonthOn"))
                    row.getLong("patternDueAt")
                    assertTrueRow(row.wasNull())
                }
            }
        }
        assertEquals(6, RepeatSchema.V1_TO_V2.size)
        assertFalse(RepeatSchema.V1_TO_V2.any { it.contains("NOT NULL") })
    }

    private fun assertTrueRow(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
