package hu.ando.talkie.net

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Előtérben futó szolgáltatás: életben tartja a kapcsolatot, amíg a képernyő ki van kapcsolva. */
class LinkService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Ando Talkie"
        val mic = intent?.getBooleanExtra("mic", false) ?: false
        val connected = intent?.getBooleanExtra("connected", false) ?: false
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, hu.ando.talkie.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, LinkManager.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ando Talkie").setContentText(text)
            .setOngoing(true).setContentIntent(open).build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            // A mikrofon típus nem indítható (pl. nincs engedély) – csak az eszközkapcsolat típussal próbáljuk.
            try {
                ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            } catch (e2: Exception) { stopSelf() }
        }
        if (connected) {
            if (wake == null) {
                wake = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ando:link").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
        } else releaseWake()
        return START_NOT_STICKY
    }

    private fun releaseWake() { runCatching { wake?.release() }; wake = null }

    override fun onDestroy() { releaseWake(); super.onDestroy() }

    companion object {
        fun update(ctx: Context, text: String, mic: Boolean, connected: Boolean, @Suppress("UNUSED_PARAMETER") open: PendingIntent) {
            val i = Intent(ctx, LinkService::class.java).putExtra("text", text).putExtra("mic", mic).putExtra("connected", connected)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, LinkService::class.java)) } catch (_: Exception) {} }
    }
}
