package hu.ando.talkie.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.ando.talkie.audio.VoiceRecorder
import hu.ando.talkie.data.Kind
import hu.ando.talkie.data.Msg
import hu.ando.talkie.data.Status
import hu.ando.talkie.net.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(fp: String, onBack: () -> Unit) {
    val a = app()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val peer by a.repo.peer(fp).collectAsState(null)
    val messages by a.repo.messages(fp).collectAsState(emptyList())
    val state by a.link.state.collectAsState()
    val progress by a.link.progress.collectAsState()
    val connected = (state as? LinkState.Connected)?.peerFp == fp
    val voice = rememberVoicePlayer()
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx) }
    var selected by remember { mutableStateOf<Msg?>(null) }
    var toSave by remember { mutableStateOf<Msg?>(null) }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(fp) {
        a.link.openChat = fp
        onDispose { a.link.openChat = null; recorder.cancel() }
    }
    LaunchedEffect(messages.size) {
        a.repo.markRead(fp)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(Unit) { a.link.errors.collect { snack.showSnackbar(it) } }
    LaunchedEffect(recording) { recSeconds = 0; while (recording) { delay(1000); recSeconds++ } }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(a.link::sendUri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(a.link::sendUri) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val m = toSave
        if (uri != null && m?.path != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { o -> File(m.path).inputStream().use { it.copyTo(o) } }
                Toast.makeText(ctx, "Elmentve", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Mentés sikertelen", Toast.LENGTH_SHORT).show() }
        }
        toSave = null
    }
    val askMic = rememberPermissionAsker(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { ok ->
        if (ok) recording = recorder.start() else scope.launch { snack.showSnackbar("Mikrofon engedély szükséges") }
    }
    val askMicCall = rememberPermissionAsker(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { ok -> if (ok) a.link.startCall() }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peer?.name ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (connected) "Kapcsolódva · titkosítva" else "Nincs kapcsolat",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vissza") } },
                actions = {
                    if (connected) IconButton(onClick = { if (hasMic(ctx)) a.link.startCall() else askMicCall() }) { Icon(Icons.Default.Call, "Hívás") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Beszélgetés törlése") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Box(Modifier.navigationBarsPadding().padding(8.dp)) {
                    when {
                        !connected -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Nincs kapcsolat a partnerrel. Az előzmények olvashatók.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onBack) { Text("Kapcsolódás") }
                        }
                        recording -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { recorder.cancel(); recording = false }) { Icon(Icons.Default.Delete, "Elvetés") }
                            Text("● Felvétel ${formatDuration(recSeconds * 1000)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            FilledIconButton(onClick = {
                                recording = false
                                recorder.stop()?.let { (f, d) -> a.link.sendVoice(f, d) }
                            }) { Icon(Icons.AutoMirrored.Filled.Send, "Küldés") }
                        }
                        else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box {
                                IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, "Csatolás") }
                                DropdownMenu(attachMenu, { attachMenu = false }) {
                                    DropdownMenuItem(text = { Text("Kép vagy videó") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                        onClick = { attachMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                                    DropdownMenuItem(text = { Text("Fájl") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                                        onClick = { attachMenu = false; pickFile.launch("*/*") })
                                }
                            }
                            OutlinedTextField(
                                text, { text = it }, Modifier.weight(1f), placeholder = { Text("Üzenet") },
                                maxLines = 4, shape = RoundedCornerShape(24.dp),
                            )
                            if (text.isBlank()) {
                                FilledIconButton(onClick = { if (hasMic(ctx)) recording = recorder.start() else askMic() }) { Icon(Icons.Default.Mic, "Hangüzenet") }
                            } else {
                                FilledIconButton(onClick = { a.link.sendText(text); text = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Küldés") }
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        if (messages.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Még nincs üzenet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(
            Modifier.padding(pad).fillMaxSize(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                val idx = messages.indexOf(m)
                if (idx == 0 || !sameDay(messages[idx - 1].timestamp, m.timestamp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(formatDay(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (m.kind == Kind.CALL) CallChip(m) else Bubble(m, progress[m.id], voice, onLong = { selected = m })
            }
        }
    }

    selected?.let { m ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Bezárás") } },
            text = {
                Column {
                    if (m.kind == Kind.TEXT) TextButton(onClick = { clipboard.setText(AnnotatedString(m.text ?: "")); selected = null }) { Text("Másolás") }
                    if (m.path != null && m.status == Status.DONE) TextButton(onClick = { toSave = m; selected = null; saver.launch(m.fileName ?: "fajl") }) { Text("Mentés fájlba…") }
                    TextButton(onClick = { scope.launch { a.repo.deleteMessage(m.id) }; selected = null }) { Text("Törlés") }
                }
            },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Beszélgetés törlése") },
        text = { Text("Az összes üzenet és fogadott/küldött fájl törlődik erről az eszközről.") },
        confirmButton = { TextButton(onClick = { scope.launch { a.repo.deleteConversation(fp) }; confirmDelete = false }) { Text("Törlés") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } },
    )
}

@Composable
private fun CallChip(m: Msg) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Call, null, Modifier.size(16.dp))
                Text("${m.text} · ${formatTime(m.timestamp)}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Msg, progress: Float?, voice: VoicePlayer, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val out = m.outgoing
    val container = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (out) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val playing by voice.playingId.collectAsState()
    val ready = m.status == Status.DONE && m.path != null

    Box(Modifier.fillMaxWidth(), contentAlignment = if (out) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = container, contentColor = onContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, if (out) 4.dp else 18.dp, if (out) 18.dp else 4.dp),
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).combinedClickable(
                onClick = {
                    if (ready) when (m.kind) {
                        Kind.IMAGE, Kind.VIDEO, Kind.FILE -> openFile(ctx, m.path!!, m.mime)
                        Kind.VOICE -> voice.toggle(m.id, m.path!!)
                    }
                },
                onLongClick = onLong,
            ),
        ) {
            Column(Modifier.padding(10.dp, 8.dp)) {
                when (m.kind) {
                    Kind.TEXT -> Text(m.text ?: "")
                    Kind.IMAGE, Kind.VIDEO -> {
                        val bmp by rememberImage(if (ready || m.kind == Kind.IMAGE && m.outgoing) m.path else null, m.kind == Kind.VIDEO)
                        Box(Modifier.size(220.dp, 160.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            if (m.kind == Kind.VIDEO) Icon(Icons.Default.PlayCircle, null, Modifier.size(48.dp), tint = androidx.compose.ui.graphics.Color.White)
                            else if (bmp == null) Icon(Icons.Default.Image, null)
                        }
                        Text(m.fileName ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Kind.VOICE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (playing == m.id) Icons.Default.Pause else Icons.Default.PlayArrow, "Lejátszás", Modifier.size(32.dp))
                        Column {
                            Text("Hangüzenet")
                            Text(formatDuration(m.durationMs), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(32.dp))
                        Column {
                            Text(m.fileName ?: "fájl", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(m.size), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (m.status == Status.IN_PROGRESS && m.kind != Kind.TEXT) {
                    Spacer(Modifier.height(6.dp))
                    if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.status == Status.FAILED) {
                        Icon(Icons.Default.Error, "Sikertelen", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    }
                    Text(formatTime(m.timestamp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
