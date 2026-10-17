package hu.ando.talkie.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import hu.ando.talkie.MainActivity
import hu.ando.talkie.audio.AudioCapture
import hu.ando.talkie.audio.AudioPlayer
import hu.ando.talkie.audio.CallAudio
import hu.ando.talkie.core.CryptoUtil
import hu.ando.talkie.core.KeystoreIdentity
import hu.ando.talkie.core.Prefs
import hu.ando.talkie.data.Kind
import hu.ando.talkie.data.PeerEntity
import hu.ando.talkie.data.Repo
import hu.ando.talkie.data.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val description: String) : LinkState
    /** Új partner: a felhasználóknak össze kell vetni az ellenőrző kódot. */
    data class Verifying(val peerName: String, val fingerprint: String, val sas: String, val waitingPeer: Boolean) : LinkState
    data class Connected(val peerFp: String, val peerName: String, val transport: String) : LinkState
}

sealed interface CallState {
    data object None : CallState
    data class Outgoing(val peerName: String) : CallState
    data class Incoming(val peerName: String) : CallState
    data class Active(val peerName: String, val since: Long) : CallState
}

private class Session(
    val channel: SecureChannel,
    val link: RawLink,
    val fp: String,
    val peerName: String,
    val identityKey: ByteArray,
) {
    @Volatile var localTrusted = false
    @Volatile var peerVerified = false
    val verification = CompletableDeferred<Boolean>()
}

private class Incoming(val rowId: Long, val file: File, val out: OutputStream, val size: Long) {
    var received = 0L
}

class LinkManager(val ctx: Context, val repo: Repo, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity by lazy { KeystoreIdentity() }

    val myFingerprint: String by lazy { CryptoUtil.fingerprint(identity.publicKey) }

    val wifi = WifiTransport(ctx, ::onRaw)
    val bt = BluetoothTransport(ctx, ::onRaw)

    val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val call = MutableStateFlow<CallState>(CallState.None)
    val pttLocal = MutableStateFlow(false)
    val pttRemote = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    /** Folyamatban lévő átvitelek előrehaladása (üzenet id -> 0..1). */
    val progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    /** Az éppen megnyitott beszélgetés partnere (nem kell értesítés). */
    @Volatile var openChat: String? = null
    @Volatile private var appVisible = false

    private val busy = AtomicBoolean(false)
    @Volatile private var session: Session? = null
    private val incoming = HashMap<Long, Incoming>()
    private val capture = AudioCapture { frame -> trySend(T.AUDIO, frame) }
    private val player = AudioPlayer()
    private var ringtone: Ringtone? = null
    private var callTimeout: Job? = null
    private var callStart = 0L
    private var callOutgoing = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { appVisible = true }
            override fun onStop(owner: LifecycleOwner) { appVisible = false }
        })
        scope.launch { repo.failPending() }
        scope.launch {
            combine(state, call, pttLocal, pttRemote, prefs.backgroundListen) { s, c, pl, pr, bg ->
                Triple(s, c is CallState.Active || pl || pr, bg)
            }.collect { (s, mic, bg) -> updateService(s, mic, bg) }
        }
        scope.launch { call.collect { handleRinging(it) } }
    }

    // ---------------------------------------------------------------- kapcsolatok

    fun startListening() {
        wifi.startServer(prefs.deviceName.value)
        bt.startServer()
    }

    fun connectWifi(host: String, port: Int = WifiTransport.PORT) = connectWith("Wi‑Fi: $host") { wifi.connect(host, port) }

    fun connectBluetooth(address: String, name: String) = connectWith("Bluetooth: $name") { bt.connect(address) }

    private fun connectWith(desc: String, open: () -> RawLink) {
        if (!busy.compareAndSet(false, true)) return
        state.value = LinkState.Connecting(desc)
        scope.launch {
            val link = try { open() } catch (e: Exception) {
                _errors.tryEmit("Sikertelen kapcsolódás: ${e.message ?: e.javaClass.simpleName}")
                state.value = LinkState.Idle; busy.set(false)
                return@launch
            }
            runSession(link, true)
        }
    }

    private fun onRaw(link: RawLink, initiator: Boolean) {
        if (!busy.compareAndSet(false, true)) { link.close(); return }
        state.value = LinkState.Connecting("${link.transport}: ${link.description}")
        scope.launch { runSession(link, initiator) }
    }

    private suspend fun runSession(link: RawLink, initiator: Boolean) {
        var s: Session? = null
        try {
            val ch = SecureChannel(link.input, link.output, identity)
            val hs = ch.handshake(initiator)
            val fp = CryptoUtil.fingerprint(hs.peerIdentity)
            ch.send(T.PROFILE, prefs.deviceName.value.toByteArray())
            val prof = ch.receive()
            if (prof.type != T.PROFILE) throw IOException("Váratlan keret")
            val name = String(prof.body).trim().take(40).ifBlank { "Ismeretlen" }
            s = Session(ch, link, fp, name, hs.peerIdentity)
            session = s
            val known = repo.peerOnce(fp)
            val reader = scope.launch { readLoop(s, link.transport) }

            if (known != null && known.identityKey.contentEquals(hs.peerIdentity)) {
                repo.savePeer(known.copy(name = name, lastSeen = System.currentTimeMillis()))
            } else {
                state.value = LinkState.Verifying(name, fp, hs.sas, false)
                if (!s.verification.await()) { runCatching { ch.send(T.BYE) }; throw IOException("Elutasítva") }
                val now = System.currentTimeMillis()
                repo.savePeer(PeerEntity(fp, name, hs.peerIdentity, now, now))
            }
            s.localTrusted = true
            ch.send(T.VERIFIED)
            refreshConnected(s, hs.sas)
            reader.join()
        } catch (e: Exception) {
            if (session != null || state.value is LinkState.Connecting) {
                _errors.tryEmit(e.message?.takeIf { it.isNotBlank() } ?: "A kapcsolat megszakadt")
            }
        } finally {
            cleanup(s, link)
        }
    }

    private fun refreshConnected(s: Session, sas: String? = null) {
        if (s.localTrusted && s.peerVerified) {
            state.value = LinkState.Connected(s.fp, s.peerName, s.link.transport)
        } else if (s.localTrusted) {
            val cur = state.value
            if (cur is LinkState.Verifying) state.value = cur.copy(waitingPeer = true)
        }
    }

    /** A felhasználó döntése az ellenőrző kódról. */
    fun confirmVerification(ok: Boolean) { session?.verification?.complete(ok) }

    fun disconnect() {
        val s = session
        if (s != null) {
            scope.launch { runCatching { s.channel.send(T.BYE) }; s.link.close() }
        } else {
            busy.set(false); state.value = LinkState.Idle
        }
    }

    private fun cleanup(s: Session?, link: RawLink) {
        link.close()
        stopAudio()
        endRingtone()
        s?.verification?.complete(false)
        if (call.value is CallState.Active) logCall(s?.fp, "Hívás megszakadt", callOutgoing)
        call.value = CallState.None
        pttLocal.value = false; pttRemote.value = false
        synchronized(incoming) {
            incoming.values.forEach { runCatching { it.out.close() }; scope.launch { repo.setStatus(it.rowId, Status.FAILED, it.received) } }
            incoming.clear()
        }
        scope.launch { repo.failPending() }
        progress.value = emptyMap()
        session = null
        state.value = LinkState.Idle
        busy.set(false)
    }

    private fun trySend(type: Int, body: ByteArray = ByteArray(0)): Boolean {
        val s = session ?: return false
        return try { s.channel.send(type, body); true } catch (e: Exception) { s.link.close(); false }
    }

    // ---------------------------------------------------------------- fogadás

    private fun readLoop(s: Session, transport: String) {
        try {
            while (true) {
                val f = s.channel.receive()
                if (!s.localTrusted && f.type != T.VERIFIED && f.type != T.BYE) continue
                handle(s, f)
                if (f.type == T.BYE) break
            }
        } catch (_: Exception) {
        } finally {
            s.verification.complete(false)
            s.link.close()
        }
    }

    private fun handle(s: Session, f: Frame) {
        when (f.type) {
            T.VERIFIED -> { s.peerVerified = true; refreshConnected(s) }
            T.BYE -> s.link.close()
            T.TEXT -> if (state.value is LinkState.Connected) {
                val text = String(f.body)
                scope.launch {
                    repo.addMessage(s.fp, false, Kind.TEXT, text = text, unread = openChat != s.fp)
                    notifyMessage(s, text)
                }
            }
            T.FILE_META -> onFileMeta(s, f.body)
            T.FILE_DATA -> onFileData(f.body)
            T.FILE_END -> onFileEnd(s, DataInputStream(f.body.inputStream()).readLong())
            T.CALL_REQ -> onCallRequest(s)
            T.CALL_ACCEPT -> if (call.value is CallState.Outgoing) {
                callTimeout?.cancel()
                beginCall(s.peerName, outgoing = true)
            }
            T.CALL_REJECT -> if (call.value is CallState.Outgoing) {
                callTimeout?.cancel(); call.value = CallState.None
                logCall(s.fp, "Hívás elutasítva", true)
            }
            T.CALL_END -> {
                val c = call.value
                if (c is CallState.Incoming) logCall(s.fp, "Nem fogadott hívás", false, missed = true)
                else if (c is CallState.Active) logCall(s.fp, "Hívás", callOutgoing, c.since)
                else if (c is CallState.Outgoing) logCall(s.fp, "Hívás elutasítva", true)
                callTimeout?.cancel(); stopAudio(); call.value = CallState.None
            }
            T.AUDIO -> if (call.value is CallState.Active || pttRemote.value) player.play(f.body)
            T.PTT_START -> if (call.value is CallState.None && !pttLocal.value) {
                pttRemote.value = true
                CallAudio.enter(ctx, true); player.start()
            }
            T.PTT_END -> if (pttRemote.value) {
                pttRemote.value = false; player.stop(); CallAudio.leave(ctx)
            }
        }
    }

    // ---------------------------------------------------------------- szöveg / fájl küldés

    fun sendText(text: String) {
        val s = session ?: return
        val t = text.trim()
        if (t.isEmpty() || state.value !is LinkState.Connected) return
        scope.launch {
            val id = repo.addMessage(s.fp, true, Kind.TEXT, text = t, status = Status.IN_PROGRESS)
            val ok = trySend(T.TEXT, t.toByteArray())
            repo.setStatus(id, if (ok) Status.DONE else Status.FAILED, 0)
        }
    }

    fun sendUri(uri: Uri) {
        val s = session ?: return
        if (state.value !is LinkState.Connected) return
        scope.launch {
            try {
                var name = "fajl"
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { name = it }
                    }
                }
                val mime = ctx.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val dest = File(repo.mediaDir, "out_${System.currentTimeMillis()}_${safeName(name)}")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                val kind = when {
                    mime.startsWith("image/") -> Kind.IMAGE
                    mime.startsWith("video/") -> Kind.VIDEO
                    else -> Kind.FILE
                }
                transfer(s, dest, name, mime, kind, 0)
            } catch (e: Exception) {
                _errors.tryEmit("A fájl nem küldhető: ${e.message}")
            }
        }
    }

    /** Hangüzenet küldése (a fájl a gyorsítótárból átkerül az alkalmazás mappájába). */
    fun sendVoice(file: File, durationMs: Int) {
        val s = session
        if (s == null || state.value !is LinkState.Connected) { file.delete(); return }
        scope.launch {
            val dest = File(repo.mediaDir, "out_${System.currentTimeMillis()}_voice.m4a")
            if (!file.renameTo(dest)) { file.copyTo(dest, true); file.delete() }
            transfer(s, dest, "hangüzenet.m4a", "audio/mp4", Kind.VOICE, durationMs)
        }
    }

    private suspend fun transfer(s: Session, file: File, name: String, mime: String, kind: Int, durationMs: Int) {
        val id = repo.addMessage(
            s.fp, true, kind, fileName = name, mime = mime, size = file.length(), path = file.absolutePath,
            durationMs = durationMs, status = Status.IN_PROGRESS,
        )
        var ok = true
        try {
            val meta = ByteArrayOutputStream()
            DataOutputStream(meta).run {
                writeLong(id); writeByte(kind); writeUTF(name); writeUTF(mime); writeLong(file.length()); writeInt(durationMs)
            }
            if (!trySend(T.FILE_META, meta.toByteArray())) throw IOException("Megszakadt a kapcsolat")
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val body = ByteArray(8 + n)
                    java.nio.ByteBuffer.wrap(body).putLong(id)
                    System.arraycopy(buf, 0, body, 8, n)
                    if (!trySend(T.FILE_DATA, body)) throw IOException("Megszakadt a kapcsolat")
                    sent += n
                    setProgress(id, sent.toFloat() / total)
                }
            }
            if (!trySend(T.FILE_END, java.nio.ByteBuffer.allocate(8).putLong(id).array())) throw IOException("Megszakadt a kapcsolat")
        } catch (e: Exception) {
            ok = false
        }
        setProgress(id, null)
        repo.setStatus(id, if (ok) Status.DONE else Status.FAILED, file.length())
    }

    private var lastProgressPush = 0L
    private fun setProgress(id: Long, v: Float?) {
        val now = System.currentTimeMillis()
        if (v != null && v < 1f && now - lastProgressPush < 120) return
        lastProgressPush = now
        progress.value = if (v == null) progress.value - id else progress.value + (id to v)
    }

    private fun onFileMeta(s: Session, body: ByteArray) {
        if (state.value !is LinkState.Connected) return
        val d = DataInputStream(body.inputStream())
        val tid = d.readLong(); val kind = d.readUnsignedByte(); val name = d.readUTF(); val mime = d.readUTF()
        val size = d.readLong(); val dur = d.readInt()
        val k = if (kind in Kind.FILE..Kind.VOICE) kind else Kind.FILE
        val file = File(repo.mediaDir, "in_${System.currentTimeMillis()}_${safeName(name)}")
        val out = FileOutputStream(file)
        val rowId = kotlinx.coroutines.runBlocking {
            repo.addMessage(s.fp, false, k, fileName = name, mime = mime, size = size, path = file.absolutePath,
                durationMs = dur, status = Status.IN_PROGRESS, unread = openChat != s.fp)
        }
        synchronized(incoming) { incoming[tid] = Incoming(rowId, file, out, size) }
    }

    private fun onFileData(body: ByteArray) {
        if (body.size < 8) return
        val tid = java.nio.ByteBuffer.wrap(body).long
        val inc = synchronized(incoming) { incoming[tid] } ?: return
        inc.out.write(body, 8, body.size - 8)
        inc.received += body.size - 8
        setProgress(inc.rowId, inc.received.toFloat() / inc.size.coerceAtLeast(1))
    }

    private fun onFileEnd(s: Session, tid: Long) {
        val inc = synchronized(incoming) { incoming.remove(tid) } ?: return
        runCatching { inc.out.close() }
        setProgress(inc.rowId, null)
        scope.launch {
            repo.setStatus(inc.rowId, Status.DONE, inc.file.length())
            notifyMessage(s, "📎 ${inc.file.name.substringAfter('_').substringAfter('_')}")
        }
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)

    // ---------------------------------------------------------------- hívás / walkie-talkie

    fun startCall() {
        val s = session ?: return
        if (state.value !is LinkState.Connected || call.value !is CallState.None || pttRemote.value) return
        call.value = CallState.Outgoing(s.peerName)
        trySend(T.CALL_REQ)
        callTimeout = scope.launch {
            delay(45_000)
            if (call.value is CallState.Outgoing) { trySend(T.CALL_END); call.value = CallState.None; logCall(s.fp, "Nem fogadott hívás", true, missed = true) }
        }
    }

    private fun onCallRequest(s: Session) {
        if (state.value !is LinkState.Connected || call.value !is CallState.None || pttLocal.value) { trySend(T.CALL_REJECT); return }
        if (pttRemote.value) { pttRemote.value = false; player.stop() }
        call.value = CallState.Incoming(s.peerName)
        notifyCall(s)
    }

    fun acceptCall() {
        val s = session ?: return
        if (call.value !is CallState.Incoming) return
        trySend(T.CALL_ACCEPT)
        beginCall(s.peerName, outgoing = false)
    }

    fun rejectCall() {
        val s = session ?: return
        if (call.value is CallState.Incoming) {
            trySend(T.CALL_REJECT); call.value = CallState.None
            logCall(s.fp, "Elutasított hívás", false)
        }
    }

    fun endCall() {
        val s = session ?: return
        val c = call.value
        if (c is CallState.None) return
        trySend(T.CALL_END)
        callTimeout?.cancel()
        if (c is CallState.Active) logCall(s.fp, "Hívás", callOutgoing, c.since)
        else if (c is CallState.Outgoing) logCall(s.fp, "Nem fogadott hívás", true, missed = true)
        stopAudio(); call.value = CallState.None
    }

    private fun beginCall(peerName: String, outgoing: Boolean) {
        callOutgoing = outgoing
        callStart = System.currentTimeMillis()
        muted.value = false
        CallAudio.enter(ctx, speaker.value)
        player.start()
        capture.muted = false
        capture.start()
        call.value = CallState.Active(peerName, callStart)
    }

    fun setMuted(m: Boolean) { muted.value = m; capture.muted = m }
    fun setSpeaker(on: Boolean) { speaker.value = on; CallAudio.setSpeaker(ctx, on) }

    private fun stopAudio() {
        capture.stop(); player.stop()
        CallAudio.leave(ctx)
    }

    private fun logCall(peer: String?, label: String, outgoing: Boolean, since: Long = 0, missed: Boolean = false) {
        if (peer == null) return
        val text = if (since > 0) {
            val sec = ((System.currentTimeMillis() - since) / 1000).toInt()
            "$label · %d:%02d".format(sec / 60, sec % 60)
        } else label
        scope.launch { repo.addMessage(peer, outgoing, Kind.CALL, text = text, unread = missed) }
    }

    /** Walkie Talkie: nyomva tartva beszél. */
    fun pttStart(): Boolean {
        if (state.value !is LinkState.Connected || call.value !is CallState.None || pttRemote.value || pttLocal.value) return false
        if (!capture.start()) { _errors.tryEmit("A mikrofon nem érhető el"); return false }
        capture.muted = false
        pttLocal.value = true
        trySend(T.PTT_START)
        return true
    }

    fun pttStop() {
        if (!pttLocal.value) return
        pttLocal.value = false
        capture.stop()
        trySend(T.PTT_END)
    }

    private fun handleRinging(c: CallState) {
        if (c is CallState.Incoming) {
            try {
                val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                r.play(); ringtone = r
            } catch (_: Exception) {}
        } else endRingtone()
    }

    private fun endRingtone() { runCatching { ringtone?.stop() }; ringtone = null }

    // ---------------------------------------------------------------- értesítések / szolgáltatás

    private fun openIntent(peer: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peer != null) putExtra(MainActivity.EXTRA_PEER, peer)
        }
        return PendingIntent.getActivity(ctx, peer.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notifyMessage(s: Session, text: String) {
        if (appVisible && openChat == s.fp || !canNotify()) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(s.peerName).setContentText(text)
            .setAutoCancel(true).setContentIntent(openIntent(s.fp)).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(s.fp.hashCode(), n)
    }

    private fun notifyCall(s: Session) {
        if (appVisible || !canNotify()) return
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Bejövő hívás").setContentText(s.peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(openIntent(s.fp)).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(CALL_NOTIF, n)
    }

    private fun updateService(s: LinkState, mic: Boolean, bgListen: Boolean) {
        val connected = s is LinkState.Connected
        if (!connected && !bgListen) { LinkService.stop(ctx); return }
        val text = when (s) {
            is LinkState.Connected -> "Kapcsolódva: ${s.peerName} (${s.transport}, titkosítva)"
            is LinkState.Connecting, is LinkState.Verifying -> "Kapcsolódás…"
            LinkState.Idle -> "Kész a kapcsolódásra"
        }
        LinkService.update(ctx, text, mic, connected, openIntent(null))
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MESSAGES = "messages"
        const val CH_CALLS = "calls"
        private const val CALL_NOTIF = 4242

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Kapcsolat", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Üzenetek", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Hívások", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
