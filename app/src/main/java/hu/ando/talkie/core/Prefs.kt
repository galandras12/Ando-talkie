package hu.ando.talkie.core

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object ThemeMode { const val SYSTEM = 0; const val LIGHT = 1; const val DARK = 2 }

/** Beállítások (téma, alkalmazászár). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(sp.getInt("theme", ThemeMode.SYSTEM))
    val themeMode: StateFlow<Int> = _themeMode
    fun setThemeMode(v: Int) { sp.edit().putInt("theme", v).apply(); _themeMode.value = v }

    private val _dynamic = MutableStateFlow(sp.getBoolean("dynamic", Build.VERSION.SDK_INT >= 31))
    val dynamicColor: StateFlow<Boolean> = _dynamic
    fun setDynamicColor(v: Boolean) { sp.edit().putBoolean("dynamic", v).apply(); _dynamic.value = v }

    private val _deviceName = MutableStateFlow(sp.getString("name", null) ?: defaultName())
    val deviceName: StateFlow<String> = _deviceName
    fun setDeviceName(v: String) { sp.edit().putString("name", v.trim()).apply(); _deviceName.value = v.trim() }

    private val _bgListen = MutableStateFlow(sp.getBoolean("bg_listen", true))
    /** Háttérben is várja a bejövő kapcsolatot (állandó értesítéssel). */
    val backgroundListen: StateFlow<Boolean> = _bgListen
    fun setBackgroundListen(v: Boolean) { sp.edit().putBoolean("bg_listen", v).apply(); _bgListen.value = v }

    private val _lockEnabled = MutableStateFlow(sp.getString("pin_hash", null) != null)
    val lockEnabled: StateFlow<Boolean> = _lockEnabled

    private val _biometric = MutableStateFlow(sp.getBoolean("biometric", false))
    val biometric: StateFlow<Boolean> = _biometric
    fun setBiometric(v: Boolean) { sp.edit().putBoolean("biometric", v).apply(); _biometric.value = v }

    private val _lockDelay = MutableStateFlow(sp.getInt("lock_delay", 0))
    /** Ennyi másodperc háttérben töltött idő után zár az alkalmazás. */
    val lockDelaySec: StateFlow<Int> = _lockDelay
    fun setLockDelay(v: Int) { sp.edit().putInt("lock_delay", v).apply(); _lockDelay.value = v }

    private val _secure = MutableStateFlow(sp.getBoolean("secure", false))
    /** Képernyőkép / előnézet tiltása. */
    val blockScreenshots: StateFlow<Boolean> = _secure
    fun setBlockScreenshots(v: Boolean) { sp.edit().putBoolean("secure", v).apply(); _secure.value = v }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { CryptoUtil.rng.nextBytes(it) }
        sp.edit().putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)).apply()
        _lockEnabled.value = true
    }

    fun checkPin(pin: String): Boolean {
        val salt = Base64.decode(sp.getString("pin_salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("pin_hash", null) ?: return false, Base64.NO_WRAP)
        return java.security.MessageDigest.isEqual(expected, hash(pin, salt))
    }

    fun disableLock() {
        sp.edit().remove("pin_salt").remove("pin_hash").putBoolean("biometric", false).apply()
        _lockEnabled.value = false; _biometric.value = false
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    private fun defaultName(): String = (Build.MODEL ?: "Android").take(24)
}
