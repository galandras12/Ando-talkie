package hu.ando.talkie.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import hu.ando.talkie.BuildConfig
import java.io.File

/** Az éppen telepített (release) APK offline másolata – megosztható Bluetooth-on, Quick Share-en vagy bármilyen más módon. */
object ApkShare {
    const val MIME = "application/vnd.android.package-archive"

    fun fileName() = "AndoTalkie-v${BuildConfig.VERSION_NAME}-release.apk"

    /** Elkészíti / frissíti a megosztható másolatot a telepített APK-ból. */
    fun prepare(ctx: Context): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".apk") && it.name != fileName() }?.forEach { it.delete() }
        val src = File(ctx.applicationInfo.sourceDir)
        val dst = File(dir, fileName())
        if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
        return dst
    }

    fun size(ctx: Context): Long = File(ctx.applicationInfo.sourceDir).length()

    fun share(ctx: Context) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", prepare(ctx))
        val send = Intent(Intent.ACTION_SEND).setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Ando Talkie ${BuildConfig.VERSION_NAME}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Ando Talkie APK megosztása").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveTo(ctx: Context, uri: android.net.Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { o -> prepare(ctx).inputStream().use { it.copyTo(o) } }
    }
}
