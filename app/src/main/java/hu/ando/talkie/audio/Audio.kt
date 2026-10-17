package hu.ando.talkie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

const val SAMPLE_RATE = 16000
/** 20 ms-os keret: 320 minta * 2 bájt. */
const val FRAME_BYTES = 640

/** Mikrofon -> 20 ms-os PCM keretek (hívás és Walkie Talkie üzemmód). */
class AudioCapture(private val onFrame: (ByteArray) -> Unit) {
    @Volatile private var running = false
    @Volatile var muted = false
    private var thread: Thread? = null

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 8)
            )
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true } catch (_: Exception) {}
        running = true
        thread = Thread({
            try {
                rec.startRecording()
                val buf = ByteArray(FRAME_BYTES)
                while (running) {
                    var off = 0
                    while (off < FRAME_BYTES && running) {
                        val n = rec.read(buf, off, FRAME_BYTES - off)
                        if (n <= 0) { running = false; break }
                        off += n
                    }
                    if (running && off == FRAME_BYTES) onFrame(if (muted) ByteArray(FRAME_BYTES) else buf.copyOf())
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "audio-capture").apply { isDaemon = true; start() }
        return true
    }

    fun stop() { running = false; thread = null }
}

/** Érkező PCM keretek lejátszása kis késleltetéssel. */
class AudioPlayer {
    private val queue = ArrayBlockingQueue<ByteArray>(40)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        queue.clear()
        thread = Thread({
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                while (running) {
                    val f = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    track.write(f, 0, f.size)
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }, "audio-play").apply { isDaemon = true; start() }
    }

    fun play(frame: ByteArray) {
        if (!running) return
        if (!queue.offer(frame)) { queue.poll(); queue.offer(frame) }
    }

    fun stop() { running = false; thread = null; queue.clear() }
}

/** Hangüzenet felvétele AAC (.m4a) fájlba. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis(); true
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f.delete(); false
        }
    }

    /** Leállít; visszaadja a fájlt és a hosszt, vagy null-t, ha túl rövid / hibás volt. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null
        val f = file
        val dur = (System.currentTimeMillis() - startedAt).toInt()
        rec = null; file = null
        return try {
            r.stop(); r.release()
            if (f != null && dur >= 500) f to dur else { f?.delete(); null }
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f?.delete(); null
        }
    }

    fun cancel() { stop()?.first?.delete() }
}

object CallAudio {
    fun enter(ctx: Context, speaker: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
    }

    fun leave(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }
}
