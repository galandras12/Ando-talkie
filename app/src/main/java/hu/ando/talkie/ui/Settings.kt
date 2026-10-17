package hu.ando.talkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import hu.ando.talkie.BuildConfig
import hu.ando.talkie.core.CryptoUtil
import hu.ando.talkie.core.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val a = app()
    val ctx = LocalContext.current
    val p = a.prefs
    val scope = rememberCoroutineScope()
    val mode by p.themeMode.collectAsState()
    val dynamic by p.dynamicColor.collectAsState()
    val name by p.deviceName.collectAsState()
    val lock by p.lockEnabled.collectAsState()
    val bio by p.biometric.collectAsState()
    val delay by p.lockDelaySec.collectAsState()
    val secure by p.blockScreenshots.collectAsState()
    val bg by p.backgroundListen.collectAsState()
    val peers by a.repo.peers.collectAsState(emptyList())
    var editName by remember { mutableStateOf(false) }
    var setPin by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<String?>(null) }

    val apkSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ApkShare.MIME)
    ) { uri ->
        if (uri != null) Toast.makeText(
            ctx, if (runCatching { ApkShare.saveTo(ctx, uri) }.isSuccess) "Elmentve" else "Mentés sikertelen", Toast.LENGTH_SHORT,
        ).show()
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Megjelenés")
        listOf(ThemeMode.SYSTEM to "Rendszer témáját követi", ThemeMode.LIGHT to "Világos", ThemeMode.DARK to "Sötét").forEach { (m, label) ->
            ListItem(
                modifier = Modifier.clickable { p.setThemeMode(m) },
                leadingContent = { RadioButton(mode == m, { p.setThemeMode(m) }) },
                headlineContent = { Text(label) },
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) ListItem(
            headlineContent = { Text("Dinamikus színek (Material You)") },
            supportingContent = { Text("A háttérkép színeit használja") },
            trailingContent = { Switch(dynamic, { p.setDynamicColor(it) }) },
        )
        HorizontalDivider()

        Section("Eszköz")
        ListItem(
            modifier = Modifier.clickable { editName = true },
            headlineContent = { Text("Eszköz neve") }, supportingContent = { Text(name) },
        )
        ListItem(
            headlineContent = { Text("Háttérben is fogadjon") },
            supportingContent = { Text("Állandó értesítéssel fut, így zárolt képernyő mellett is jöhet kapcsolat, üzenet, hívás") },
            trailingContent = { Switch(bg, { p.setBackgroundListen(it) }) },
        )
        HorizontalDivider()

        Section("Biztonság")
        ListItem(
            headlineContent = { Text("Alkalmazászár") },
            supportingContent = { Text("PIN kód és/vagy ujjlenyomat a megnyitáshoz") },
            trailingContent = { Switch(lock, { if (it) setPin = true else confirmOff = true }) },
        )
        if (lock) {
            if (biometricAvailable(ctx)) ListItem(
                headlineContent = { Text("Ujjlenyomat használata") },
                trailingContent = { Switch(bio, { on -> if (on) showBiometric(ctx, "Ujjlenyomat engedélyezése") { p.setBiometric(true) } else p.setBiometric(false) }) },
            )
            ListItem(
                headlineContent = { Text("Zárolás késleltetése") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Azonnal", 30 to "30 mp", 60 to "1 perc", 300 to "5 perc").forEach { (s, l) ->
                            FilterChip(delay == s, { p.setLockDelay(s) }, { Text(l) })
                        }
                    }
                },
            )
        }
        ListItem(
            headlineContent = { Text("Képernyőkép tiltása") },
            supportingContent = { Text("Elrejti a tartalmat a nemrég használt alkalmazások között is") },
            trailingContent = { Switch(secure, { p.setBlockScreenshots(it) }) },
        )
        ListItem(
            headlineContent = { Text("Ennek az eszköznek az ujjlenyomata") },
            supportingContent = { Text(CryptoUtil.prettyFingerprint(a.link.myFingerprint)) },
        )
        HorizontalDivider()

        Section("Megbízható eszközök")
        if (peers.isEmpty()) ListItem(headlineContent = { Text("Még nincs") })
        peers.forEach { peer ->
            ListItem(
                modifier = Modifier.clickable { forget = peer.fingerprint },
                headlineContent = { Text(peer.name) },
                supportingContent = { Text(CryptoUtil.prettyFingerprint(peer.fingerprint)) },
                trailingContent = { Text("Törlés", color = MaterialTheme.colorScheme.error) },
            )
        }
        HorizontalDivider()

        Section("Alkalmazás megosztása")
        ListItem(
            modifier = Modifier.clickable { runCatching { ApkShare.share(ctx) }.onFailure { Toast.makeText(ctx, "A megosztás nem sikerült", Toast.LENGTH_SHORT).show() } },
            leadingContent = { Icon(Icons.Default.Share, null) },
            headlineContent = { Text("APK megosztása") },
            supportingContent = { Text("Offline átadás a telepítőfájllal (${formatSize(ApkShare.size(ctx))}) Bluetooth-on, Quick Share-en vagy más módon") },
        )
        ListItem(
            modifier = Modifier.clickable { apkSaver.launch(ApkShare.fileName()) },
            leadingContent = { Icon(Icons.Default.Save, null) },
            headlineContent = { Text("APK mentése fájlba") },
            supportingContent = { Text(ApkShare.fileName()) },
        )
        HorizontalDivider()

        Section("Névjegy")
        ListItem(headlineContent = { Text("Ando Talkie ${BuildConfig.VERSION_NAME}") },
            supportingContent = { Text("Végpontok közötti titkosítás: ECDH P‑256 + ECDSA azonosítás, AES‑256‑GCM. A tárolt üzenetszövegek Android Keystore kulccsal titkosítva vannak.") })
    }

    if (editName) {
        var v by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editName = false }, title = { Text("Eszköz neve") },
            text = { OutlinedTextField(v, { v = it.take(24) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (v.isNotBlank()) { p.setDeviceName(v); a.link.wifi.stopServer(); a.link.startListening() }
                editName = false
            }) { Text("Mentés") } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Mégse") } },
        )
    }
    if (setPin) PinSetupDialog(onDone = { pin -> if (pin != null) p.setPin(pin); setPin = false })
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false }, title = { Text("Alkalmazászár kikapcsolása") },
        text = { Text("Biztosan kikapcsolod a PIN/ujjlenyomat védelmet?") },
        confirmButton = { TextButton(onClick = { p.disableLock(); confirmOff = false }) { Text("Kikapcsolás") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Mégse") } },
    )
    forget?.let { fp ->
        AlertDialog(
            onDismissRequest = { forget = null }, title = { Text("Eszköz eltávolítása") },
            text = { Text("A beszélgetés is törlődik, és legközelebb újra ellenőrizni kell a biztonsági kódot.") },
            confirmButton = { TextButton(onClick = { scope.launch { a.repo.forgetPeer(fp) }; forget = null }) { Text("Eltávolítás") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Mégse") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
}

@Composable
private fun PinSetupDialog(onDone: (String?) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val valid = a.length in 4..8 && a == b
    AlertDialog(
        onDismissRequest = { onDone(null) }, title = { Text("PIN kód beállítása") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4–8 számjegy")
                OutlinedTextField(a, { a = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(b, { b = it.filter(Char::isDigit).take(8) }, label = { Text("PIN újra") }, singleLine = true,
                    isError = b.isNotEmpty() && a != b,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = { TextButton(onClick = { onDone(a) }, enabled = valid) { Text("Beállítás") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Mégse") } },
    )
}
