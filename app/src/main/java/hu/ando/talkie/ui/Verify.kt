package hu.ando.talkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.ando.talkie.core.CryptoUtil
import hu.ando.talkie.net.LinkState

/** Új partner első kapcsolódásakor: mindkét kijelzőn ugyanannak a 6 számjegynek kell látszania. */
@Composable
fun VerifyDialog(v: LinkState.Verifying) {
    val link = app().link
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.VerifiedUser, null) },
        title = { Text("Biztonsági kód ellenőrzése") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Új eszköz: ${v.peerName}. Hasonlítsd össze ezt a kódot a másik eszközön láthatóval. " +
                        "Csak akkor erősítsd meg, ha a két kód pontosan egyezik.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    v.sas.chunked(3).joinToString("  "),
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ujjlenyomat: " + CryptoUtil.prettyFingerprint(v.fingerprint).take(29) + "…",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (v.waitingPeer) {
                    Spacer(Modifier.height(16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Várakozás a másik eszköz megerősítésére…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (!v.waitingPeer) TextButton(onClick = { link.confirmVerification(true) }) { Text("Egyezik") }
        },
        dismissButton = {
            TextButton(onClick = { if (v.waitingPeer) link.disconnect() else link.confirmVerification(false) }) {
                Text(if (v.waitingPeer) "Mégse" else "Nem egyezik")
            }
        },
    )
}
