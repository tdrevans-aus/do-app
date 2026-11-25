package com.evansappstudio.doapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

class AlertMigrationTest {
    @Test
    fun version2RowBecomesStandardAndKeepsRepeatColumns() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(RepeatSchema.V1_CREATE)
                RepeatSchema.V1_TO_V2.forEach { statement.execute(it) }
            }
            connection.prepareStatement(
                "INSERT INTO reminder (title, dueAt, nagMinutes, isDone, doneAt, createdAt, " +
                    "repeatInterval, repeatUnit, repeatFrom, repeatWeekDays, repeatMonthOn, patternDueAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, "Bins")
                insert.setLong(2, 1_700_000_000_000L)
                insert.setInt(3, 5)
                insert.setInt(4, 0)
                insert.setNull(5, java.sql.Types.INTEGER)
                insert.setLong(6, 1_699_000_000_000L)
                insert.setInt(7, 1)
                insert.setString(8, "WEEK")
                insert.setString(9, "DUE_DATE")
                insert.setString(10, "MON,TUE,WED,THU,FRI")
                insert.setNull(11, java.sql.Types.VARCHAR)
                insert.setLong(12, 1_700_000_000_000L)
                insert.executeUpdate()
            }
            connection.createStatement().use { it.execute(AlertSchema.V2_TO_V3) }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM reminder WHERE id = 1").use { row ->
                    check(row.next())
                    assertEquals("Bins", row.getString("title"))
                    assertEquals(1_700_000_000_000L, row.getLong("dueAt"))
                    assertEquals(5, row.getInt("nagMinutes"))
                    assertEquals(0, row.getInt("isDone"))
                    assertEquals(1, row.getInt("repeatInterval"))
                    assertEquals("WEEK", row.getString("repeatUnit"))
                    assertEquals("DUE_DATE", row.getString("repeatFrom"))
                    assertEquals("MON,TUE,WED,THU,FRI", row.getString("repeatWeekDays"))
                    assertNull(row.getString("repeatMonthOn"))
                    assertEquals(1_700_000_000_000L, row.getLong("patternDueAt"))
                    assertEquals("STANDARD", row.getString("alertLevel"))
                }
            }
        }
    }
}
