package com.arohi.ai.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MemoryExport(
    val memories: List<MemoryItem>,
    val chat: List<ChatMessage>,
    val routines: List<RoutineEntity>,
    val exportedAt: Long
)

/**
 * Single facade over the Room memory system.
 * - short-term: recent chat context
 * - long-term: MemoryItem facts / preferences / moods / standing instructions
 */
class MemoryRepository private constructor(context: Context) {

    private val db = MemoryDatabase.get(context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsStore.get(context)

    val allMemories: Flow<List<MemoryItem>> = db.memoryDao().observeAll()
    val allChat: Flow<List<ChatMessage>> = db.chatDao().observeAll()
    val recentActivity: Flow<List<ActivityLog>> = db.activityDao().observeRecent(20)
    val routines: Flow<List<RoutineEntity>> = db.routineDao().observeAll()

    fun enabled(): Boolean = settings.memoryEnabled

    // ---------- Memories ----------
    suspend fun addMemory(content: String, category: String = "general", importance: Int = 3) {
        if (!enabled() || content.isBlank()) return
        val existing = db.memoryDao().findByContent(content.trim())
        if (existing != null) {
            db.memoryDao().update(existing.copy(importance = maxOf(existing.importance, importance), updatedAt = System.currentTimeMillis()))
        } else {
            db.memoryDao().insert(
                MemoryItem(content = content.trim(), category = category, importance = importance,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            )
        }
        ArohiBus.toast("মনে রাখা হলো: $content")
        logActivity("MEMORY", "মনে রাখা হলো: $content")
    }

    suspend fun topMemories(limit: Int = 25): List<MemoryItem> = db.memoryDao().topMemories(limit)
    suspend fun memoryCount(): Int = db.memoryDao().count()
    suspend fun deleteMemory(id: Long) = db.memoryDao().deleteById(id)
    suspend fun clearMemories() = db.memoryDao().clearAll()
    fun search(q: String): Flow<List<MemoryItem>> = db.memoryDao().search(q)

    // ---------- Chat ----------
    suspend fun addChat(role: String, text: String, emotion: String = "NEUTRAL") {
        db.chatDao().insert(ChatMessage(role = role, text = text, emotion = emotion, timestamp = System.currentTimeMillis()))
        ArohiBus.chatRefreshed()
    }
    suspend fun recentChat(n: Int = 12): List<ChatMessage> =
        db.chatDao().lastMessages(n).sortedBy { it.timestamp }
    suspend fun clearChat() = db.chatDao().clearAll()

    // ---------- Mood persistence ----------
    suspend fun saveMood(emotion: String) {
        db.memoryDao().findByContent("mood:current")?.let {
            db.memoryDao().update(it.copy(content = "mood:current", category = "mood",
                importance = 5, updatedAt = System.currentTimeMillis()))
        } ?: db.memoryDao().insert(
            MemoryItem(content = "mood:current", category = "mood", importance = 5,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )
        // mood value stored as separate lightweight row
        db.memoryDao().findByContent("mood:value")?.let {
            db.memoryDao().update(it.copy(content = "mood:value", category = emotion,
                importance = 5, updatedAt = System.currentTimeMillis()))
        } ?: db.memoryDao().insert(
            MemoryItem(content = "mood:value", category = emotion, importance = 5,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun loadMood(): EmotionSnapshot {
        val row = db.memoryDao().findByContent("mood:value")
        return EmotionSnapshot(mood = row?.category ?: "NEUTRAL", updatedAt = row?.updatedAt ?: 0L)
    }

    // ---------- Routines ----------
    suspend fun addRoutine(name: String, time: String, steps: List<String>) {
        db.routineDao().insert(RoutineEntity(name = name, timeOfDay = time,
            stepsJson = gson.toJson(steps), enabled = true))
    }
    suspend fun routinesNow(): List<RoutineEntity> = db.routineDao().enabledRoutines()
    suspend fun deleteRoutine(r: RoutineEntity) = db.routineDao().delete(r)
    fun parseSteps(json: String): List<String> =
        try { gson.fromJson(json, object : TypeToken<List<String>>() {}.type) } catch (_: Exception) { emptyList() }

    // ---------- Activity log ----------
    suspend fun logActivity(type: String, detail: String) {
        db.activityDao().insert(ActivityLog(type = type, detail = detail, timestamp = System.currentTimeMillis()))
    }
    suspend fun recentActivities(n: Int = 10): List<ActivityLog> = db.activityDao().recent(n)

    // ---------- Export / Import ----------
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        gson.toJson(MemoryExport(
            memories = db.memoryDao().topMemories(1000),
            chat = db.chatDao().lastMessages(500).sortedBy { it.timestamp },
            routines = db.routineDao().enabledRoutines(),
            exportedAt = System.currentTimeMillis()
        ))
    }

    suspend fun importJson(json: String): Int = withContext(Dispatchers.IO) {
        val data = try { gson.fromJson(json, MemoryExport::class.java) } catch (_: Exception) { null } ?: return@withContext 0
        var n = 0
        data.memories.forEach {
            db.memoryDao().insert(it.copy(id = 0, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())); n++
        }
        data.routines.forEach { db.routineDao().insert(it.copy(id = 0)); n++ }
        n
    }

    companion object {
        @Volatile private var inst: MemoryRepository? = null
        fun get(context: Context): MemoryRepository =
            inst ?: synchronized(this) { inst ?: MemoryRepository(context.applicationContext).also { inst = it } }
    }
}

data class EmotionSnapshot(val mood: String, val updatedAt: Long)
