package com.clawbridge.mobile.ui

import com.clawbridge.mobile.data.SessionSummaryUi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SessionViewMode {
    TIME,
    PROJECT
}

enum class SessionStatusFilter(val label: String) {
    ALL("全部"),
    RUNNING("进行中"),
    WAITING("待确认"),
    DONE("已完成")
}

/**
 * Pre-computed immutable presentation model for a single session row.
 * Keeps scrolling 60/120fps smooth on 56+ sessions by doing all text cleaning,
 * title humanization, deduplication, and search-blob indexing once per list update.
 */
data class SessionDisplayModel(
    val raw: SessionSummaryUi,
    val displayTitle: String,
    val previewText: String,
    val projectLabel: String,
    val modelTag: String,
    val originalIdHint: String,
    val isGenericIdTranslated: Boolean,
    val searchCorpusLower: String,
)

sealed interface SessionListEntry {
    val key: String
    val contentType: String

    data class SectionHeader(
        override val key: String,
        val title: String,
        val count: Int,
        val kind: HeaderKind,
    ) : SessionListEntry {
        override val contentType: String = "section_header"
    }

    data class CollapsibleProjectHeader(
        override val key: String,
        val projectName: String,
        val count: Int,
        val hasActive: Boolean,
        val expanded: Boolean,
    ) : SessionListEntry {
        override val contentType: String = "project_header"
    }

    data class SessionRow(
        override val key: String,
        val model: SessionDisplayModel,
        val isPinned: Boolean,
        val showProjectInline: Boolean,
        val showBottomDivider: Boolean,
    ) : SessionListEntry {
        override val contentType: String = "session_row"
    }
}

enum class HeaderKind {
    PINNED,
    RUNNING,
    TIME
}

private val GENERIC_CODEX_TITLE_RE = Regex("^Codex\\s*会话\\s*([0-9a-fA-F-]{4,})$", RegexOption.IGNORE_CASE)
private val GENERIC_AGY_TITLE_RE = Regex("^(Antigravity\\s*会话\\s*[0-9a-fA-F-]*|未命名会话)$", RegexOption.IGNORE_CASE)
private val PURE_HEX_ID_RE = Regex("^[0-9a-fA-F-]{6,}$")
private val LEADING_BRACKET_MODEL_RE = Regex("^\\[([A-Za-z0-9._:/-]+)]\\s*")
private val MD_LINK_RE = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val MD_DECORATION_RE = Regex("^(#+\\s+|[-*>]+\\s+|\\*{1,3}|_{1,2})")
private val WHITESPACE_RE = Regex("\\s+")

internal fun cleanCompactText(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
        .replace(MD_LINK_RE, "$1")
        .replace("`", "")
        .replace("**", "")
        .replace("__", "")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace(WHITESPACE_RE, " ")
        .trim()
    while (MD_DECORATION_RE.containsMatchIn(s)) {
        s = s.replaceFirst(MD_DECORATION_RE, "").trim()
    }
    return s
}

private fun normalizeForDupCompare(text: String): String {
    return cleanCompactText(text)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE_RE, "")
        .trimEnd('.', '。', '…', ':', '：')
}

internal fun isMeaningfulProject(project: String): Boolean {
    val p = project.trim()
    if (p.isEmpty()) return false
    return p !in setOf("未分组对话", "未分组项目", "默认项目", "默认工作区", "Codex", "Codex CLI", "Antigravity")
}

private fun compactModelLabel(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    return s
        .replace(" (Medium)", "", ignoreCase = true)
        .replace(" (High)", " High", ignoreCase = true)
        .replace(" (Low)", " Low", ignoreCase = true)
        .trim()
}

private fun truncateTitleSnippet(text: String, maxChars: Int = 22): String {
    val clean = cleanCompactText(text)
    if (clean.length <= maxChars) return clean
    val slice = clean.take(maxChars)
    val naturalBreak = slice.indexOfLast { it == '。' || it == '，' || it == '：' || it == '；' || it == '?' || it == '？' }
    val cut = if (naturalBreak >= 12) slice.substring(0, naturalBreak) else slice.trimEnd()
    return cut.trimEnd(',', '，', ':', '：', '-', ' ') + "…"
}

internal fun buildSessionDisplayModel(item: SessionSummaryUi): SessionDisplayModel {
    val rawTitleClean = cleanCompactText(item.title)
    var rawPreview = item.preview.trim()

    // Extract leading `[gpt-6.2-sol]` model tag from Codex thread previews if present
    var extractedModelFromPreview = ""
    val bracketMatch = LEADING_BRACKET_MODEL_RE.find(rawPreview)
    if (bracketMatch != null) {
        extractedModelFromPreview = bracketMatch.groupValues[1].trim()
        rawPreview = rawPreview.removeRange(bracketMatch.range).trim()
    }

    val cleanPreview = cleanCompactText(rawPreview)
    val modelTag = compactModelLabel(
        item.currentModelLabel
            .ifBlank { item.currentModelId }
            .ifBlank { extractedModelFromPreview }
    )
    val projectLabel = item.project.trim().ifBlank { "未分组项目" }
    val shortId = item.id.take(8)

    val isGenericId = rawTitleClean.isBlank() ||
        GENERIC_CODEX_TITLE_RE.matches(rawTitleClean) ||
        GENERIC_AGY_TITLE_RE.matches(rawTitleClean) ||
        PURE_HEX_ID_RE.matches(rawTitleClean)

    val previewIsMeaningfulText = cleanPreview.isNotBlank() &&
        cleanPreview != "Codex 会话" &&
        cleanPreview != "Codex 会话记录" &&
        !cleanPreview.matches(Regex("^共\\s*\\d+\\s*步$"))

    val displayTitle: String
    val originalIdHint: String
    var previewConsumedIntoTitle = false

    if (isGenericId) {
        originalIdHint = "#$shortId"
        displayTitle = when {
            previewIsMeaningfulText -> {
                previewConsumedIntoTitle = true
                truncateTitleSnippet(cleanPreview, 22)
            }
            isMeaningfulProject(projectLabel) -> {
                val clientName = if (item.source == "codex") "Codex" else "Antigravity"
                "$projectLabel · $clientName 对话"
            }
            else -> {
                val clientName = if (item.source == "codex") "Codex 对话" else "未命名对话"
                "$clientName · $shortId"
            }
        }
    } else {
        originalIdHint = ""
        displayTitle = rawTitleClean
    }

    // Build non-redundant preview line:
    // Priority: real message summary (different from title) -> contextual metadata (never duplicate title!)
    val titleNorm = normalizeForDupCompare(displayTitle)
    val previewNorm = normalizeForDupCompare(cleanPreview)
    val isDuplicateOfTitle = previewNorm.isNotEmpty() &&
        (previewNorm == titleNorm || (titleNorm.length >= 6 && titleNorm.startsWith(previewNorm)))

    val basePreview: String = when {
        previewConsumedIntoTitle -> {
            // Continue from after the snippet promoted to title so Line 2 never repeats Line 1
            val promotedPrefix = displayTitle.trimEnd('…')
            val remainder = if (cleanPreview.startsWith(promotedPrefix)) {
                cleanPreview.removePrefix(promotedPrefix)
                    .trimStart(' ', '：', ':', '，', ',', '。', '；', ';', '-', '—')
            } else {
                ""
            }
            remainder
        }
        previewIsMeaningfulText && !isDuplicateOfTitle -> cleanPreview
        else -> {
            // Avoid repeating the title! Show useful metadata instead.
            val metaParts = mutableListOf<String>()
            if (item.stepCount > 0) metaParts += "${item.stepCount} 步"
            if (item.subagentCount > 0) metaParts += "子代理 ${item.subagentCount}"
            metaParts.joinToString(" · ")
        }
    }

    val finalPreview = buildString {
        if (basePreview.isNotBlank()) {
            append(basePreview)
        }
        if (originalIdHint.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(originalIdHint)
        }
    }

    // Search covers all fields available from Bridge's session.list payload:
    // raw title, humanized title, project/group, workspacePath, model label/id/tag, preview message summary, id, source
    val searchCorpus = buildString {
        append(item.title).append(' ')
        append(displayTitle).append(' ')
        append(item.project).append(' ')
        append(item.workspacePath).append(' ')
        append(item.preview).append(' ')
        append(modelTag).append(' ')
        append(item.currentModelId).append(' ')
        append(item.currentModelLabel).append(' ')
        append(item.id).append(' ')
        append(item.source).append(' ')
        if (item.source == "antigravity") append("agy ")
    }.lowercase(Locale.ROOT)

    return SessionDisplayModel(
        raw = item,
        displayTitle = displayTitle,
        previewText = finalPreview,
        projectLabel = projectLabel,
        modelTag = modelTag,
        originalIdHint = originalIdHint,
        isGenericIdTranslated = isGenericId,
        searchCorpusLower = searchCorpus,
    )
}

internal fun matchesSessionFilters(
    model: SessionDisplayModel,
    sourceFilter: String,
    statusFilter: SessionStatusFilter,
    query: String,
): Boolean {
    if (sourceFilter == "antigravity" && model.raw.source != "antigravity") return false
    if (sourceFilter == "codex" && model.raw.source != "codex") return false

    val st = model.raw.status
    val statusOk = when (statusFilter) {
        SessionStatusFilter.ALL -> true
        SessionStatusFilter.RUNNING -> st == "running"
        SessionStatusFilter.WAITING -> st == "waiting_approval"
        SessionStatusFilter.DONE -> st != "running" && st != "waiting_approval"
    }
    if (!statusOk) return false

    val trimmed = query.trim().lowercase(Locale.ROOT)
    if (trimmed.isEmpty()) return true
    val tokens = trimmed.split(WHITESPACE_RE).filter { it.isNotEmpty() }
    return tokens.all { token -> model.searchCorpusLower.contains(token) }
}

private fun sessionPriorityRank(status: String): Int = when (status) {
    "running" -> 0
    "waiting_approval" -> 1
    "error" -> 2
    else -> 3
}

private val ACTIVE_FIRST_COMPARATOR = Comparator<SessionDisplayModel> { a, b ->
    val p = sessionPriorityRank(a.raw.status).compareTo(sessionPriorityRank(b.raw.status))
    if (p != 0) p else b.raw.updatedAt.compareTo(a.raw.updatedAt)
}

internal enum class TimeBucket(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    EARLIER("更早")
}

internal fun classifyTimeBucket(tsMs: Long, nowMs: Long): TimeBucket {
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val startOfYesterday = startOfToday - 24L * 3600_000L

    return when {
        tsMs >= startOfToday -> TimeBucket.TODAY
        tsMs >= startOfYesterday -> TimeBucket.YESTERDAY
        else -> TimeBucket.EARLIER
    }
}

internal fun formatCompactSessionTime(tsMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (tsMs <= 0L) return ""
    return when (classifyTimeBucket(tsMs, nowMs)) {
        TimeBucket.TODAY -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tsMs))
        TimeBucket.YESTERDAY -> "昨天"
        TimeBucket.EARLIER -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(tsMs))
    }
}

/**
 * Builds the flat, immutable LazyColumn entry list for either TIME or PROJECT view.
 *
 * - TIME view (default):
 *     [Pinned] -> [Running / Active] -> [今天] -> [昨天] -> [更早]
 *     Sorted by updatedAt DESC, with running sessions automatically at top.
 * - PROJECT view:
 *     [Pinned] -> Project groups ordered by active-first then most recent activity.
 *     Groups with >= 2 sessions get a compact collapsible header (top active groups expanded by default);
 *     Groups with ONLY 1 session NEVER waste a full-width header row — they render inline with a subtle project tag!
 */
internal fun buildSessionListEntries(
    filtered: List<SessionDisplayModel>,
    pinnedIds: Set<String>,
    viewMode: SessionViewMode,
    expandedProjects: Map<String, Boolean>,
    searchActive: Boolean,
    nowMs: Long = System.currentTimeMillis(),
): List<SessionListEntry> {
    if (filtered.isEmpty()) return emptyList()

    val entries = ArrayList<SessionListEntry>(filtered.size + 12)
    val pinned = filtered.filter { it.raw.id in pinnedIds }.sortedWith(ACTIVE_FIRST_COMPARATOR)
    val unpinned = filtered.filterNot { it.raw.id in pinnedIds }

    if (pinned.isNotEmpty()) {
        entries += SessionListEntry.SectionHeader(
            key = "hdr-pinned",
            title = "置顶",
            count = pinned.size,
            kind = HeaderKind.PINNED
        )
        pinned.forEachIndexed { idx, m ->
            entries += SessionListEntry.SessionRow(
                key = "pin-${m.raw.source}-${m.raw.id}",
                model = m,
                isPinned = true,
                showProjectInline = true,
                showBottomDivider = idx < pinned.lastIndex || viewMode == SessionViewMode.PROJECT
            )
        }
    }

    if (viewMode == SessionViewMode.TIME) {
        val activeUnpinned = unpinned
            .filter { it.raw.status == "running" }
            .sortedByDescending { it.raw.updatedAt }
        val restUnpinned = unpinned
            .filterNot { it.raw.status == "running" }
            .sortedByDescending { it.raw.updatedAt }

        if (activeUnpinned.isNotEmpty()) {
            entries += SessionListEntry.SectionHeader(
                key = "hdr-running",
                title = "进行中",
                count = activeUnpinned.size,
                kind = HeaderKind.RUNNING
            )
            activeUnpinned.forEachIndexed { idx, m ->
                entries += SessionListEntry.SessionRow(
                    key = "run-${m.raw.source}-${m.raw.id}",
                    model = m,
                    isPinned = false,
                    showProjectInline = true,
                    showBottomDivider = idx < activeUnpinned.lastIndex
                )
            }
        }

        val byBucket = restUnpinned.groupBy { classifyTimeBucket(it.raw.updatedAt, nowMs) }
        for (bucket in listOf(TimeBucket.TODAY, TimeBucket.YESTERDAY, TimeBucket.EARLIER)) {
            val list = byBucket[bucket].orEmpty()
            if (list.isEmpty()) continue
            entries += SessionListEntry.SectionHeader(
                key = "hdr-time-${bucket.name}",
                title = bucket.label,
                count = list.size,
                kind = HeaderKind.TIME
            )
            list.forEachIndexed { idx, m ->
                entries += SessionListEntry.SessionRow(
                    key = "t-${bucket.name}-${m.raw.source}-${m.raw.id}",
                    model = m,
                    isPinned = false,
                    showProjectInline = true,
                    showBottomDivider = idx < list.lastIndex
                )
            }
        }
    } else {
        // PROJECT view: group unpinned sessions by project, active & most recently updated groups first
        val grouped = unpinned
            .groupBy { it.projectLabel }
            .entries
            .map { (proj, items) ->
                val sortedItems = items.sortedWith(ACTIVE_FIRST_COMPARATOR)
                val hasRunning = sortedItems.any { it.raw.status == "running" }
                val hasWaiting = sortedItems.any { it.raw.status == "waiting_approval" }
                val latestTs = sortedItems.maxOf { it.raw.updatedAt }
                ProjectBucket(proj, sortedItems, hasRunning, hasWaiting, latestTs)
            }
            .sortedWith(
                compareByDescending<ProjectBucket> { it.hasRunning }
                    .thenByDescending { it.hasWaiting }
                    .thenByDescending { it.latestTs }
            )

        var multiGroupIndex = 0
        for ((gIdx, bucket) in grouped.withIndex()) {
            if (bucket.items.size == 1) {
                // Single-session project: NO standalone header row! Render compact row with inline project chip.
                val m = bucket.items.first()
                entries += SessionListEntry.SessionRow(
                    key = "p1-${m.raw.source}-${m.raw.id}",
                    model = m,
                    isPinned = false,
                    showProjectInline = true,
                    showBottomDivider = gIdx < grouped.lastIndex
                )
            } else {
                val defaultExpanded = searchActive || bucket.hasRunning || bucket.hasWaiting || multiGroupIndex < 3
                val isExpanded = expandedProjects[bucket.projectName] ?: defaultExpanded
                multiGroupIndex++

                entries += SessionListEntry.CollapsibleProjectHeader(
                    key = "hdr-proj-${bucket.projectName}",
                    projectName = bucket.projectName,
                    count = bucket.items.size,
                    hasActive = bucket.hasRunning || bucket.hasWaiting,
                    expanded = isExpanded
                )
                if (isExpanded) {
                    bucket.items.forEachIndexed { idx, m ->
                        entries += SessionListEntry.SessionRow(
                            key = "pm-${bucket.projectName}-${m.raw.source}-${m.raw.id}",
                            model = m,
                            isPinned = false,
                            showProjectInline = false,
                            showBottomDivider = idx < bucket.items.lastIndex
                        )
                    }
                }
            }
        }
    }

    return entries
}

private data class ProjectBucket(
    val projectName: String,
    val items: List<SessionDisplayModel>,
    val hasRunning: Boolean,
    val hasWaiting: Boolean,
    val latestTs: Long,
)
