package hu.ando.talkie.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.ando.talkie.net.LinkState
import hu.ando.talkie.net.WifiTransport

/**
 * Segítő varázsló két eszköz összekapcsolásához, ha az automatikus keresés nem működik.
 * Lépések: mód -> előkészületek ellenőrzése -> szerepválasztás -> csatlakozás -> kód összevetése.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(onClose: () -> Unit) {
    val a = app()
    val ctx = LocalContext.current
    val state by a.link.state.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var wifiMode by rememberSaveable { mutableStateOf(true) }
    var host by rememberSaveable { mutableStateOf("") }

    // Ha a kapcsolat elindult, ugrunk az utolsó lépésre.
    LaunchedEffect(state) { if (state is LinkState.Verifying || state is LinkState.Connected) step = 4 }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Kapcsolódási varázsló") },
            navigationIcon = { IconButton(onClick = { if (step in 1..3) step-- else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vissza") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
            Text("${step + 1}. lépés / 5", style = MaterialTheme.typography.labelMedium)
            when (step) {
                0 -> {
                    Text("Hogyan kapcsolódjon a két eszköz?", style = MaterialTheme.typography.titleLarge)
                    ModeCard(Icons.Default.Wifi, "Wi‑Fi", "Mindkét eszköz ugyanazon a Wi‑Fi hálózaton (vagy az egyik hotspotján) van. Gyorsabb, nagyobb hatótáv.") { wifiMode = true; step = 1 }
                    ModeCard(Icons.Default.Bluetooth, "Bluetooth", "Nincs szükség hálózatra, ~10 m hatótáv. Fájlküldésnél lassabb.") { wifiMode = false; step = 1 }
                }
                1 -> if (wifiMode) WifiChecks(onNext = { step = 2 }) else BtChecks(onNext = { step = 2 })
                2 -> {
                    Text("Melyik eszközön vagy?", style = MaterialTheme.typography.titleLarge)
                    Text("Az egyik eszközön válaszd a „Várakozás”-t, a másikon a „Csatlakozás”-t. Mindkettőn legyen megnyitva ez a varázsló vagy az alkalmazás.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { a.link.startListening(); step = 5 }, modifier = Modifier.fillMaxWidth()) { Text("Várakozás a másik eszközre") }
                    OutlinedButton(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Csatlakozás a másik eszközhöz") }
                }
                3 -> if (wifiMode) WifiConnectStep(host, { host = it }) else BtConnectStep()
                4 -> FinishStep(state, onClose)
                else -> WaitStep(wifiMode, onBack = { step = 2 })
            }
        }
    }
}

@Composable
private fun ModeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, text: String, fixLabel: String? = null, onFix: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok && fixLabel != null) TextButton(onClick = onFix) { Text(fixLabel) }
    }
}

@Composable
private fun WifiChecks(onNext: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val onLan = remember(tick) { WifiTransport.isOnLocalNetwork(ctx) }
    val ips = remember(tick) { WifiTransport.localAddresses() }
    var perms by remember { mutableStateOf(hasPermissions(ctx, wifiPermissions())) }
    val ask = rememberPermissionAsker(wifiPermissions()) { perms = it }

    Text("Wi‑Fi előkészületek", style = MaterialTheme.typography.titleLarge)
    CheckRow(onLan, if (onLan) "Helyi hálózaton vagy (${ips.joinToString()})" else "Nem vagy Wi‑Fi hálózaton. Csatlakozz ugyanahhoz a Wi‑Fi-hez, mint a másik eszköz, vagy kapcsold be az egyik eszközön a hotspotot, a másikkal pedig csatlakozz hozzá.",
        "Wi‑Fi beállítások") { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
    CheckRow(perms, if (perms) "Közeli eszközök engedély megadva" else "Engedély szükséges a közeli eszközök kereséséhez", "Megadás", ask)
    Text("Tipp: egyes vendég/nyilvános Wi‑Fi hálózatok (AP isolation) tiltják az eszközök közötti forgalmat. Ilyenkor használj hotspotot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { tick++ }) { Text("Újraellenőrzés") }
        Button(onClick = onNext, enabled = onLan && perms) { Text("Tovább") }
    }
}

@Composable
private fun BtChecks(onNext: () -> Unit) {
    val a = app()
    val ctx = LocalContext.current
    val bt = a.link.bt
    var perms by remember { mutableStateOf(hasPermissions(ctx, btPermissions())) }
    var enabled by remember { mutableStateOf(bt.enabled) }
    val ask = rememberPermissionAsker(btPermissions()) { perms = it; if (it) a.link.startListening() }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enabled = bt.enabled; a.link.startListening() }

    Text("Bluetooth előkészületek", style = MaterialTheme.typography.titleLarge)
    CheckRow(bt.available, "Az eszköz támogatja a Bluetooth-t")
    CheckRow(perms, if (perms) "Bluetooth engedély megadva" else "Bluetooth engedély szükséges", "Megadás", ask)
    CheckRow(enabled, if (enabled) "A Bluetooth be van kapcsolva" else "A Bluetooth ki van kapcsolva", "Bekapcsolás") {
        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
    Text("Tipp: ha nem látod a másik eszközt, párosítsd előbb a rendszer Bluetooth-beállításaiban, majd térj vissza ide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Rendszerbeállítások") }
        Button(onClick = onNext, enabled = bt.available && perms && enabled) { Text("Tovább") }
    }
}

@Composable
private fun WaitStep(wifi: Boolean, onBack: () -> Unit) {
    val a = app()
    val ips = remember { WifiTransport.localAddresses() }
    Text("Várakozás a másik eszközre", style = MaterialTheme.typography.titleLarge)
    CircularProgressIndicator()
    Text("A másik eszközön válaszd a „Csatlakozás a másik eszközhöz” lehetőséget, és keresd meg ezt az eszközt: „${a.prefs.deviceName.value}”.")
    if (wifi) {
        Text("Ha nem találja, adja meg ezt a címet: ${ips.joinToString().ifBlank { "(nincs hálózati cím)" }} (port ${WifiTransport.PORT})", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Bluetooth: a másik eszköz a keresésben látni fogja ezt az eszközt.", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(onClick = onBack) { Text("Vissza") }
}

@Composable
private fun WifiConnectStep(host: String, onHost: (String) -> Unit) {
    val a = app()
    val ctx = LocalContext.current
    val peers by a.link.wifi.peers.collectAsState()
    val state by a.link.state.collectAsState()
    DisposableEffect(Unit) { a.link.wifi.startDiscovery(); onDispose { a.link.wifi.stopDiscovery() } }
    Text("Csatlakozás", style = MaterialTheme.typography.titleLarge)
    if (state is LinkState.Connecting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text((state as LinkState.Connecting).description) }
    if (peers.isEmpty()) Text("Keresés… (a másik eszközön a „Várakozás” legyen kiválasztva)")
    peers.forEach { p ->
        ListItem(modifier = Modifier.clickable { a.link.connectWifi(p.host, p.port) },
            leadingContent = { Icon(Icons.Default.Wifi, null) }, headlineContent = { Text(p.name) }, supportingContent = { Text("${p.host}:${p.port}") })
    }
    Text("Ha nem jelenik meg, írd be a másik eszköz IP-címét (azon a „Várakozás” képernyőn látszik):", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(host, { onHost(it.trim()) }, label = { Text("IP-cím, pl. 192.168.1.23") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { a.link.connectWifi(host) }, enabled = host.isNotBlank() && state !is LinkState.Connecting) { Text("Csatlakozás") }
}

@Composable
private fun BtConnectStep() {
    val a = app()
    val bt = a.link.bt
    val peers by bt.peers.collectAsState()
    val scanning by bt.scanning.collectAsState()
    val state by a.link.state.collectAsState()
    LaunchedEffect(Unit) { bt.startDiscovery() }
    DisposableEffect(Unit) { onDispose { bt.stopDiscovery() } }
    Text("Csatlakozás", style = MaterialTheme.typography.titleLarge)
    if (state is LinkState.Connecting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text((state as LinkState.Connecting).description) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(if (scanning) "Keresés…" else "Keresés kész")
        OutlinedButton(onClick = { bt.startDiscovery() }) { Text("Újrakeresés") }
    }
    peers.sortedByDescending { it.bonded }.forEach { p ->
        ListItem(modifier = Modifier.clickable { a.link.connectBluetooth(p.address, p.name) },
            leadingContent = { Icon(Icons.Default.Bluetooth, null) }, headlineContent = { Text(p.name) },
            supportingContent = { Text(if (p.bonded) "Párosított" else p.address) })
    }
}

@Composable
private fun FinishStep(state: LinkState, onClose: () -> Unit) {
    when (state) {
        is LinkState.Verifying -> {
            Text("Biztonsági kód", style = MaterialTheme.typography.titleLarge)
            Text("Hasonlítsd össze a felugró ablakban látható kódot a másik eszközön láthatóval, majd erősítsd meg.")
            CircularProgressIndicator()
        }
        is LinkState.Connected -> {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Összekapcsolva: ${state.peerName}", style = MaterialTheme.typography.titleLarge)
            Text("A kapcsolat végpontok között titkosított.")
            Button(onClick = onClose) { Text("Kész") }
        }
        else -> {
            Text("A kapcsolat megszakadt.", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onClose) { Text("Bezárás") }
        }
    }
}
