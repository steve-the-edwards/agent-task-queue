package com.block.agenttaskqueue.sidecar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val ACTIVE_INTERVAL_MS = 1000L
private const val IDLE_INTERVAL_MS = 3000L

private val DashboardColors = lightColorScheme(
    primary = Color(0xFF305B78),
    secondary = Color(0xFFB35C33),
    tertiary = Color(0xFF46705C),
    background = Color(0xFFF7F1E8),
    surface = Color(0xFFFFFCF8),
    surfaceVariant = Color(0xFFE9DFCf),
    onBackground = Color(0xFF1F262D),
    onSurface = Color(0xFF1F262D),
    outline = Color(0xFF877F74),
    error = Color(0xFF8D2C2C),
)

fun main(args: Array<String>) = application {
    val dataDir = resolveDataDir(args)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Agent Task Queue Sidecar",
        state = rememberWindowState(width = 1320.dp, height = 900.dp),
    ) {
        MaterialTheme(colorScheme = DashboardColors) {
            QueueDashboard(dataDir = dataDir)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueDashboard(dataDir: Path) {
    val refreshRequests = remember(dataDir) { Channel<Unit>(Channel.CONFLATED) }
    var snapshot by remember(dataDir) {
        mutableStateOf(QueueSnapshot.empty(dataDir, statusMessage = "Loading queue state..."))
    }

    LaunchedEffect(dataDir) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { TaskQueueDatabase.loadSnapshot(dataDir) }
            val interval = if (snapshot.tasks.isNotEmpty()) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
            withTimeoutOrNull(interval) {
                refreshRequests.receive()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Agent Task Queue", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = snapshot.dataDir.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    Text(
                        text = "Updated ${formatRefreshTime(snapshot.refreshedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { refreshRequests.trySend(Unit) }) {
                        Text("Refresh")
                    }
                    Spacer(Modifier.width(16.dp))
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            Color(0xFFF3ECE2),
                        )
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SummaryRow(snapshot)

            snapshot.errorMessage?.let { ErrorBanner(it) }
            snapshot.statusMessage?.let { InfoBanner(it) }

            RunningNowStrip(snapshot.runningTasks)
            QueueTopologySection(snapshot.scopeGroups)

            Text(
                text = "Live view from queue.db. Queue capacities set with --queue-capacity are process-local and not persisted in SQLite.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun SummaryRow(snapshot: QueueSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryCard(
            title = "Running",
            value = snapshot.summary.running.toString(),
            caption = "Active commands",
            accent = Color(0xFFD06A3A),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            title = "Waiting",
            value = snapshot.summary.waiting.toString(),
            caption = "Queued tasks",
            accent = Color(0xFF3D7EA6),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            title = "Exact Queues",
            value = snapshot.queueLanes.size.toString(),
            caption = "Distinct queue_name values",
            accent = Color(0xFF5B8A67),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            title = "Root Scopes",
            value = snapshot.scopeGroups.size.toString(),
            caption = "Top-level queue groups",
            accent = Color(0xFF8B5F8C),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    caption: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(title, color = accent, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RunningNowStrip(tasks: List<QueueTask>) {
    SectionCard(
        title = "Running Now",
        subtitle = "Commands currently holding observed queue slots, with queue name and live elapsed time preserved.",
    ) {
        if (tasks.isEmpty()) {
            Text("No running tasks.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            return@SectionCard
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tasks.forEach { task ->
                RunningTaskSpotlight(task)
            }
        }
    }
}

@Composable
private fun RunningTaskSpotlight(task: QueueTask) {
    val accent = Color(0xFFD06A3A)

    Surface(
        modifier = Modifier.widthIn(min = 320.dp, max = 380.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFFFF5EC),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(task.status, accent)
                Text(
                    task.statusAge(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = task.queueName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = task.displayCommand,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = taskMetaLine(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
            )
        }
    }
}

@Composable
private fun QueueTopologySection(scopeGroups: List<ScopeGroup>) {
    SectionCard(
        title = "Queue Topology",
        subtitle = "Each scope groups exact queues. Every row stays FIFO: observed running work appears first, queued work follows in order.",
    ) {
        if (scopeGroups.isEmpty()) {
            Text("No active queues.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            return@SectionCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TopologyLegend()
            scopeGroups.forEach { scope ->
                ScopeTopologyCard(scope)
            }
        }
    }
}

@Composable
private fun TopologyLegend() {
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFF2E8DB)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendChip(text = "Root scope", background = Color(0xFFE2D2BE), foreground = Color(0xFF6C4F34))
            LegendChip(text = "Exact queue", background = Color(0xFFDCE9DF), foreground = Color(0xFF2F5D41))
            LegendChip(text = "Running", background = Color(0xFFF7D9C7), foreground = Color(0xFF9A4B23))
            LegendChip(text = "Waiting (FIFO)", background = Color(0xFFD7E8F3), foreground = Color(0xFF295F7F))
            Text(
                text = "Scope -> exact queue -> observed task order",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ScopeTopologyCard(scope: ScopeGroup) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F0E4))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LegendChip(text = "Root scope", background = Color(0xFFE2D2BE), foreground = Color(0xFF6C4F34))
                    Text(scope.scopeName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "${scope.lanes.size} exact queues · ${scope.runningCount} running · ${scope.waitingCount} waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                scope.lanes.forEach { lane ->
                    LanePipelineRow(scope.scopeName, lane)
                }
            }
        }
    }
}

@Composable
private fun LanePipelineRow(scopeName: String, lane: QueueLane) {
    val laneLabel = laneDisplayName(scopeName, lane.queueName)

    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFFFFCF8)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.widthIn(min = 220.dp, max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LegendChip(text = "Exact queue", background = Color(0xFFDCE9DF), foreground = Color(0xFF2F5D41))
                Text(laneLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (laneLabel != lane.queueName) {
                    Text(
                        text = lane.queueName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                Text(
                    text = "${lane.runningCount} running · ${lane.waitingCount} waiting · ${lane.tasks.size} task(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF4EBDD))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lane.runningTasks.forEach { task ->
                    RunningPipelineTaskCard(task)
                }
                if (lane.runningTasks.isNotEmpty() && lane.waitingTasks.isNotEmpty()) {
                    QueueTransitionMarker()
                }
                lane.waitingTasks.forEachIndexed { index, task ->
                    WaitingPipelineTaskCard(task, position = index + 1)
                }
            }
        }
    }
}

@Composable
private fun RunningPipelineTaskCard(task: QueueTask) {
    val accent = Color(0xFFD06A3A)

    Surface(
        modifier = Modifier.widthIn(min = 250.dp, max = 320.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF7EF),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(task.status, accent)
                Text(
                    task.statusAge(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Text(
                text = task.displayCommand,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                taskMetaLine(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun QueueTransitionMarker() {
    Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFE7DBCB)) {
        Text(
            text = "then queued",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun WaitingPipelineTaskCard(task: QueueTask, position: Int) {
    val accent = Color(0xFF3D7EA6)

    Surface(
        modifier = Modifier.widthIn(min = 230.dp, max = 300.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF6FBFE),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendChip(
                    text = "Queue #$position",
                    background = accent.copy(alpha = 0.18f),
                    foreground = accent,
                )
                Text(
                    task.statusAge(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }

            Text(
                text = task.displayCommand,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = taskMetaLine(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, accent: Color) {
    LegendChip(
        text = text.uppercase(),
        background = accent.copy(alpha = 0.16f),
        foreground = accent,
    )
}

@Composable
private fun LegendChip(text: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                content()
            },
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Banner(message = message, background = Color(0xFFFBE4E3), foreground = MaterialTheme.colorScheme.error)
}

@Composable
private fun InfoBanner(message: String) {
    Banner(message = message, background = Color(0xFFEAF1F6), foreground = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Banner(message: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = background) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = foreground,
        )
    }
}

private fun laneDisplayName(scopeName: String, queueName: String): String {
    val prefix = "$scopeName/"
    return when {
        queueName == scopeName -> "root lane"
        queueName.startsWith(prefix) -> queueName.removePrefix(prefix)
        else -> queueName
    }
}

private fun taskMetaLine(task: QueueTask): String {
    return buildString {
        append("#${task.id}")
        task.pid?.let {
            append(" · server pid $it")
        }
        task.childPid?.let {
            append(" · child pid $it")
        }
    }
}

private fun resolveDataDir(args: Array<String>): Path {
    if (args.any { it == "-h" || it == "--help" }) {
        println("Usage: ./gradlew run --args=\"[--data-dir PATH]\"")
        exitProcess(0)
    }

    var configuredPath: String? = null
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg.startsWith("--data-dir=") -> configuredPath = arg.substringAfter("=")
            arg == "--data-dir" && index + 1 < args.size -> {
                configuredPath = args[index + 1]
                index += 1
            }
        }
        index += 1
    }

    val fallback = System.getenv("TASK_QUEUE_DATA_DIR")?.takeIf { it.isNotBlank() }
        ?: "/tmp/agent-task-queue"
    return Paths.get(configuredPath ?: fallback)
}
