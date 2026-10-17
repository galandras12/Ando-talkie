package hu.ando.talkie.data

import android.content.Context
import hu.ando.talkie.core.StorageCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class Msg(
    val id: Long,
    val peer: String,
    val outgoing: Boolean,
    val kind: Int,
    val text: String?,
    val fileName: String?,
    val mime: String?,
    val size: Long,
    val path: String?,
    val durationMs: Int,
    val timestamp: Long,
    val status: Int,
    val unread: Boolean,
)

class Repo(private val ctx: Context, val db: AppDb, private val cipher: StorageCipher) {
    val mediaDir: File = File(ctx.filesDir, "media").apply { mkdirs() }

    private fun MessageEntity.toMsg() = Msg(
        id, peer, outgoing, kind, cipher.decrypt(textEnc), fileName, mime, size, path, durationMs,
        timestamp, status, unread,
    )

    val peers: Flow<List<PeerEntity>> = db.peers().all()
    fun peer(fp: String): Flow<PeerEntity?> = db.peers().observe(fp)
    suspend fun peerOnce(fp: String) = db.peers().get(fp)
    suspend fun savePeer(p: PeerEntity) = db.peers().upsert(p)

    fun messages(peer: String): Flow<List<Msg>> = db.messages().forPeer(peer).map { l -> l.map { it.toMsg() } }
    val allMessages: Flow<List<Msg>> = db.messages().allNewestFirst().map { l -> l.map { it.toMsg() } }

    suspend fun addMessage(
        peer: String, outgoing: Boolean, kind: Int, text: String? = null, fileName: String? = null,
        mime: String? = null, size: Long = 0, path: String? = null, durationMs: Int = 0,
        status: Int = Status.DONE, unread: Boolean = false,
    ): Long = db.messages().insert(
        MessageEntity(
            peer = peer, outgoing = outgoing, kind = kind, textEnc = cipher.encrypt(text), fileName = fileName,
            mime = mime, size = size, path = path, durationMs = durationMs,
            timestamp = System.currentTimeMillis(), status = status, unread = unread,
        )
    )

    suspend fun setStatus(id: Long, status: Int, size: Long) = db.messages().setStatus(id, status, size)
    suspend fun failPending() = db.messages().failPending()
    suspend fun markRead(peer: String) = db.messages().markRead(peer)

    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        db.messages().get(id)?.path?.let { File(it).delete() }
        db.messages().delete(id)
    }

    suspend fun deleteConversation(peer: String) = withContext(Dispatchers.IO) {
        db.messages().paths(peer).forEach { File(it).delete() }
        db.messages().deleteForPeer(peer)
    }

    suspend fun forgetPeer(peer: String) {
        deleteConversation(peer)
        db.peers().delete(peer)
    }
}
