package hu.ando.talkie.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.ando.talkie.data.Kind
import hu.ando.talkie.data.Msg
import hu.ando.talkie.data.PeerEntity
import hu.ando.talkie.net.LinkState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onWizard: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val a = app()
    val all by a.repo.allMessages.collectAsState(emptyList())
    val unread = all.count { it.unread }
    val titles = listOf("Beszélgetések", "Walkie Talkie", "Kapcsolódás", "Beállítások")

    Scaffold(
        topBar = { TopAppBar(title = { Text(titles[tab]) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, label = { Text("Chat") },
                    icon = { BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(Icons.Default.Chat, null) } },
                )
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Walkie") }, icon = { Icon(Icons.Default.Mic, null) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Kapcsolat") }, icon = { Icon(Icons.Default.Link, null) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, label = { Text("Beállítások") }, icon = { Icon(Icons.Default.Settings, null) })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatList(all, onOpenChat, onConnect = { tab = 2 })
                1 -> WalkieTab(onConnect = { tab = 2 }, onOpenChat = onOpenChat)
                2 -> ConnectScreen(onWizard = onWizard, onOpenChat = onOpenChat)
                else -> SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(all: List<Msg>, onOpen: (String) -> Unit, onConnect: () -> Unit) {
    val a = app()
    val peers by a.repo.peers.collectAsState(emptyList())
    val state by a.link.state.collectAsState()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<PeerEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        StatusCard(state, onClick = {
            if (state is LinkState.Connected) onOpen((state as LinkState.Connected).peerFp) else onConnect()
        })
        if (peers.isEmpty()) {
            EmptyState("Még nincs beszélgetés", "Kapcsolódj egy közeli eszközhöz Wi‑Fi-n vagy Bluetooth-on, és az eszköz itt jelenik meg.") {
                TextButton(onClick = onConnect) { Text("Kapcsolódás") }
            }
        } else LazyColumn {
            items(peers, key = { it.fingerprint }) { p ->
                val last = all.firstOrNull { it.peer == p.fingerprint }
                val unreadCount = all.count { it.peer == p.fingerprint && it.unread }
                val online = (state as? LinkState.Connected)?.peerFp == p.fingerprint
                ListItem(
                    modifier = Modifier.combinedClickable(onClick = { onOpen(p.fingerprint) }, onLongClick = { deleting = p }),
                    leadingContent = { Avatar(p.name, online) },
                    headlineContent = { Text(p.name, maxLines = 1) },
                    supportingContent = { Text(last?.let(::preview) ?: "Nincs üzenet", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (last != null) Text(formatTime(last.timestamp), style = MaterialTheme.typography.labelSmall)
                            if (unreadCount > 0) Badge { Text("$unreadCount") }
                        }
                    },
                )
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(p.name) },
            text = { Text("Törlöd a beszélgetést? A megbízható eszközök listájából is kikerül, így legközelebb újra ellenőrizni kell.") },
            confirmButton = { TextButton(onClick = { scope.launch { a.repo.forgetPeer(p.fingerprint) }; deleting = null }) { Text("Törlés") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Mégse") } },
        )
    }
}

fun preview(m: Msg): String = when (m.kind) {
    Kind.TEXT -> (if (m.outgoing) "Te: " else "") + (m.text ?: "")
    Kind.IMAGE -> "🖼 Kép"
    Kind.VIDEO -> "🎬 Videó"
    Kind.VOICE -> "🎤 Hangüzenet"
    Kind.CALL -> "📞 ${m.text}"
    else -> "📎 ${m.fileName}"
}

@Composable
fun Avatar(name: String, online: Boolean = false) {
    Box {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (online) Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(12.dp).align(Alignment.BottomEnd),
        ) {}
    }
}

@Composable
fun StatusCard(state: LinkState, onClick: () -> Unit) {
    val (title, sub, container) = when (state) {
        is LinkState.Connected -> Triple("Kapcsolódva: ${state.peerName}", "${state.transport} · végpontok közti titkosítás aktív", MaterialTheme.colorScheme.primaryContainer)
        is LinkState.Connecting -> Triple("Kapcsolódás…", state.description, MaterialTheme.colorScheme.secondaryContainer)
        is LinkState.Verifying -> Triple("Ellenőrzés", "Hasonlítsd össze a biztonsági kódot", MaterialTheme.colorScheme.secondaryContainer)
        LinkState.Idle -> Triple("Nincs kapcsolat", "Koppints a kapcsolódáshoz", MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Card(
        onClick = onClick, colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        action()
    }
}
