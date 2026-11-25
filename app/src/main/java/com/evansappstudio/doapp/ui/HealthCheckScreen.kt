package com.evansappstudio.doapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.HealthFix
import com.evansappstudio.doapp.alarm.HealthItem
import com.evansappstudio.doapp.alarm.HealthReport
import com.evansappstudio.doapp.alarm.HealthStatus

private val HealthWarningYellow = Color(0xFFF5C400)

@Composable
fun HealthMenuButton(
    warning: Boolean,
    onHealth: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(if (warning) R.string.health_menu_warning else R.string.health_menu)
    Box {
        IconButton(onClick = { open = true }) {
            Box(
                modifier = Modifier.semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                MenuMark(color = MaterialTheme.colorScheme.onSurface)
                if (warning) {
                    WarningMark(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.health_check)) },
                onClick = {
                    open = false
                    onHealth()
                },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.tools),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export)) },
                onClick = {
                    open = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.backup)) },
                onClick = {
                    open = false
                    onBackup()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    report: HealthReport,
    onBack: () -> Unit,
    onFix: (HealthFix) -> Unit,
    onTest: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_check)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.health_lead),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(report.items, key = { it.id }) { row ->
                HealthRow(row = row, onFix = onFix)
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.health_test_button))
                    }
                    if (report.testMessage.isNotEmpty()) {
                        Text(
                            text = report.testMessage,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthRow(row: HealthItem, onFix: (HealthFix) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = statusLabel(row.status),
            color = statusColor(row.status),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = row.title, style = MaterialTheme.typography.titleMedium)
        Text(text = row.explanation, style = MaterialTheme.typography.bodyMedium)
        if (row.fixes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.fixes.forEach { fix ->
                    TextButton(onClick = { onFix(fix) }) { Text(fix.label) }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: HealthStatus): String = when (status) {
    HealthStatus.OK -> stringResource(R.string.health_status_ok)
    HealthStatus.WARN -> stringResource(R.string.health_status_warning)
    HealthStatus.BLOCKED -> stringResource(R.string.health_status_blocked)
    HealthStatus.CHECK -> stringResource(R.string.health_status_check)
}

@Composable
private fun statusColor(status: HealthStatus): Color = when (status) {
    HealthStatus.OK -> MaterialTheme.colorScheme.primary
    HealthStatus.WARN -> Color(0xFF8A6200)
    HealthStatus.BLOCKED -> MaterialTheme.colorScheme.error
    HealthStatus.CHECK -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun MenuMark(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val gap = size.height / 4f
        for (index in 0..2) {
            val y = gap + index * gap
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun WarningMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width / 2f, 1.dp.toPx())
            lineTo(size.width - 1.dp.toPx(), size.height - 1.dp.toPx())
            lineTo(1.dp.toPx(), size.height - 1.dp.toPx())
            close()
        }
        drawPath(path, HealthWarningYellow)
        drawPath(path, Color(0xFF5C4300), style = Stroke(width = 1.dp.toPx()))
    }
}
