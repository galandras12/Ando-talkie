package hu.ando.talkie

import hu.ando.talkie.core.CryptoUtil
import hu.ando.talkie.core.Identity
import hu.ando.talkie.net.SecureChannel
import hu.ando.talkie.net.T
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.concurrent.thread

class SecureChannelTest {
    private class SoftIdentity : Identity {
        private val kp = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        override val publicKey: ByteArray = kp.public.encoded
        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
    }

    private fun pair(): Pair<SecureChannel, SecureChannel> {
        val server = java.net.ServerSocket(0)
        val client = java.net.Socket("127.0.0.1", server.localPort)
        val peer = server.accept()
        return SecureChannel(client.getInputStream(), client.getOutputStream(), SoftIdentity()) to
            SecureChannel(peer.getInputStream(), peer.getOutputStream(), SoftIdentity())
    }

    @Test
    fun handshakeGivesSameSasAndRoundTripsFrames() {
        val (a, b) = pair()
        var rb: Any? = null
        val t = thread { rb = b.handshake(false) }
        val ra = a.handshake(true)
        t.join()
        val rbh = rb as hu.ando.talkie.net.HandshakeResult
        assertEquals(ra.sas, rbh.sas)
        assertEquals(6, ra.sas.length)

        a.send(T.TEXT, "szia".toByteArray())
        val f = b.receive()
        assertEquals(T.TEXT, f.type)
        assertEquals("szia", String(f.body))

        val big = ByteArray(32 * 1024) { it.toByte() }
        b.send(T.FILE_DATA, big)
        assertArrayEquals(big, a.receive().body)
    }

    @Test
    fun fingerprintIsStable() {
        val k = ByteArray(10) { it.toByte() }
        assertEquals(CryptoUtil.fingerprint(k), CryptoUtil.fingerprint(k))
        assertTrue(CryptoUtil.fingerprint(k).length == 32)
    }
}
