package com.SNDCPY.server.security

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyPair
import java.util.concurrent.TimeUnit

/**
 * Manages device pairing and long-lived identity for the SNDCPY server.
 *
 * Trust model:
 * - The server owns a persistent ECDH P-256 identity key pair.
 * - A client pairs by presenting its public key plus a one-time 6-digit
 *   pairing code shown on the server screen. The client key fingerprint
 *   is then persisted and trusted for all future sessions.
 * - Unpaired fingerprints are rejected before any secure channel opens.
 */
class PairingManager(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** One-time code issued while a pairing attempt is in flight. */
    @Volatile
    private var pendingPairingCode: String? = null

    @Volatile
    private var pendingPairingExpiresAtMs: Long = 0L

    val serverKeyPair: KeyPair
        get() {
            cachedKeyPair?.let { return it }
            synchronized(lock) {
                cachedKeyPair?.let { return it }
                val storedPrivate = preferences.getString(KEY_SERVER_PRIVATE, null)
                val storedPublic = preferences.getString(KEY_SERVER_PUBLIC, null)
                val keyPair = if (storedPrivate != null && storedPublic != null) {
                    try {
                        KeyPair(
                            CryptoEngine.decodePublicKey(storedPublic),
                            CryptoEngine.decodePrivateKey(storedPrivate)
                        )
                    } catch (_: Exception) {
                        createAndPersistKeyPair()
                    }
                } else {
                    createAndPersistKeyPair()
                }
                cachedKeyPair = keyPair
                return keyPair
            }
        }

    /** SHA-256 fingerprint of the server's own identity public key. */
    val serverFingerprint: String
        get() = CryptoEngine.fingerprint(serverKeyPair.public)

    /**
     * Issues a fresh one-time pairing code valid for [PAIRING_CODE_TTL_MINUTES] minutes.
     * Returns the code so the caller can surface it in the UI.
     */
    fun issuePairingCode(): String {
        synchronized(lock) {
            val code = (100000..999999).random().toString()
            pendingPairingCode = code
            pendingPairingExpiresAtMs = System.currentTimeMillis() +
                TimeUnit.MINUTES.toMillis(PAIRING_CODE_TTL_MINUTES)
            return code
        }
    }

    fun verifyPairingCode(code: String): Boolean {
        synchronized(lock) {
            val expected = pendingPairingCode ?: return false
            val expired = System.currentTimeMillis() > pendingPairingExpiresAtMs
            pendingPairingCode = null
            pendingPairingExpiresAtMs = 0L
            return !expired && expected == code.trim()
        }
    }

    fun isPaired(fingerprint: String): Boolean =
        pairedFingerprints().contains(fingerprint)

    fun addPairedDevice(fingerprint: String, label: String) {
        synchronized(lock) {
            val registry = pairedFingerprints() + fingerprint
            preferences.edit()
                .putStringSet(KEY_PAIRED_FINGERPRINTS, registry)
                .putString(KEY_PAIRED_DEVICE_PREFIX + fingerprint, label)
                .apply()
        }
    }

    fun forgetAllPairedDevices() {
        synchronized(lock) {
            val editor = preferences.edit()
            pairedFingerprints().forEach { editor.remove(KEY_PAIRED_DEVICE_PREFIX + it) }
            editor.remove(KEY_PAIRED_FINGERPRINTS).apply()
        }
    }

    fun pairedDeviceCount(): Int = pairedFingerprints().size

    private fun pairedFingerprints(): Set<String> =
        preferences.getStringSet(KEY_PAIRED_FINGERPRINTS, emptySet())?.toSet() ?: emptySet()

    private fun createAndPersistKeyPair(): KeyPair {
        val keyPair = CryptoEngine.generateKeyPair()
        preferences.edit()
            .putString(KEY_SERVER_PRIVATE, CryptoEngine.encodePrivateKey(keyPair.private))
            .putString(KEY_SERVER_PUBLIC, CryptoEngine.encodePublicKey(keyPair.public))
            .apply()
        return keyPair
    }

    companion object {
        private const val PREFERENCES_NAME = "sndcpy_security"
        private const val KEY_SERVER_PRIVATE = "server_private_key"
        private const val KEY_SERVER_PUBLIC = "server_public_key"
        private const val KEY_PAIRED_FINGERPRINTS = "paired_fingerprints"
        private const val KEY_PAIRED_DEVICE_PREFIX = "paired_device_"
        const val PAIRING_CODE_TTL_MINUTES = 5L
    }
}
