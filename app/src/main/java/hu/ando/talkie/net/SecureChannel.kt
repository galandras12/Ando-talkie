package hu.ando.talkie.net

import hu.ando.talkie.core.CryptoUtil
import hu.ando.talkie.core.Identity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object T {
    const val PROFILE = 1
    const val TEXT = 2
    const val FILE_META = 3
    const val FILE_DATA = 4
    const val FILE_END = 5
    const val CALL_REQ = 10
    const val CALL_ACCEPT = 11
    const val CALL_REJECT = 12
    const val CALL_END = 13
    const val AUDIO = 14
    const val PTT_START = 15
    const val PTT_END = 16
    const val VERIFIED = 20
    const val BYE = 31
}

class Frame(val type: Int, val body: ByteArray)

class HandshakeResult(val peerIdentity: ByteArray, val sas: String)

/**
 * Peer-to-peer végpontok közötti titkosított csatorna tetszőleges (TCP / RFCOMM) bájtfolyam felett.
 *
 *  - Kézfogás: mindkét fél ideiglenes P-256 kulcsot (ECDH) és hosszú távú azonosító kulcsot küld,
 *    az egész átiratot aláírják az azonosító kulccsal, így a munkamenet kulcsa csak a két félé.
 *  - Az átirat alapján mindkét oldalon azonos 6 számjegyű ellenőrző kód (SAS) készül, amit a felhasználók
 *    összevetnek, így man-in-the-middle támadás kiszűrhető.
 *  - Keretek: AES-256-GCM, irányonként külön kulccsal és számlálós nonce-szal.
 */
class SecureChannel(input: InputStream, output: OutputStream, private val identity: Identity) {
    private val din = DataInputStream(BufferedInputStream(input, 32 * 1024))
    private val dout = DataOutputStream(BufferedOutputStream(output, 32 * 1024))
    private val writeLock = Any()

    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendCtr = 0L
    private var recvCtr = 0L

    private fun writeRaw(data: ByteArray) {
        dout.writeInt(data.size); dout.write(data); dout.flush()
    }

    private fun readRaw(max: Int): ByteArray {
        val len = din.readInt()
        if (len < 0 || len > max) throw IOException("Hibás keretméret: $len")
        return ByteArray(len).also { din.readFully(it) }
    }

    fun handshake(initiator: Boolean): HandshakeResult {
        val eph = CryptoUtil.generateEphemeral()
        val nonce = ByteArray(32).also { CryptoUtil.rng.nextBytes(it) }
        val mine = hello(identity.publicKey, eph.public.encoded, nonce)

        val theirs: ByteArray
        if (initiator) { writeRaw(mine); theirs = readRaw(2048) } else { theirs = readRaw(2048); writeRaw(mine) }

        val (peerId, peerEph) = parseHello(theirs)
        val transcript = CryptoUtil.sha256(if (initiator) mine + theirs else theirs + mine)
        val myRole = if (initiator) "I" else "R"
        val peerRole = if (initiator) "R" else "I"

        val mySig = identity.sign(AUTH_LABEL + myRole.toByteArray() + transcript)
        val peerSig: ByteArray
        if (initiator) { writeRaw(mySig); peerSig = readRaw(512) } else { peerSig = readRaw(512); writeRaw(mySig) }
        if (!CryptoUtil.verify(peerId, AUTH_LABEL + peerRole.toByteArray() + transcript, peerSig)) {
            throw IOException("A partner azonosítása sikertelen")
        }

        val shared = CryptoUtil.ecdh(eph.private, peerEph)
        val prk = CryptoUtil.hmac(transcript, shared)
        val i2r = SecretKeySpec(CryptoUtil.expand(prk, "ando i2r"), "AES")
        val r2i = SecretKeySpec(CryptoUtil.expand(prk, "ando r2i"), "AES")
        sendKey = if (initiator) i2r else r2i
        recvKey = if (initiator) r2i else i2r

        val sasBytes = CryptoUtil.expand(prk, "ando sas", 4)
        val sasNum = (ByteBuffer.wrap(sasBytes).int.toLong() and 0xFFFFFFFFL) % 1_000_000L
        return HandshakeResult(peerId, "%06d".format(sasNum))
    }

    fun send(type: Int, body: ByteArray = ByteArray(0)) {
        synchronized(writeLock) {
            val plain = ByteArray(1 + body.size)
            plain[0] = type.toByte()
            System.arraycopy(body, 0, plain, 1, body.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, sendKey!!, GCMParameterSpec(128, nonce(sendCtr++)))
            val ct = c.doFinal(plain)
            dout.writeInt(ct.size); dout.write(ct); dout.flush()
        }
    }

    fun receive(): Frame {
        val ct = readRaw(MAX_FRAME)
        if (ct.size < 17) throw IOException("Túl rövid keret")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey!!, GCMParameterSpec(128, nonce(recvCtr++)))
        val plain = c.doFinal(ct)
        return Frame(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }

    private fun nonce(ctr: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()

    private fun hello(idPub: ByteArray, ephPub: ByteArray, nonce: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).run {
            writeByte(VERSION)
            writeShort(idPub.size); write(idPub)
            writeShort(ephPub.size); write(ephPub)
            write(nonce)
        }
        return bo.toByteArray()
    }

    private fun parseHello(data: ByteArray): Pair<ByteArray, ByteArray> {
        val d = DataInputStream(data.inputStream())
        if (d.readUnsignedByte() != VERSION) throw IOException("Nem kompatibilis protokollverzió")
        val id = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
        val eph = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
        return id to eph
    }

    companion object {
        const val VERSION = 1
        const val MAX_FRAME = 256 * 1024
        private val AUTH_LABEL = "ando-talkie-auth-v1".toByteArray()
    }
}
