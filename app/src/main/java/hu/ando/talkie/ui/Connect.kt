package hu.ando.talkie.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.clickable
import hu.ando.talkie.net.LinkState
import hu.ando.talkie.net.WifiTransport

@Composable
fun ConnectScreen(onWizard: () -> Unit, onOpenChat: (String) -> Unit) {
    val link = app().link
    val state by link.state.collectAsState()
    var mode by rememberSaveable { mutableIntStateOf(0) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard(state) { (state as? LinkState.Connected)?.let { onOpenChat(it.peerFp) } } }
        if (state is LinkState.Connected) {
            item { OutlinedButton(onClick = { link.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Kapcsolat bontása") } }
        } else if (state is LinkState.Connecting) {
            item { OutlinedButton(onClick = { link.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Mégse") } }
        } else {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Default.Wifi, null, Modifier.size(18.dp)) }) { Text("Wi‑Fi") }
                    SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Default.Bluetooth, null, Modifier.size(18.dp)) }) { Text("Bluetooth") }
                }
            }
            if (mode == 0) wifiItems() else bluetoothItems()
            item {
                val c = LocalContext.current
                OutlinedButton(onClick = { runCatching { ApkShare.share(c) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("A másik eszköznek nincs meg az app? APK megosztása")
                }
            }
            item {
                Button(onClick = onWizard, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.size(8.dp)); Text("Nem megy? Segítő varázsló")
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.wifiItems() {
    item { WifiSection() }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bluetoothItems() {
    item { BluetoothSection() }
}

@Composable
fun WifiSection() {
    val a = app()
    val ctx = LocalContext.current
    val wifi = a.link.wifi
    val peers by wifi.peers.collectAsState()
    val listening by wifi.listening.collectAsState()
    var perms by remember { mutableStateOf(hasPermissions(ctx, wifiPermissions())) }
    val ask = rememberPermissionAsker(wifiPermissions()) { perms = it; if (it) a.link.startListening() }
    var host by rememberSaveable { mutableStateOf("") }
    val onLan = remember { WifiTransport.isOnLocalNetwork(ctx) }
    val ips = remember { WifiTransport.localAddresses() }

    DisposableEffect(perms) {
        if (perms) wifi.startDiscovery()
        onDispose { wifi.stopDiscovery() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Ez az eszköz: ${a.prefs.deviceName.value}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (!onLan) "⚠ Nem vagy helyi (Wi‑Fi) hálózaton. Csatlakozz ugyanahhoz a Wi‑Fi-hez, mint a másik eszköz."
                    else "Cím: " + ips.joinToString(", ") + " · port ${wifi.localPort}" + if (listening) " · fogadásra kész" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!perms) {
            Text("A közeli eszközök kereséséhez engedély szükséges.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = ask) { Text("Engedély megadása") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Közeli eszközök", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (peers.isEmpty()) Text("Még nem található eszköz. Győződj meg róla, hogy a másik eszközön is meg van nyitva az alkalmazás.", style = MaterialTheme.typography.bodySmall)
        peers.forEach { p ->
            ListItem(
                modifier = Modifier.clickable { a.link.connectWifi(p.host, p.port) },
                leadingContent = { Icon(Icons.Default.Wifi, null) },
                headlineContent = { Text(p.name) },
                supportingContent = { Text("${p.host}:${p.port}") },
            )
        }
        HorizontalDivider()
        Text("Kapcsolódás IP-címmel", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("pl. 192.168.1.23") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { a.link.connectWifi(host) }, enabled = host.isNotBlank()) { Text("Csatlakozás") }
        }
    }
}

@Composable
fun BluetoothSection() {
    val a = app()
    val ctx = LocalContext.current
    val bt = a.link.bt
    val peers by bt.peers.collectAsState()
    val scanning by bt.scanning.collectAsState()
    val listening by bt.listening.collectAsState()
    var perms by remember { mutableStateOf(hasPermissions(ctx, btPermissions())) }
    var enabled by remember { mutableStateOf(bt.enabled) }
    val ask = rememberPermissionAsker(btPermissions()) { perms = it }
    val enableLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { enabled = bt.enabled; if (enabled) a.link.startListening() }

    LaunchedEffect(perms, enabled) { if (perms && enabled) { a.link.startListening(); bt.startDiscovery() } }
    DisposableEffect(Unit) { onDispose { bt.stopDiscovery() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !bt.available -> Text("Ez az eszköz nem támogatja a Bluetooth-t.")
            !perms -> {
                Text("A Bluetooth használatához engedély szükséges.")
                OutlinedButton(onClick = ask) { Text("Engedély megadása") }
            }
            !enabled -> {
                Text("A Bluetooth ki van kapcsolva.")
                OutlinedButton(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Bluetooth bekapcsolása") }
            }
            else -> {
                Text(
                    if (listening) "Fogadásra kész – a másik eszköz megtalálhatja ezt az eszközt." else "Bluetooth kész.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Eszközök", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { bt.startDiscovery() }) { Icon(Icons.Default.Refresh, "Újrakeresés") }
                }
                if (peers.isEmpty()) Text("Nincs találat. A másik eszköz legyen bekapcsolt Bluetooth-szal és nyitott alkalmazással, esetleg előbb párosítsd a rendszerbeállításokban.", style = MaterialTheme.typography.bodySmall)
                peers.sortedByDescending { it.bonded }.forEach { p ->
                    ListItem(
                        modifier = Modifier.clickable { a.link.connectBluetooth(p.address, p.name) },
                        leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(if (p.bonded) "Párosított · ${p.address}" else p.address) },
                    )
                }
            }
        }
    }
}
