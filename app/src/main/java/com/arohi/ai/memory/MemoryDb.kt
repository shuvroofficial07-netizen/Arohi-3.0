package com.arohi.ai.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: String = "general",     // general | preference | fact | mood | instruction
    val importance: Int = 3,              // 1..5
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,                     // user | arohi | system
    val text: String,
    val emotion: String = "NEUTRAL",
    val timestamp: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timeOfDay: String,                // "HH:mm"
    val stepsJson: String,                // JSON array of step strings
    val enabled: Boolean = true,
    val lastRun: Long = 0
)

@Entity(tableName = "activity_log")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                     // ACTION | AUTOMATION | NOTIFICATION | MEMORY | SECURITY | SMART_HOME | PC
    val detail: String,
    val timestamp: Long
)

@Dao
interface MemoryDao {
    @Insert suspend fun insert(item: MemoryItem): Long
    @Update suspend fun update(item: MemoryItem)
    @Delete suspend fun delete(item: MemoryItem)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM memories") suspend fun clearAll()
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryItem>>
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun topMemories(limit: Int): List<MemoryItem>
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<MemoryItem>>
    @Query("SELECT COUNT(*) FROM memories") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM memories") fun countSync(): Int
    @Query("SELECT * FROM memories WHERE content = :c LIMIT 1") suspend fun findByContent(c: String): MemoryItem?
}

@Dao
interface ChatDao {
    @Insert suspend fun insert(msg: ChatMessage): Long
    @Query("DELETE FROM chat_messages") suspend fun clearAll()
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :n")
    suspend fun lastMessages(n: Int): List<ChatMessage>
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ChatMessage>>
    @Query("SELECT COUNT(*) FROM chat_messages") suspend fun count(): Int
}

@Dao
interface RoutineDao {
    @Insert suspend fun insert(r: RoutineEntity): Long
    @Update suspend fun update(r: RoutineEntity)
    @Delete suspend fun delete(r: RoutineEntity)
    @Query("SELECT * FROM routines WHERE enabled = 1") suspend fun enabledRoutines(): List<RoutineEntity>
    @Query("SELECT * FROM routines ORDER BY timeOfDay") fun observeAll(): Flow<List<RoutineEntity>>
}

@Dao
interface ActivityDao {
    @Insert suspend fun insert(a: ActivityLog)
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :n")
    suspend fun recent(n: Int): List<ActivityLog>
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :n")
    fun observeRecent(n: Int): Flow<List<ActivityLog>>
    @Query("DELETE FROM activity_log") suspend fun clear()
}

@Database(
    entities = [MemoryItem::class, ChatMessage::class, RoutineEntity::class, ActivityLog::class],
    version = 1,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun routineDao(): RoutineDao
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile private var inst: MemoryDatabase? = null
        fun get(context: Context): MemoryDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext, MemoryDatabase::class.java, "arohi_memory.db"
                ).fallbackToDestructiveMigration().build().also { inst = it }
            }
    }
}
