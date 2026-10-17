package hu.ando.talkie.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

private fun Context.findActivity(): FragmentActivity? {
    var c = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

fun biometricAvailable(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun showBiometric(ctx: Context, title: String, onSuccess: () -> Unit) {
    val act = ctx.findActivity() ?: return
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("PIN használata")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build()
    )
}

/** Alkalmazászár: PIN billentyűzet + opcionális ujjlenyomat. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val prefs = app().prefs
    val ctx = LocalContext.current
    val useBio by prefs.biometric.collectAsState()
    val bioOk = remember(useBio) { useBio && biometricAvailable(ctx) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var fails by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { if (bioOk) showBiometric(ctx, "Ando Talkie feloldása", onUnlock) }
    LaunchedEffect(lockedUntil) { while (System.currentTimeMillis() < lockedUntil) { now = System.currentTimeMillis(); delay(500) }; now = System.currentTimeMillis() }
    val waiting = lockedUntil > now

    fun press(d: String) {
        if (waiting || pin.length >= 8) return
        pin += d; error = false
    }
    fun submit() {
        if (pin.length < 4 || waiting) return
        if (prefs.checkPin(pin)) onUnlock() else {
            error = true; pin = ""; fails++
            if (fails >= 5) { lockedUntil = System.currentTimeMillis() + 30_000; fails = 0 }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Add meg a PIN kódot", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(20.dp)) {
                repeat(pin.length) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)) {} }
            }
            Text(
                when {
                    waiting -> "Túl sok próbálkozás. Várj ${((lockedUntil - now) / 1000 + 1)} mp-et."
                    error -> "Hibás PIN"
                    else -> " "
                },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp),
            )
            for (row in listOf("123", "456", "789")) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(4.dp)) {
                    row.forEach { c -> Key(c.toString()) { press(c.toString()) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (bioOk) IconButton(onClick = { showBiometric(ctx, "Ando Talkie feloldása", onUnlock) }, Modifier.size(72.dp)) { Icon(Icons.Default.Fingerprint, "Ujjlenyomat", Modifier.size(32.dp)) }
                else Spacer(Modifier.size(72.dp))
                Key("0") { press("0") }
                IconButton(onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }, Modifier.size(72.dp)) { Icon(Icons.AutoMirrored.Filled.Backspace, "Törlés") }
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = ::submit, enabled = pin.length >= 4 && !waiting) { Text("Feloldás") }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.size(72.dp), shape = CircleShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
