package com.clawbridge.mobile.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Two-layer (L1 in-memory LRU + L2 app cacheDir JSON) cache for recent SessionDetail snapshots.
 * Enables instant 0ms session open while fresh `session.stream` syncs silently in background.
 */
class SessionDetailCache(
    cacheRootDir: File,
    private val maxEntries: Int = 20,
) {
    constructor(context: Context, maxEntries: Int = 20) : this(
        File(context.applicationContext.cacheDir, "session_detail_cache"),
        maxEntries
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = cacheRootDir
    private val memoryLru = object : LinkedHashMap<String, SessionDetailState>(maxEntries + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionDetailState>?): Boolean {
            return size > maxEntries
        }
    }

    private fun cacheKey(source: String, sessionId: String): String =
        "${source.lowercase()}:${sessionId.trim()}"

    private fun diskFileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(cacheDir, "sess_$digest.json")
    }

    fun getMemory(source: String, sessionId: String): SessionDetailState? {
        val key = cacheKey(source, sessionId)
        synchronized(memoryLru) {
            return memoryLru[key]
        }
    }

    fun get(source: String, sessionId: String): SessionDetailState? {
        val key = cacheKey(source, sessionId)
        synchronized(memoryLru) {
            memoryLru[key]?.let { return it }
        }
        val fromDisk = readFromDisk(key) ?: return null
        synchronized(memoryLru) {
            memoryLru[key] = fromDisk
        }
        return fromDisk
    }

    fun put(detail: SessionDetailState) {
        if (detail.session.id.isBlank()) return
        val key = cacheKey(detail.session.source, detail.session.id)
        val clean = detail.copy(
            messages = detail.messages.takeLast(100),
            artifacts = detail.artifacts.take(100),
            loading = false,
            syncing = false,
            fromCache = false,
        )
        synchronized(memoryLru) {
            memoryLru[key] = clean
        }
        writeToDisk(key, clean)
    }

    private fun writeToDisk(key: String, detail: SessionDetailState) {
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val payload = serialize(detail).toString()
            val target = diskFileFor(key)
            val tmp = File(cacheDir, "${target.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
            pruneDiskIfNeeded()
        }
    }

    private fun pruneDiskIfNeeded() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    private fun readFromDisk(key: String): SessionDetailState? {
        return runCatching {
            val f = diskFileFor(key)
            if (!f.exists() || !f.isFile) return null
            val root = json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
            deserialize(root)
        }.getOrNull()
    }

    private fun serialize(detail: SessionDetailState): JsonObject = buildJsonObject {
        put("cdpAvailable", JsonPrimitive(detail.cdpAvailable))
        put("session", buildJsonObject {
            val s = detail.session
            put("id", JsonPrimitive(s.id))
            put("source", JsonPrimitive(s.source))
            put("title", JsonPrimitive(s.title))
            put("project", JsonPrimitive(s.project))
            put("workspacePath", JsonPrimitive(s.workspacePath))
            put("preview", JsonPrimitive(s.preview))
            put("status", JsonPrimitive(s.status))
            put("stepCount", JsonPrimitive(s.stepCount))
            put("subagentCount", JsonPrimitive(s.subagentCount))
            put("runningSubagentCount", JsonPrimitive(s.runningSubagentCount))
            put("updatedAt", JsonPrimitive(s.updatedAt))
            put("currentModelId", JsonPrimitive(s.currentModelId))
            put("currentModelLabel", JsonPrimitive(s.currentModelLabel))
        })
        put("messages", buildJsonArray {
            detail.messages.forEach { m ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(m.id))
                    put("role", JsonPrimitive(m.role))
                    put("text", JsonPrimitive(m.text))
                    put("thinkingSummary", JsonPrimitive(m.thinkingSummary))
                    put("toolSteps", buildJsonArray {
                        m.toolSteps.forEach { add(JsonPrimitive(it)) }
                    })
                    put("status", JsonPrimitive(m.status))
                    put("ts", JsonPrimitive(m.ts))
                })
            }
        })
        put("subagents", buildJsonArray {
            detail.subagents.forEach { sub ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(sub.id))
                    put("agentName", JsonPrimitive(sub.agentName))
                    put("role", JsonPrimitive(sub.role))
                    put("status", JsonPrimitive(sub.status))
                    put("stepCount", JsonPrimitive(sub.stepCount))
                    put("preview", JsonPrimitive(sub.preview))
                    put("updatedAt", JsonPrimitive(sub.updatedAt))
                })
            }
        })
        put("artifacts", buildJsonArray {
            detail.artifacts.forEach { a ->
                add(buildJsonObject {
                    put("artifactId", JsonPrimitive(a.artifactId))
                    put("name", JsonPrimitive(a.name))
                    put("kind", JsonPrimitive(a.kind))
                    put("mimeType", JsonPrimitive(a.mimeType))
                    put("sizeBytes", JsonPrimitive(a.sizeBytes))
                    put("updatedAt", JsonPrimitive(a.updatedAt))
                    put("addLines", JsonPrimitive(a.addLines))
                    put("delLines", JsonPrimitive(a.delLines))
                    put("filePath", JsonPrimitive(a.filePath))
                })
            }
        })
    }

    private fun deserialize(root: JsonObject): SessionDetailState? {
        val sObj = root["session"] as? JsonObject ?: return null
        val sid = sObj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (sid.isBlank()) return null

        val session = SessionSummaryUi(
            id = sid,
            source = sObj["source"]?.jsonPrimitive?.contentOrNull ?: "antigravity",
            title = sObj["title"]?.jsonPrimitive?.contentOrNull ?: "未命名会话",
            project = sObj["project"]?.jsonPrimitive?.contentOrNull ?: "默认项目",
            workspacePath = sObj["workspacePath"]?.jsonPrimitive?.contentOrNull ?: "",
            preview = sObj["preview"]?.jsonPrimitive?.contentOrNull ?: "",
            status = sObj["status"]?.jsonPrimitive?.contentOrNull ?: "done",
            stepCount = sObj["stepCount"]?.jsonPrimitive?.intOrNull ?: 0,
            subagentCount = sObj["subagentCount"]?.jsonPrimitive?.intOrNull ?: 0,
            runningSubagentCount = sObj["runningSubagentCount"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = sObj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            currentModelId = sObj["currentModelId"]?.jsonPrimitive?.contentOrNull ?: "",
            currentModelLabel = sObj["currentModelLabel"]?.jsonPrimitive?.contentOrNull ?: "",
        )

        val msgs = (root["messages"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ChatMessageUi(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    role = o["role"]?.jsonPrimitive?.contentOrNull ?: "agent",
                    text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    thinkingSummary = o["thinkingSummary"]?.jsonPrimitive?.contentOrNull ?: "",
                    toolSteps = (o["toolSteps"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    status = o["status"]?.jsonPrimitive?.contentOrNull ?: "done",
                    ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        } ?: emptyList()

        val subs = (root["subagents"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                SubagentUi(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    agentName = o["agentName"]?.jsonPrimitive?.contentOrNull ?: "subagent",
                    role = o["role"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = o["status"]?.jsonPrimitive?.contentOrNull ?: "done",
                    stepCount = o["stepCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    preview = o["preview"]?.jsonPrimitive?.contentOrNull ?: "",
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        } ?: emptyList()

        val arts = (root["artifacts"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ArtifactItemUi(
                    artifactId = o["artifactId"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "file",
                    mimeType = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                    sizeBytes = o["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    addLines = o["addLines"]?.jsonPrimitive?.intOrNull ?: 0,
                    delLines = o["delLines"]?.jsonPrimitive?.intOrNull ?: 0,
                    filePath = o["filePath"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }.getOrNull()
        } ?: emptyList()

        return SessionDetailState(
            session = session,
            cdpAvailable = root["cdpAvailable"]?.jsonPrimitive?.booleanOrNull ?: false,
            messages = msgs,
            subagents = subs,
            artifacts = arts,
            loading = false,
            syncing = false,
            fromCache = true,
        )
    }
}
