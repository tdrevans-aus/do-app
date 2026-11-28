package com.evansappstudio.doapp.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.cloud.AssistantsUi
import com.evansappstudio.doapp.cloud.CloudAgent
import com.evansappstudio.doapp.cloud.PairingCode
import com.evansappstudio.doapp.data.Reminder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Spec 13: the Assistants screen renders (Robolectric, no emulator) and its controls are reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistantsRenderTest {
    val compose = createComposeRule()

    @get:Rule
    val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            org.robolectric.Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                android.content.ComponentName(app, androidx.activity.ComponentActivity::class.java),
            )
        }
    }).around(compose)

    private val now = 1_764_300_000_000L

    @Test
    fun pairingCodeAddressAndApprovalCard() {
        val decisions = ArrayList<Pair<Long, ApprovalDecision>>()
        val disconnected = ArrayList<String>()
        val held = Reminder(
            id = 7,
            uuid = "u-7",
            title = "Feed the cat",
            dueAt = now + 60_000L,
            nagMinutes = 5,
            createdAt = now,
            source = "AGENT",
            sourceId = "ag_0123456789abcdef",
            approval = "PENDING",
            requestedAlertLevel = "PROMINENT",
        )
        compose.setContent {
            AssistantsScreen(
                ui = AssistantsUi(
                    available = true,
                    hasIdentity = true,
                    agents = listOf(CloudAgent("ag_0123456789abcdef", "Claude", null, now, null, null)),
                    pairing = PairingCode("ABCD2345", now + 125_000L),
                    mcpUrl = "https://do-cloud.web.app/mcp",
                ),
                held = listOf(held),
                onBack = {},
                onConnect = {},
                onRefresh = {},
                onDisconnect = { disconnected += it },
                onAssistantsEnabled = {},
                onAskBefore = {},
                onDecision = { id, decision -> decisions += id to decision },
                clock = { now },
            )
        }
        compose.onNodeWithText("Claude wants this to ring Alarm").assertExists()
        compose.onNodeWithText("Feed the cat").assertExists()
        compose.onNodeWithText("Allow").assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("As Normal").performClick()
        compose.onNodeWithContentDescription("Decline and delete Feed the cat").performClick()
        assertEquals(listOf(7L to ApprovalDecision.ALLOW, 7L to ApprovalDecision.AS_NORMAL, 7L to ApprovalDecision.DECLINE), decisions)
        compose.onNodeWithContentDescription("Ask me before it can ring through").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("ABCD 2345"))
        compose.onNodeWithText("ABCD 2345").assertExists()
        compose.onNodeWithContentDescription("Pairing code A B C D 2 3 4 5").assertExists()
        compose.onNodeWithText("Works once. Expires in 2:05.").assertExists()
        compose.onNodeWithText("https://do-cloud.web.app/mcp").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Disconnect Claude"))
        compose.onNodeWithContentDescription("Disconnect Claude").performClick()
        compose.onNodeWithText("Disconnect Claude?").assertExists()
        compose.onAllNodesWithText("Disconnect").onLast().performClick()
        assertEquals(listOf("ag_0123456789abcdef"), disconnected)
    }

    @Test
    fun theStringsAreAustralianAndHaveNoEmDashes() {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.isFile }
        val cloud = file.readLines().filter { it.contains("name=\"assistants_") || it.contains("name=\"approval_") }
        assert(cloud.size > 20)
        for (line in cloud) {
            assert(!line.contains('—')) { line }
            assert(!line.contains("authoriz") && !line.contains(" color")) { line }
        }
    }
}
