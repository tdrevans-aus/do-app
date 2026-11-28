package com.evansappstudio.doapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The core logic must not import Android or AndroidX, so it can later be shared
 * with an iPhone app. Adding a file to the core means adding it to [CORE_FILES].
 */
class CorePurityTest {
    // AT-15
    @Test
    fun at15CoreFilesImportNoAndroidCode() {
        val offences = CORE_FILES.flatMap { path ->
            val file = sourceFile(path)
            androidImports(file.readLines()).map { "$path: $it" }
        }
        assertEquals(emptyList<String>(), offences)
    }

    // AT-15 (the command types carry no Room entity, DAO or Scheduler, so they are portable)
    @Test
    fun at15CommandTypesImportNothingFromTheAndroidSide() {
        val imports = sourceFile("alarm/CommandTypes.kt").readLines().map { it.trim() }.filter { it.startsWith("import ") }
        assertEquals(emptyList<String>(), imports)
    }

    // AT-15 (the check fails when an Android import is added, for example to RepeatMath.kt)
    @Test
    fun at15TheCheckCatchesAnAndroidImport() {
        val lines = sourceFile("alarm/RepeatMath.kt").readLines().toMutableList()
        lines.add(2, "import android.util.Log")
        assertEquals(listOf("import android.util.Log"), androidImports(lines))
        assertEquals(listOf("import androidx.room.Entity"), androidImports(listOf("  import androidx.room.Entity")))
        assertTrue(androidImports(listOf("// import android.util.Log is not allowed")).isEmpty())
    }

    // AT-10 (Spec 11: core purity covers the new pure files)
    @Test
    fun at10Spec11PureFilesAreInTheCoreAndImportNoAndroidCode() {
        assertTrue(CORE_FILES.containsAll(SPEC11_CORE))
        SPEC11_CORE.forEach { path ->
            val file = sourceFile(path)
            assertEquals(path, emptyList<String>(), androidImports(file.readLines()))
            // Their app imports are themselves core files.
            val appImports = file.readLines().map { it.trim() }
                .filter { it.startsWith("import com.evansappstudio.doapp.") }
            appImports.forEach { line ->
                val name = line.removePrefix("import com.evansappstudio.doapp.")
                val pkg = name.substringBeforeLast('.')
                val coreOwner = CORE_FILES.any { core ->
                    core.substringBeforeLast('/').replace('/', '.') == pkg &&
                        sourceFile(core).readText().contains(name.substringAfterLast('.'))
                }
                assertTrue("$path: $line is not from a core file", coreOwner)
            }
        }
    }

    private fun androidImports(lines: List<String>): List<String> = lines
        .map { it.trim() }
        .filter { it.startsWith("import android.") || it.startsWith("import androidx.") }

    private fun sourceFile(path: String): File {
        val relative = "src/main/java/com/evansappstudio/doapp/$path"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile } ?: error("missing core file $path from ${File(".").absolutePath}")
    }

    companion object {
        /** The pure core. Adding a file to the core means adding it here. */
        val CORE_FILES: List<String> = listOf(
            "alarm/RepeatMath.kt",
            "alarm/DateParser.kt",
            "alarm/NagSchedule.kt",
            "alarm/QuietHours.kt",
            "alarm/SnoozeDue.kt",
            "alarm/RepeatText.kt",
            "alarm/QuickTimes.kt",
            "data/RepeatRule.kt",
            "alarm/CommandTypes.kt",
            // Spec 11: the catalogue model and parser, regions, picks and suggestions,
            // and the small JSON codec the parser and suggestion state use.
            "catalogue/Catalogue.kt",
            "catalogue/Region.kt",
            "catalogue/Picks.kt",
            "catalogue/SuggestionPolicy.kt",
            "backup/BackupJson.kt",
        )

        /** Spec 11 files that must stay in [CORE_FILES]. */
        val SPEC11_CORE: List<String> = listOf(
            "catalogue/Catalogue.kt",
            "catalogue/Region.kt",
            "catalogue/Picks.kt",
            "catalogue/SuggestionPolicy.kt",
            "backup/BackupJson.kt",
        )
    }
}
