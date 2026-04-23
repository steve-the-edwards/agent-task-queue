package com.block.agenttaskqueue.sidecar

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class QueueTask(
    val id: Int,
    val queueName: String,
    val status: String,
    val command: String?,
    val workingDirectory: String?,
    val worktreeRoot: String?,
    val repoName: String?,
    val gitBranch: String?,
    val agentName: String?,
    val pid: Int?,
    val childPid: Int?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    val displayCommand: String
        get() = (command ?: "unknown").replace(Regex("^(\\w+=\\S+\\s+)+"), "")

    val displayRepoAndWorktree: String?
        get() {
            val repo = repoName?.takeIf { it.isNotBlank() }
            val worktree = worktreeRoot?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: workingDirectory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val cwdSuffix = relativeDirectory(worktreeRoot, workingDirectory)

            val parts = buildList {
                repo?.let(::add)
                worktree?.takeUnless { it == repo }?.let(::add)
                cwdSuffix?.let(::add)
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    val displayBranch: String?
        get() = gitBranch?.takeIf { it.isNotBlank() }

    val displayAgent: String?
        get() = agentName?.takeIf { it.isNotBlank() }

    fun statusAge(now: Instant = Instant.now()): String {
        val reference = when (status.lowercase()) {
            "running" -> parseQueueInstant(updatedAt, ZoneId.systemDefault()) ?: parseQueueInstant(createdAt)
            else -> parseQueueInstant(createdAt)
        } ?: return "time unknown"

        val prefix = if (status.equals("running", ignoreCase = true)) "running" else "queued"
        return "$prefix ${relativeDuration(now, reference)}"
    }
}

data class QueueSummary(
    val total: Int,
    val running: Int,
    val waiting: Int,
) {
    companion object {
        fun fromTasks(tasks: List<QueueTask>): QueueSummary {
            return QueueSummary(
                total = tasks.size,
                running = tasks.count { it.status.equals("running", ignoreCase = true) },
                waiting = tasks.count { it.status.equals("waiting", ignoreCase = true) },
            )
        }
    }
}

data class QueueLane(
    val queueName: String,
    val tasks: List<QueueTask>,
) {
    val runningTasks: List<QueueTask> = tasks.filter { it.status.equals("running", ignoreCase = true) }
    val waitingTasks: List<QueueTask> = tasks.filter { it.status.equals("waiting", ignoreCase = true) }
    val runningCount: Int = runningTasks.size
    val waitingCount: Int = waitingTasks.size
}

data class ScopeGroup(
    val scopeName: String,
    val lanes: List<QueueLane>,
) {
    val taskCount: Int = lanes.sumOf { it.tasks.size }
    val runningCount: Int = lanes.sumOf { it.runningCount }
    val waitingCount: Int = lanes.sumOf { it.waitingCount }
}

data class QueueSnapshot(
    val dataDir: Path,
    val tasks: List<QueueTask>,
    val refreshedAt: Instant,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val summary: QueueSummary = QueueSummary.fromTasks(tasks)
    val runningTasks: List<QueueTask> = tasks.filter { it.status.equals("running", ignoreCase = true) }
    val waitingTasks: List<QueueTask> = tasks.filter { it.status.equals("waiting", ignoreCase = true) }
    val queueLanes: List<QueueLane> = tasks
        .groupBy { it.queueName }
        .map { (queueName, queuedTasks) -> QueueLane(queueName, queuedTasks.sortedBy { it.id }) }
        .sortedWith(queueLaneComparator)
    val scopeGroups: List<ScopeGroup> = queueLanes
        .groupBy { rootScope(it.queueName) }
        .map { (scopeName, lanes) -> ScopeGroup(scopeName, lanes.sortedWith(queueLaneComparator)) }
        .sortedWith(scopeGroupComparator)

    companion object {
        fun empty(
            dataDir: Path,
            statusMessage: String? = null,
            errorMessage: String? = null,
        ): QueueSnapshot {
            return QueueSnapshot(
                dataDir = dataDir,
                tasks = emptyList(),
                refreshedAt = Instant.now(),
                statusMessage = statusMessage,
                errorMessage = errorMessage,
            )
        }

        fun fromTasks(
            dataDir: Path,
            tasks: List<QueueTask>,
            statusMessage: String? = null,
        ): QueueSnapshot {
            return QueueSnapshot(
                dataDir = dataDir,
                tasks = tasks.sortedWith(compareBy<QueueTask>({ it.queueName }, { it.id })),
                refreshedAt = Instant.now(),
                statusMessage = statusMessage,
            )
        }
    }
}

fun parseQueueInstant(raw: String?, defaultZone: ZoneId = ZoneOffset.UTC): Instant? {
    if (raw.isNullOrBlank()) {
        return null
    }

    val normalized = raw.replace(" ", "T")
    return runCatching {
        LocalDateTime.parse(normalized).atZone(defaultZone).toInstant()
    }.getOrNull()
}

fun formatRefreshTime(instant: Instant): String {
    val localTime = instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return localTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
}

private fun rootScope(queueName: String): String = queueName.substringBefore('/')

private fun relativeDirectory(root: String?, path: String?): String? {
    if (root.isNullOrBlank() || path.isNullOrBlank()) {
        return null
    }

    val prefix = if (root.endsWith('/')) root else "$root/"
    return path.removePrefix(prefix).takeIf { it != path && it.isNotBlank() }
}

private val queueLaneComparator =
    compareByDescending<QueueLane> { it.runningCount > 0 }
        .thenByDescending { it.waitingCount }
        .thenBy { it.queueName }

private val scopeGroupComparator =
    compareByDescending<ScopeGroup> { it.runningCount > 0 }
        .thenByDescending { it.waitingCount }
        .thenBy { it.scopeName }

private fun relativeDuration(now: Instant, then: Instant): String {
    val elapsed = Duration.between(then, now).seconds.coerceAtLeast(0)
    return when {
        elapsed < 60 -> "${elapsed}s"
        elapsed < 3600 -> "${elapsed / 60}m"
        elapsed < 86_400 -> "${elapsed / 3600}h ${elapsed % 3600 / 60}m"
        else -> "${elapsed / 86_400}d ${elapsed % 86_400 / 3600}h"
    }
}
