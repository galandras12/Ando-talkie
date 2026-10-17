package hu.ando.talkie.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Az eszköz hosszú távú azonosítója (aláíráshoz). */
interface Identity {
    val publicKey: ByteArray
    fun sign(data: ByteArray): ByteArray
}

/** Az Android Keystore-ban tárolt EC (P-256) azonosító kulcs: a privát kulcs sosem hagyja el az eszközt. */
class KeystoreIdentity(alias: String = "ando_identity") : Identity {
    private val priv: PrivateKey
    override val publicKey: ByteArray

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
        }
        priv = ks.getKey(alias, null) as PrivateKey
        publicKey = ks.getCertificate(alias).publicKey.encoded
    }

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }
}

object CryptoUtil {
    val rng = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-Expand (RFC 5869), legfeljebb 32 bájt. */
    fun expand(prk: ByteArray, info: String, len: Int = 32): ByteArray =
        hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(len)

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(sig) }
    } catch (e: Exception) {
        false
    }

    fun generateEphemeral(): KeyPair =
        KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }

    fun ecdh(priv: PrivateKey, peerPub: ByteArray): ByteArray {
        val pub: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
        return KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
    }

    /** Az azonosító kulcs ujjlenyomata (32 hex karakter). */
    fun fingerprint(pub: ByteArray): String = sha256(pub).take(16).joinToString("") { "%02x".format(it) }

    fun prettyFingerprint(fp: String): String = fp.uppercase().chunked(4).joinToString(" ")
}

/** Az eszközön tárolt adatok (üzenetszöveg) titkosítása Keystore AES-GCM kulccsal. */
class StorageCipher(private val alias: String = "ando_storage") {
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }
        ks.getKey(alias, null) as SecretKey
    }

    fun encrypt(plain: String?): String? {
        if (plain == null) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(enc: String?): String? {
        if (enc == null) return null
        return try {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) {
            "⚠"
        }
    }
}
