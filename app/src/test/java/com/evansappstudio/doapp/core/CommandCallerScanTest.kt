package com.evansappstudio.doapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reminder changes go through ReminderCommands. Only the core (Scheduler and
 * ReminderCommands) may call the Scheduler mutators; every other file in main,
 * debug and release, including the receivers in alarm/, must use the commands.
 */
class CommandCallerScanTest {
    // AT-14
    @Test
    fun at14NoFileOutsideTheCoreCallsASchedulerMutator() {
        val hits = sourceRoots().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in ALLOWED }
                .flatMap { file -> directCalls(file.readText()).map { "${file.name}: $it" } }
                .toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    // AT-14 (the scan fails if a caller is put back)
    @Test
    fun at14TheScanCatchesADirectCall() {
        assertEquals(listOf("scheduler.markDone("), directCalls("app.container.scheduler.markDone(id)"))
        assertEquals(listOf("scheduler().add("), directCalls("val id = scheduler().add(\n title = TEST_TITLE,"))
        assertEquals(listOf("scheduler.snoozeShowing("), directCalls("container.scheduler.snoozeShowing(0, option)"))
        assertEquals(listOf("scheduler.delete("), directCalls("container.scheduler\n    .delete(id)"))
        assertTrue(directCalls("container.scheduler.rescheduleAll()").isEmpty())
        assertTrue(directCalls("container.commands.markDone(origin, id)").isEmpty())
        assertTrue(directCalls("container.scheduler.onFire(id, actual, scheduled)").isEmpty())
    }

    private fun directCalls(text: String): List<String> =
        MUTATOR_CALL.findAll(text).map { it.value.replace(Regex("\\s+"), "") }.toList()

    private fun sourceRoots(): List<File> {
        val names = listOf("src/main/java", "src/debug/java", "src/release/java")
        val roots = names.mapNotNull { name -> listOf(File(name), File("app/$name")).firstOrNull { it.isDirectory } }
        check(roots.size == names.size) { "missing source roots from ${File(".").absolutePath}" }
        return roots
    }

    companion object {
        private val ALLOWED = setOf("Scheduler.kt", "ReminderCommands.kt")
        private val MUTATORS = listOf(
            "add", "update", "markDone", "snooze", "snoozeShowing", "stopAlert", "setAlertLevel",
            "setRepeat", "shiftDue", "setTitle", "delete", "restore", "replaceAll", "mergeAll",
        )
        private val MUTATOR_CALL = Regex(
            "scheduler(?:\\(\\))?\\s*\\.\\s*(?:${MUTATORS.joinToString("|")})\\s*\\(",
        )
    }
}
