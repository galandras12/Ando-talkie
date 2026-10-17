package hu.ando.talkie.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import hu.ando.talkie.TalkieApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

val LocalApp = staticCompositionLocalOf<TalkieApp> { error("no app") }

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun wifiPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyArray()

fun hasPermissions(ctx: Context, perms: Array<String>) =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

fun hasMic(ctx: Context) = hasPermissions(ctx, arrayOf(Manifest.permission.RECORD_AUDIO))

/** Engedélykérő: a visszaadott függvény meghívásakor kéri az engedélyeket, majd a [onResult]-ot hívja. */
@Composable
fun rememberPermissionAsker(perms: Array<String>, onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        onResult(r.values.all { it })
    }
    return remember(perms.toList()) { { if (perms.isEmpty()) onResult(true) else launcher.launch(perms) } }
}

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun formatDuration(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

fun formatTime(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
fun formatDay(ts: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))

fun sameDay(a: Long, b: Long): Boolean {
    val c1 = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val c2 = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR) &&
        c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR)
}

fun openFile(ctx: Context, path: String, mime: String?) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nincs alkalmazás a fájl megnyitásához", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "A fájl nem nyitható meg", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberImage(path: String?, kindVideo: Boolean, maxPx: Int = 640): State<Bitmap?> =
    produceState<Bitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try {
                if (kindVideo) {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(path); r.getFrameAtTime(0) } finally { r.release() }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, o)
                    var s = 1
                    while (o.outWidth / s > maxPx * 2 || o.outHeight / s > maxPx * 2) s *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                }
            } catch (e: Exception) { null }
        }
    }

/** Hangüzenetek lejátszása (egyszerre egy). */
class VoicePlayer {
    val playingId = MutableStateFlow<Long?>(null)
    private var mp: MediaPlayer? = null

    fun toggle(id: Long, path: String) {
        val wasPlaying = playingId.value == id
        stop()
        if (wasPlaying) return
        try {
            mp = MediaPlayer().apply {
                setDataSource(path); prepare()
                setOnCompletionListener { stop() }
                start()
            }
            playingId.value = id
        } catch (e: Exception) { stop() }
    }

    fun stop() {
        runCatching { mp?.stop() }; runCatching { mp?.release() }
        mp = null; playingId.value = null
    }
}

@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val p = remember { VoicePlayer() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { p.stop() } }
    return p
}

@Composable
fun app(): TalkieApp = LocalApp.current

@Composable
fun ctx(): Context = LocalContext.current
