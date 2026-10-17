package hu.ando.talkie.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.ando.talkie.net.LinkState

/** Walkie Talkie üzemmód: nyomva tartva beszélsz, elengedve a másik fél szólhat (fél-duplex, titkosított). */
@Composable
fun WalkieTab(onConnect: () -> Unit, onOpenChat: (String) -> Unit) {
    val link = app().link
    val ctx = LocalContext.current
    val state by link.state.collectAsState()
    val talking by link.pttLocal.collectAsState()
    val remote by link.pttRemote.collectAsState()
    val connected = state as? LinkState.Connected
    var micGranted by remember { mutableStateOf(hasMic(ctx)) }
    val askMic = rememberPermissionAsker(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { micGranted = it }

    if (connected == null) {
        EmptyState("Nincs csatlakoztatott eszköz", "A Walkie Talkie használatához előbb kapcsolódj egy másik eszközhöz.") {
            FilledTonalButton(onClick = onConnect) { Text("Kapcsolódás") }
        }
        return
    }

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s",
    )
    val color = when {
        talking -> MaterialTheme.colorScheme.error
        remote -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(connected.peerName, style = MaterialTheme.typography.headlineSmall)
        Text("${connected.transport} · titkosítva", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(220.dp).scale(if (talking || remote) pulse else 1f)
                .pointerInput(micGranted) {
                    detectTapGestures(onPress = {
                        if (!micGranted) { askMic(); return@detectTapGestures }
                        if (link.pttStart()) {
                            try { tryAwaitRelease() } finally { link.pttStop() }
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.fillMaxSize(), shadowElevation = 8.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (remote) Icons.Default.RecordVoiceOver else Icons.Default.Mic, null,
                        Modifier.size(88.dp), tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            when {
                !micGranted -> "Koppints a mikrofon engedélyezéséhez"
                talking -> "Adás… (engedd el a befejezéshez)"
                remote -> "${connected.peerName} beszél…"
                else -> "Nyomd és tartsd a beszélgetéshez"
            },
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onOpenChat(connected.peerFp) }) {
                Icon(Icons.Default.Chat, null); Spacer(Modifier.size(8.dp)); Text("Üzenetek")
            }
            FilledTonalButton(onClick = { if (hasMic(ctx)) link.startCall() else askMic() }) {
                Icon(Icons.Default.Call, null); Spacer(Modifier.size(8.dp)); Text("Hívás")
            }
        }
    }
}
