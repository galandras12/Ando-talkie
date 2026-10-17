package hu.ando.talkie.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Egy nyers (még titkosítatlan) bájtfolyam-kapcsolat. */
class RawLink(
    val input: InputStream,
    val output: OutputStream,
    val description: String,
    val transport: String,
    private val closer: Closeable,
) : Closeable {
    override fun close() { try { closer.close() } catch (_: Exception) {} }
}

data class WifiPeer(val name: String, val host: String, val port: Int)
data class BtPeer(val name: String, val address: String, val bonded: Boolean)

/** Azonos Wi‑Fi hálózaton: TCP szerver + mDNS (NSD) keresés. */
class WifiTransport(private val ctx: Context, private val onLink: (RawLink, Boolean) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var server: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var ownServiceName: String = ""

    private val _peers = MutableStateFlow<List<WifiPeer>>(emptyList())
    val peers: StateFlow<List<WifiPeer>> = _peers
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun startServer(deviceName: String) {
        if (server != null) return
        val ss = try { ServerSocket(PORT).apply { reuseAddress = true } } catch (e: Exception) {
            try { ServerSocket(0) } catch (e2: Exception) { return }
        }
        server = ss
        _listening.value = true
        Thread({
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    onLink(RawLink(s.getInputStream(), s.getOutputStream(), s.inetAddress.hostAddress ?: "?", "Wi‑Fi", s), false)
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
            _listening.value = false
        }, "wifi-accept").apply { isDaemon = true }.start()
        register(deviceName, ss.localPort)
    }

    private fun register(deviceName: String, port: Int) {
        ownServiceName = "Ando-" + deviceName.replace(Regex("[^A-Za-z0-9 _-]"), "") + "-" + UUID.randomUUID().toString().take(4)
        val info = NsdServiceInfo().apply { serviceName = ownServiceName; serviceType = SERVICE_TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) { ownServiceName = i.serviceName }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        registration = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    @Synchronized
    fun stopServer() {
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        registration = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        _listening.value = false
    }

    val localPort: Int get() = server?.localPort ?: PORT

    @Synchronized
    fun startDiscovery() {
        if (discovery != null) return
        _peers.value = emptyList()
        multicastLock = wifi.createMulticastLock("ando-mdns").apply { setReferenceCounted(false); try { acquire() } catch (_: Exception) {} }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, c: Int) { discovery = null }
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == ownServiceName) return
                synchronized(resolveQueue) { resolveQueue.addLast(i) }
                resolveNext()
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == pretty(i.serviceName) }
            }
        }
        discovery = l
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { discovery = null }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(resolveQueue) {
            if (resolving) return
            next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
        }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, c: Int) { done() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    if (host != null && i.serviceName != ownServiceName) {
                        val p = WifiPeer(pretty(i.serviceName), host, i.port)
                        _peers.value = _peers.value.filterNot { it.host == p.host && it.port == p.port } + p
                    }
                    done()
                }
                private fun done() { synchronized(resolveQueue) { resolving = false }; resolveNext() }
            })
        } catch (_: Exception) { synchronized(resolveQueue) { resolving = false } }
    }

    private fun pretty(service: String) = service.removePrefix("Ando-").replace(Regex("-[0-9a-f]{4}(\\s\\(\\d+\\))?$"), "").ifBlank { service }

    @Synchronized
    fun stopDiscovery() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
        try { multicastLock?.release() } catch (_: Exception) {}
    }

    /** Kapcsolódás IP-címre (blokkoló!). */
    fun connect(host: String, port: Int): RawLink {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 8000)
        return RawLink(s.getInputStream(), s.getOutputStream(), host, "Wi‑Fi", s)
    }

    companion object {
        const val PORT = 48555
        const val SERVICE_TYPE = "_andotalkie._tcp."

        /** A saját helyi IPv4 címek (Wi‑Fi / hotspot). */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }

        fun isOnLocalNetwork(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val viaWifi = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            return viaWifi || localAddresses().isNotEmpty()
        }
    }
}

/** Bluetooth Classic (RFCOMM) szerver + keresés. */
@SuppressLint("MissingPermission")
class BluetoothTransport(private val ctx: Context, private val onLink: (RawLink, Boolean) -> Unit) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var server: BluetoothServerSocket? = null

    private val _peers = MutableStateFlow<List<BtPeer>>(emptyList())
    val peers: StateFlow<List<BtPeer>> = _peers
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    val available get() = adapter != null
    val enabled get() = adapter?.isEnabled == true

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            has(android.Manifest.permission.BLUETOOTH_CONNECT) && has(android.Manifest.permission.BLUETOOTH_SCAN)
        } else has(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun startServer() {
        val a = adapter ?: return
        if (server != null || !a.isEnabled || !hasPermission()) return
        val ss = try { a.listenUsingInsecureRfcommWithServiceRecord("AndoTalkie", UUID_SERVICE) } catch (e: Exception) { return }
        server = ss
        _listening.value = true
        Thread({
            while (true) {
                try {
                    val s = ss.accept()
                    onLink(RawLink(s.inputStream, s.outputStream, s.remoteDevice.name ?: s.remoteDevice.address, "Bluetooth", s), false)
                } catch (e: Exception) {
                    break
                }
            }
            synchronized(this) { server = null }
            _listening.value = false
        }, "bt-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stopServer() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        _listening.value = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    add(d)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }
    }
    private var registered = false

    private fun add(d: BluetoothDevice) {
        val p = BtPeer(d.name ?: d.address, d.address, d.bondState == BluetoothDevice.BOND_BONDED)
        _peers.value = _peers.value.filterNot { it.address == p.address } + p
    }

    @Synchronized
    fun startDiscovery() {
        val a = adapter ?: return
        if (!a.isEnabled || !hasPermission()) return
        _peers.value = emptyList()
        a.bondedDevices?.forEach { add(it) }
        if (!registered) {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
            registered = true
        }
        if (a.isDiscovering) a.cancelDiscovery()
        _scanning.value = a.startDiscovery()
    }

    @Synchronized
    fun stopDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        _scanning.value = false
        if (registered) { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}; registered = false }
    }

    /** Kapcsolódás (blokkoló!). */
    fun connect(address: String): RawLink {
        val a = adapter ?: throw IllegalStateException("Nincs Bluetooth")
        try { a.cancelDiscovery() } catch (_: Exception) {}
        val dev = a.getRemoteDevice(address)
        val s = dev.createInsecureRfcommSocketToServiceRecord(UUID_SERVICE)
        try { s.connect() } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; throw e }
        return RawLink(s.inputStream, s.outputStream, dev.name ?: address, "Bluetooth", s)
    }

    companion object {
        val UUID_SERVICE: UUID = UUID.fromString("7c1b6f0e-5a4d-4f6e-9b8a-3f2d1c0a9e11")
    }
}
