package com.SNDCPY.server.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic engine for SNDCPY secure sessions.
 *
 * Session establishment:
 * 1. Each side owns a long-lived ECDH P-256 key pair.
 * 2. Public keys plus fresh nonces are exchanged during the handshake.
 * 3. An ECDH shared secret is derived and expanded with HKDF-SHA256
 *    into an AES-256 session key bound to both nonces.
 * 4. All post-handshake traffic uses AES-256-GCM with a random 96-bit IV
 *    prefixed to every ciphertext (IV || ciphertext framing).
 */
object CryptoEngine {

    private const val KEY_ALGORITHM = "EC"
    private const val KEY_BITS = 256
    private const val AGREEMENT_ALGORITHM = "ECDH"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    private const val SESSION_KEY_BITS = 256
    private val HKDF_INFO = "SNDCPY-SESSION-KEY-v1".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(KEY_BITS, secureRandom)
        }.generateKeyPair()

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(x963Encode(publicKey))

    fun decodePublicKey(encoded: String): PublicKey =
        x963Decode(Base64.getDecoder().decode(encoded))

    fun encodePrivateKey(privateKey: PrivateKey): String =
        Base64.getEncoder().encodeToString(privateKey.encoded)

    fun decodePrivateKey(encoded: String): PrivateKey =
        KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))

    /** Encodes a P-256 public key as X963 raw point (0x04 || x || y). */
    private fun x963Encode(publicKey: PublicKey): ByteArray {
        val ecKey = java.security.interfaces.ECPublicKey::class.java.cast(publicKey)
        val x = ecKey.w.x
        val y = ecKey.w.y
        val buf = ByteArray(1 + 64)
        buf[0] = 0x04
        x.toByteArray().copyOf(32).copyInto(buf, 1, 0, 32)
        y.toByteArray().copyOf(32).copyInto(buf, 33, 0, 32)
        return buf
    }

    /** Decodes an X963 raw point (0x04 || x || y) into an ECPublicKey. */
    private fun x963Decode(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid X963 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val spec = ECGenParameterSpec("prime256v1")
        return KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
    }

    /** Stable SHA-256 fingerprint of a public key, uppercase hex. */
    fun fingerprint(publicKey: PublicKey): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    fun randomNonce(lengthBytes: Int = 16): ByteArray =
        ByteArray(lengthBytes).also { secureRandom.nextBytes(it) }

    fun encodeBytes(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun decodeBytes(encoded: String): ByteArray =
        Base64.getDecoder().decode(encoded)

    /**
     * Derives the AES-256 session key from the ECDH shared secret,
     * salted with both handshake nonces so every session is unique.
     */
    fun deriveSessionKey(
        privateKey: PrivateKey,
        peerPublicKey: PublicKey,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): SecretKey {
        val agreement = KeyAgreement.getInstance(AGREEMENT_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        val derived = hkdfSha256(sharedSecret, clientNonce + serverNonce, HKDF_INFO, SESSION_KEY_BITS / 8)
        return SecretKeySpec(derived, "AES")
    }

    /** Encrypts plaintext into an IV-prefixed ciphertext frame body. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypts an IV-prefixed ciphertext frame body. Throws on tamper. */
    fun decrypt(key: SecretKey, frameBody: ByteArray): ByteArray {
        require(frameBody.size > GCM_IV_LENGTH_BYTES) { "Ciphertext too short" }
        val iv = frameBody.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = frameBody.copyOfRange(GCM_IV_LENGTH_BYTES, frameBody.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * RFC 5869 HKDF (extract + expand) over HMAC-SHA256.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLengthBytes: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val output = ByteArrayOutputStream(outputLengthBytes)
        var previousBlock = ByteArray(0)
        var blockCounter = 1

        while (output.size() < outputLengthBytes) {
            val input = previousBlock + info + byteArrayOf(blockCounter.toByte())
            previousBlock = hmacSha256(prk, input)
            output.write(previousBlock)
            blockCounter++
        }

        return output.toByteArray().copyOf(outputLengthBytes)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
