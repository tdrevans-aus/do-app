package com.evansappstudio.doapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 11 AT-11 is the CI run itself: `assembleDebug testDebugUnitTest lintDebug`,
 * and `assembleDebug -PexactAlarmMode=SCHEDULE`. This guards what those runs rely on.
 */
class Spec11BuildTest {
    // AT-11 (both exact-alarm variants stay buildable, and the catalogue ships in every variant)
    @Test
    fun at11BothExactAlarmVariantsAndTheCatalogueAssetAreInTheBuild() {
        val gradle = projectFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("exactAlarmMode == \"USE\" || exactAlarmMode == \"SCHEDULE\""))
        // The asset is in main, so debug, release, USE and SCHEDULE all package it.
        assertTrue(projectFile("src/main/assets/catalogue/sysd.json").isFile)
        assertFalse(File(projectFile("src"), "debug/assets/catalogue/sysd.json").exists())
        // No new dependency for Spec 11 (no downloadable fonts, no JSON library in main).
        assertFalse(gradle.contains("ui-text-google-fonts"))
        assertEquals(1, Regex("org.json:json").findAll(gradle).count())
        assertTrue(gradle.contains("testImplementation(\"org.json:json"))
    }

    // AT-11 (the debug hooks are in the debug manifest only)
    @Test
    fun at11DebugHooksAreDeclaredInTheDebugManifestOnly() {
        val debug = projectFile("src/debug/AndroidManifest.xml").readText()
        val main = projectFile("src/main/AndroidManifest.xml").readText()
        listOf("CATALOGUE_DUMP", "SET_REGION", "SUGGESTION_DUMP", "SUGGESTION_RESET", "SET_PROFILE", "SET_TODAY").forEach { hook ->
            assertTrue(hook, debug.contains("com.evansappstudio.doapp.debug.$hook\""))
            assertFalse(hook, main.contains(hook))
        }
        val ci = projectFile("../.github/workflows/ci.yml").readText()
        assertTrue(ci.contains("assembleDebug testDebugUnitTest lintDebug"))
    }

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() } ?: error("missing $relative from ${File(".").absolutePath}")
    }
}
