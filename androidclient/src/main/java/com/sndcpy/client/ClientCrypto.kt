package com.sndcpy.client

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
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
 * Client-side mirror of the SNDCPY server crypto engine.
 */
object ClientCrypto {

    private const val EC_ALGORITHM = "EC"
    private const val KEY_BITS = 256
    private const val AGREEMENT_ALGORITHM = "ECDH"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    private const val SESSION_KEY_BITS = 256
    private val HKDF_INFO = "SNDCPY-SESSION-KEY-v1".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()
    
    // Cache the EC parameter spec from a generated key pair for reuse in x963Decode
    private val ecSpec: java.security.spec.ECParameterSpec by lazy {
        val kp = KeyPairGenerator.getInstance(EC_ALGORITHM).apply {
            initialize(KEY_BITS, secureRandom)
        }.generateKeyPair()
        (kp.public as java.security.interfaces.ECPublicKey).params
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(EC_ALGORITHM).apply {
            initialize(KEY_BITS, secureRandom)
        }.generateKeyPair()

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(x963Encode(publicKey))

    fun decodePublicKey(encoded: String): PublicKey =
        x963Decode(Base64.getDecoder().decode(encoded))

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

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, frameBody: ByteArray): ByteArray {
        require(frameBody.size > GCM_IV_LENGTH_BYTES) { "Ciphertext too short" }
        val iv = frameBody.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = frameBody.copyOfRange(GCM_IV_LENGTH_BYTES, frameBody.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

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

    /** Encodes a P-256 public key as X963 raw point (0x04 || x || y, 65 bytes). */
    private fun x963Encode(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        // X.509 SubjectPublicKeyInfo for P-256: BIT STRING (0x03 0x42 0x00) followed by 0x04 || x || y
        for (i in encoded.indices) {
            if (encoded[i] == 0x04.toByte() && i >= 3 && 
                encoded[i-3] == 0x03.toByte() && encoded[i-2] == 0x42.toByte() && encoded[i-1] == 0x00.toByte() &&
                i + 64 < encoded.size) {
                return encoded.copyOfRange(i, i + 65)
            }
        }
        throw IllegalArgumentException("Could not extract X963 point from public key")
    }

    /** Decodes an X963 raw point (0x04 || x || y) into an ECPublicKey. */
    private fun x963Decode(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid X963 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), ecSpec))
    }
}