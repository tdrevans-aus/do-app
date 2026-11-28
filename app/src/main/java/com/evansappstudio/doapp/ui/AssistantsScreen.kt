package com.evansappstudio.doapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.cloud.AssistantsUi
import com.evansappstudio.doapp.cloud.CloudAgent
import com.evansappstudio.doapp.data.Reminder
import kotlinx.coroutines.delay

/** The Assistant source chip colours from the design pass. */
private val AssistantInk = Color(0xFF1E4FA0)
private val AssistantGround = Color(0xFFE8EFFB)

/**
 * Spec 13, design screen 8: connect an assistant with a pairing code, the
 * connected list with Disconnect, the approval card for held Loud and Alarm
 * requests, "Ask me before it can ring through" and the assistants switch.
 * Shown only when the build has Do Cloud values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantsScreen(
    ui: AssistantsUi,
    held: List<Reminder>,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: (String) -> Unit,
    onAssistantsEnabled: (Boolean) -> Unit,
    onAskBefore: (Boolean) -> Unit,
    onDecision: (Long, ApprovalDecision) -> Unit,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    var confirm by remember { mutableStateOf<CloudAgent?>(null) }
    val names = ui.agents.associate { it.agentId to it.clientName }
    DoLookTheme {
        Scaffold(
            containerColor = DoLook.Ground,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.assistants_title), modifier = Modifier.semantics { heading() }) },
                    navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DoLook.Ground),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.assistants_intro), style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary)
                        if (ui.busy) {
                            val working = stringResource(R.string.assistants_working)
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = working },
                            )
                        }
                        if (ui.failed) {
                            Text(
                                stringResource(R.string.assistants_failed),
                                color = DoLook.Alarm,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
                items(held, key = { "held-${it.id}" }) { reminder ->
                    ApprovalCard(reminder, names[reminder.sourceId], onDecision)
                }
                item {
                    Card {
                        SwitchRow(
                            title = stringResource(R.string.assistants_switch),
                            subtitle = stringResource(R.string.assistants_switch_subtitle),
                            checked = ui.assistantsEnabled,
                            onChange = onAssistantsEnabled,
                        )
                        HorizontalDivider(color = DoLook.Line)
                        SwitchRow(
                            title = stringResource(R.string.assistants_ask),
                            subtitle = stringResource(R.string.assistants_ask_subtitle),
                            checked = ui.askBeforeRingThrough,
                            onChange = onAskBefore,
                        )
                    }
                }
                item { PairingCard(ui, onConnect, clock) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.assistants_connected),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (ui.hasIdentity) {
                            TextButton(onClick = onRefresh, enabled = !ui.busy, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                                Text(stringResource(R.string.assistants_refresh))
                            }
                        }
                    }
                }
                if (ui.agents.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.assistants_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = DoLook.Secondary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(ui.agents, key = { it.agentId }) { agent ->
                    AgentRow(agent, clock(), onDisconnect = { confirm = agent })
                }
                item {
                    Card {
                        Text(stringResource(R.string.assistants_rules_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.assistants_rules_body), style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary)
                    }
                }
            }
        }
        val pending = confirm
        if (pending != null) {
            val name = displayName(pending.clientName)
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(stringResource(R.string.assistants_disconnect_confirm_title, name)) },
                text = { Text(stringResource(R.string.assistants_disconnect_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        onDisconnect(pending.agentId)
                    }) { Text(stringResource(R.string.assistants_disconnect)) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = DoLook.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun PairingCard(ui: AssistantsUi, onConnect: () -> Unit, clock: () -> Long) {
    val context = LocalContext.current
    val pairing = ui.pairing
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(pairing) {
        while (pairing != null && !pairing.expired(now)) {
            now = clock()
            delay(1_000)
        }
        now = clock()
    }
    Card {
        Text(stringResource(R.string.assistants_connect), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (pairing == null) {
            Button(
                onClick = onConnect,
                enabled = !ui.busy && ui.assistantsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = DoLook.Ink),
                modifier = Modifier.heightIn(min = DoLook.MinTouch),
            ) { Text(stringResource(R.string.assistants_connect)) }
            return@Card
        }
        Text(stringResource(R.string.assistants_connect_steps), style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary)
        Text(stringResource(R.string.assistants_code_label), style = MaterialTheme.typography.labelMedium, color = DoLook.Secondary)
        val spoken = pairing.code.toCharArray().joinToString(" ")
        val description = stringResource(R.string.assistants_code_description, spoken)
        SelectionContainer {
            Text(
                pairing.display(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        if (pairing.expired(now)) {
            Text(stringResource(R.string.assistants_code_expired), color = DoLook.Alarm, style = MaterialTheme.typography.bodyMedium)
        } else {
            val left = pairing.secondsLeft(now)
            Text(
                stringResource(R.string.assistants_code_expires, (left / 60).toInt(), (left % 60).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = DoLook.Secondary,
            )
        }
        val url = ui.mcpUrl
        if (url != null) {
            Text(stringResource(R.string.assistants_address_label), style = MaterialTheme.typography.labelMedium, color = DoLook.Secondary)
            SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            val copied = stringResource(R.string.assistants_copied)
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(url, url))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.heightIn(min = DoLook.MinTouch),
            ) { Text(stringResource(R.string.assistants_copy_address)) }
        }
        TextButton(onClick = onConnect, enabled = !ui.busy, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
            Text(stringResource(R.string.assistants_new_code))
        }
    }
}

@Composable
private fun AgentRow(agent: CloudAgent, now: Long, onDisconnect: () -> Unit) {
    val name = displayName(agent.clientName)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Tag(name, AssistantInk, AssistantGround)
                val used = agent.lastUsedAt
                Text(
                    if (used == null) {
                        stringResource(R.string.assistants_never_used)
                    } else {
                        stringResource(
                            R.string.assistants_last_used,
                            DateUtils.getRelativeTimeSpanString(used, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DoLook.Secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (agent.revoked) {
                    Text(stringResource(R.string.assistants_revoked), style = MaterialTheme.typography.bodySmall, color = DoLook.Alarm)
                }
            }
            val description = stringResource(R.string.assistants_disconnect_description, name)
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier.heightIn(min = DoLook.MinTouch).semantics { contentDescription = description },
            ) { Text(stringResource(R.string.assistants_disconnect)) }
        }
    }
}

@Composable
private fun ApprovalCard(reminder: Reminder, agentName: String?, onDecision: (Long, ApprovalDecision) -> Unit) {
    val level = when (AlertLevel.fromName(reminder.requestedAlertLevel)) {
        AlertLevel.PROMINENT -> stringResource(R.string.assistants_level_alarm)
        else -> stringResource(R.string.assistants_level_loud)
    }
    Surface(
        color = AssistantGround,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.approval_title, displayName(agentName), level),
                style = MaterialTheme.typography.titleSmall,
                color = AssistantInk,
                modifier = Modifier.semantics { heading() },
            )
            Text(reminder.title, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.approval_body), style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDecision(reminder.id, ApprovalDecision.ALLOW) },
                    colors = ButtonDefaults.buttonColors(containerColor = DoLook.Ink),
                    modifier = Modifier.heightIn(min = DoLook.MinTouch),
                ) { Text(stringResource(R.string.approval_allow)) }
                OutlinedButton(
                    onClick = { onDecision(reminder.id, ApprovalDecision.AS_NORMAL) },
                    modifier = Modifier.heightIn(min = DoLook.MinTouch),
                ) { Text(stringResource(R.string.approval_as_normal)) }
                val declineDescription = stringResource(R.string.approval_decline_description, reminder.title)
                TextButton(
                    onClick = { onDecision(reminder.id, ApprovalDecision.DECLINE) },
                    modifier = Modifier.heightIn(min = DoLook.MinTouch).semantics { contentDescription = declineDescription },
                ) { Text(stringResource(R.string.approval_decline)) }
            }
        }
    }
}

/** clientName is untrusted text from the assistant: plain text only, never blank. */
@Composable
private fun displayName(raw: String?): String {
    val clean = raw?.filter { !it.isISOControl() }?.trim()?.take(60)
    return if (clean.isNullOrEmpty()) stringResource(R.string.assistants_unnamed) else clean
}
