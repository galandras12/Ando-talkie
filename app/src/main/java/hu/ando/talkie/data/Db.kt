package hu.ando.talkie.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

object Kind {
    const val TEXT = 0
    const val FILE = 1
    const val IMAGE = 2
    const val VIDEO = 3
    const val VOICE = 4
    const val CALL = 5
}

object Status {
    const val IN_PROGRESS = 0
    const val DONE = 1
    const val FAILED = 2
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val fingerprint: String,
    val name: String,
    val identityKey: ByteArray,
    val firstSeen: Long,
    val lastSeen: Long,
)

@Entity(tableName = "messages", indices = [Index("peer")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peer: String,
    val outgoing: Boolean,
    val kind: Int,
    /** Titkosítva tárolt szöveg (Keystore). */
    val textEnc: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    val path: String? = null,
    val durationMs: Int = 0,
    val timestamp: Long,
    val status: Int = Status.DONE,
    val unread: Boolean = false,
)

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun all(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE fingerprint = :fp")
    suspend fun get(fp: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE fingerprint = :fp")
    fun observe(fp: String): Flow<PeerEntity?>

    @Query("DELETE FROM peers WHERE fingerprint = :fp")
    suspend fun delete(fp: String)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY timestamp ASC, id ASC")
    fun forPeer(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC, id DESC")
    fun allNewestFirst(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("UPDATE messages SET status = :status, size = :size WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int, size: Long)

    @Query("UPDATE messages SET status = 2 WHERE status = 0")
    suspend fun failPending()

    @Query("UPDATE messages SET unread = 0 WHERE peer = :peer AND unread = 1")
    suspend fun markRead(peer: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT path FROM messages WHERE peer = :peer AND path IS NOT NULL")
    suspend fun paths(peer: String): List<String>

    @Query("DELETE FROM messages WHERE peer = :peer")
    suspend fun deleteForPeer(peer: String)
}

@Database(entities = [PeerEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun peers(): PeerDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "ando.db").build()
    }
}
