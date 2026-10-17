package hu.ando.talkie

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hu.ando.talkie.ui.AndoTheme
import hu.ando.talkie.ui.LocalApp
import hu.ando.talkie.ui.LockScreen
import hu.ando.talkie.ui.MainNav

class MainActivity : AppCompatActivity() {
    private var locked by mutableStateOf(false)
    private var leftAt = 0L
    private var pendingPeer by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TalkieApp
        locked = app.prefs.lockEnabled.value
        pendingPeer = intent?.getStringExtra(EXTRA_PEER)
        app.link.startListening()

        setContent {
            val mode by app.prefs.themeMode.collectAsState()
            val dynamic by app.prefs.dynamicColor.collectAsState()
            val secure by app.prefs.blockScreenshots.collectAsState()
            val lockEnabled by app.prefs.lockEnabled.collectAsState()
            LaunchedEffect(secure) {
                if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            LaunchedEffect(lockEnabled) { if (!lockEnabled) locked = false }
            CompositionLocalProvider(LocalApp provides app) {
                AndoTheme(mode, dynamic) {
                    if (locked && lockEnabled) {
                        LockScreen(onUnlock = { locked = false })
                    } else {
                        MainNav(pendingPeer = pendingPeer, onPeerHandled = { pendingPeer = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingPeer = intent.getStringExtra(EXTRA_PEER)
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val prefs = (application as TalkieApp).prefs
        if (prefs.lockEnabled.value && leftAt != 0L &&
            SystemClock.elapsedRealtime() - leftAt >= prefs.lockDelaySec.value * 1000L
        ) locked = true
    }

    companion object { const val EXTRA_PEER = "peer" }
}
