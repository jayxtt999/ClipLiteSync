package com.cliplite.sync.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 剪贴板历史条目（图片不进 BLOB，落盘 files/<syncId>.img） */
@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey val syncId: String,
    val seq: Long = 0,
    val clipType: Int,          // 1=Text 2=Files 3=Image 4=ImageV5 5=Html
    val hash: Long,
    val textContent: String?,   // 文本 or 图片/文件占位说明
    val imageFile: String?,     // 图片落盘路径
    val thumbFile: String?,     // 缩略图路径
    val originDevice: String,
    val sourceApp: String? = null,
    val localOnly: Boolean = false,
    val pinned: Boolean = false,
    val ts: Long,
    val originRemote: Boolean = false,   // 防回环：远端注入的不再上行
)

/** syncId 已处理集合（去重） */
@Entity(tableName = "dedup")
data class DedupEntity(
    @PrimaryKey val syncId: String,
    val seq: Long,
    val seenAt: Long,
)

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips ORDER BY pinned DESC, ts DESC")
    fun observeAll(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE syncId = :id LIMIT 1")
    suspend fun find(id: String): ClipEntity?

    @Query("SELECT * FROM clips WHERE hash = :hash ORDER BY ts DESC LIMIT 1")
    suspend fun findByHash(hash: Long): ClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ClipEntity)

    @Query("UPDATE clips SET pinned = :pinned WHERE syncId = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM clips WHERE syncId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM clips")
    suspend fun deleteAll()

    @Query("SELECT imageFile, thumbFile FROM clips WHERE imageFile IS NOT NULL OR thumbFile IS NOT NULL")
    suspend fun allImageFiles(): List<FileRow>

    @Query("SELECT COUNT(*) FROM clips")
    suspend fun count(): Int

    @Query("DELETE FROM clips WHERE pinned = 0 AND syncId IN (SELECT syncId FROM clips ORDER BY ts DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(keep: Int)
}

data class FileRow(val imageFile: String?, val thumbFile: String?)

@Dao
interface DedupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: DedupEntity)

    @Query("SELECT COUNT(*) FROM dedup WHERE syncId = :id")
    suspend fun count(id: String): Int

    @Query("DELETE FROM dedup WHERE seenAt < :before")
    suspend fun trim(before: Long)

    @Query("DELETE FROM dedup")
    suspend fun clear()
}

@Database(entities = [ClipEntity::class, DedupEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun dedupDao(): DedupDao
}
