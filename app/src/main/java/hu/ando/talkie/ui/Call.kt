package hu.ando.talkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.ando.talkie.net.CallState
import kotlinx.coroutines.delay

/** Teljes képernyős hívásfelület (bejövő, kimenő és aktív hívás). */
@Composable
fun CallOverlay(call: CallState) {
    val link = app().link
    val ctx = LocalContext.current
    val muted by link.muted.collectAsState()
    val speaker by link.speaker.collectAsState()
    val askMic = rememberPermissionAsker(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { ok -> if (ok) link.acceptCall() else link.rejectCall() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    val name = when (call) {
        is CallState.Incoming -> call.peerName
        is CallState.Outgoing -> call.peerName
        is CallState.Active -> call.peerName
        CallState.None -> ""
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 64.dp)) {
                Avatar(name)
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (call) {
                        is CallState.Incoming -> "Bejövő titkosított hívás…"
                        is CallState.Outgoing -> "Hívás…"
                        is CallState.Active -> formatDuration((now - call.since).toInt().coerceAtLeast(0))
                        CallState.None -> ""
                    },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (call) {
                is CallState.Incoming -> Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    FilledIconButton(onClick = { link.rejectCall() }, Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.CallEnd, "Elutasítás", tint = MaterialTheme.colorScheme.onError)
                    }
                    FilledIconButton(onClick = { if (hasMic(ctx)) link.acceptCall() else askMic() }, Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32))) {
                        Icon(Icons.Default.Call, "Fogadás", tint = Color.White)
                    }
                }
                is CallState.Active -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        FilledTonalIconButton(onClick = { link.setMuted(!muted) }, Modifier.size(64.dp)) {
                            Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, "Némítás")
                        }
                        FilledTonalIconButton(onClick = { link.setSpeaker(!speaker) }, Modifier.size(64.dp)) {
                            Icon(if (speaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown, "Hangszóró")
                        }
                    }
                    HangUp()
                }
                else -> HangUp()
            }
        }
    }
}

@Composable
private fun HangUp() {
    val link = app().link
    FilledIconButton(onClick = { link.endCall() }, Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
        Icon(Icons.Default.CallEnd, "Befejezés", tint = MaterialTheme.colorScheme.onError)
    }
}
