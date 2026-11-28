package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.ApprovalDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13 AT-12: blank release config hides every cloud feature and changes nothing else. */
class CloudOffTest {
    // AT-12 (any blank value is off; the MCP address follows the config)
    @Test
    fun at12BlankValuesMeanOff() {
        assertFalse(CloudConfig.OFF.enabled)
        assertFalse(CloudConfig("", "key", "1:2:android:3").enabled)
        assertFalse(CloudConfig("p", " ", "1:2:android:3").enabled)
        assertFalse(CloudConfig("p", "key", "").enabled)
        assertNull(CloudConfig.OFF.mcpUrl())
        assertFalse(CloudConfig.OFF.usesEmulator)
        val real = CloudConfig("do-cloud", "key", "1:123456789:android:abc")
        assertTrue(real.enabled)
        assertEquals("https://do-cloud.web.app/mcp", real.mcpUrl())
        assertEquals("https://do.example.com/mcp", real.copy(siteUrl = "https://do.example.com/").mcpUrl())
        assertEquals("http://10.0.2.2:5002/mcp", real.copy(emulatorHost = "10.0.2.2").mcpUrl())
        assertEquals("123456789", real.senderId)
        assertNull(real.copy(appId = "nonsense").senderId)
    }

    // AT-12 (with the cloud off, nothing builds the port, signs in, enqueues work or writes state)
    @Test
    fun at12CloudOffDoesNothing() = runBlocking {
        val cx = CloudFixture(config = CloudConfig.OFF)
        val cloud = cx.cloud
        assertFalse(cloud.enabled)
        assertFalse(cloud.ui.value.available)
        assertNull(cloud.ui.value.mcpUrl)
        cloud.onAppStart()
        cloud.onNetworkAvailable()
        cloud.onPush(mapOf("kind" to "inbox"))
        cloud.onRemindersChanged()
        cloud.onNewToken("token")
        assertEquals(PullRun(0, 0, 0, 0, 0), cloud.runPull())
        assertNull(cloud.runMirror())
        assertNull(cloud.connectAssistant())
        assertNull(cloud.refreshAgents())
        assertFalse(cloud.disconnect(AGENT_A))
        cloud.setAssistantsEnabled(false)
        cloud.setAskBeforeRingThrough(false)
        assertEquals(VerifyOutcome.OFF, cloud.onPurchase("do_unlimited", "token", justBought = true, packageName = "com.evansappstudio.doapp"))
        assertEquals(0, cx.portBuilt)
        assertTrue(cx.port.calls.isEmpty())
        assertTrue(cx.jobs.pulls.isEmpty())
        assertEquals(0, cx.jobs.mirrors)
        assertTrue(cx.prefs.all.isEmpty())
        assertEquals(0, cx.entitlement.changes)
        assertEquals("enabled=false", cloud.dump())
        // The approval card still goes through ReminderCommands (it is a local action); with nothing held it does nothing.
        assertFalse(cloud.resolveApproval(1L, ApprovalDecision.ALLOW))
    }

    // AT-12 (release defaults are blank; the container builds no cloud and the screen entry is hidden when off)
    @Test
    fun at12ReleaseIsBlankAndTheWiringChecksIt() {
        val gradle = projectFile("build.gradle.kts").readText()
        for (name in listOf("cloudProjectId", "cloudApiKey", "cloudAppId", "cloudSiteUrl")) {
            assertTrue(name, gradle.contains("cloudProp(\"$name\", \"\")"))
        }
        assertTrue(gradle.contains("\"CLOUD_EMULATOR_HOST\" to \"\",\n)"))
        assertTrue(gradle.contains("resValue(\"bool\", \"cloud_enabled\", \"\${cloudOn(cloudRelease)}\")"))
        assertFalse(gradle.contains("com.google.gms.google-services"))
        assertFalse(projectFile("build.gradle.kts").absoluteFile.parentFile.resolve("google-services.json").exists())
        val container = projectFile("src/main/java/com/evansappstudio/doapp/AppContainer.kt").readText()
        assertTrue(container.contains("if (!cloudConfig.enabled) return@lazy null"))
        assertTrue(container.contains("if (!cloudConfig.enabled || !UserUnlock.isUnlocked(appContext)) return"))
        val ui = projectFile("src/main/java/com/evansappstudio/doapp/ui/RemindersUi.kt").readText()
        assertTrue(ui.contains("onOpenAssistants = if (container.cloudConfig.enabled) {"))
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".cloud.DoMessagingService\""))
        assertEquals(3, Regex("android:enabled=\"@bool/cloud_enabled\"").findAll(manifest).count())
        assertTrue(manifest.contains("com.google.firebase.provider.FirebaseInitProvider"))
    }

    // AT-12 (Firebase is only touched behind CloudPort: FirebaseCloud and the FCM service file)
    @Test
    fun at12FirebaseStaysBehindThePort() {
        val roots = listOf("src/main/java", "src/debug/java", "src/release/java").map(::projectFile)
        val users = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("import com.google.firebase") }.map { it.name }.toList()
        }.toSet()
        assertEquals(setOf("FirebaseCloud.kt", "CloudWork.kt"), users)
        val work = projectFile("src/main/java/com/evansappstudio/doapp/cloud/CloudWork.kt").readText()
        assertFalse(work.contains("FirebaseApp"))
        assertFalse(work.contains("setForeground"))
    }
}
