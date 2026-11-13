package com.SNDCPY.server.security

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyPair
import java.util.concurrent.CountDownLatch
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
 * - Alternative: prompt-based pairing where both sides confirm (like Bluetooth).
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

    /** Pending fingerprint for prompt-based pairing approval. */
    @Volatile
    private var pendingPromptFingerprint: String? = null

    @Volatile
    private var pendingPromptExpiresAtMs: Long = 0L

    /** Signaled when the server user makes a decision on the in-flight prompt pairing. */
    private var promptDecisionLatch: CountDownLatch? = null

    /** UI decision for the in-flight prompt pairing (true = approve, false = reject). */
    private val promptDecision = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Signaled when an unknown client connects and the server user must decide
     * whether to approve the pairing request before a code is issued.
     */
    private var connectDecisionLatch: CountDownLatch? = null

    /** UI decision for the in-flight connect approval (true = approve, false = reject). */
    private val connectDecision = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * Initiates a connect-approval request for an unknown client.
     * The server user must approve (Allow) or reject (Reject) the incoming
     * pairing request before any pairing code is issued or shown.
     * Returns true if the request was initiated, false if already paired or expired.
     */
    fun requestConnectApproval(fingerprint: String): Boolean {
        synchronized(lock) {
            if (pairedFingerprints().contains(fingerprint)) {
                return false  // Already paired
            }
            connectDecision.set(false)
            connectDecisionLatch = CountDownLatch(1)
            return true
        }
    }

    /** Server user approves the incoming connect request. */
    fun approveConnectApproval(): Boolean {
        synchronized(lock) {
            if (connectDecisionLatch == null) return false
            connectDecision.set(true)
            connectDecisionLatch?.countDown()
            connectDecisionLatch = null
            return true
        }
    }

    /** Server user rejects the incoming connect request. */
    fun rejectConnectApproval(): Boolean {
        synchronized(lock) {
            if (connectDecisionLatch == null) return false
            connectDecision.set(false)
            connectDecisionLatch?.countDown()
            connectDecisionLatch = null
            return true
        }
    }

    /** Blocks until the server user decides (approve/reject) or the timeout elapses. */
    fun waitForConnectDecision(timeoutMs: Long = 30_000): Boolean {
        val latch = connectDecisionLatch ?: return false
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return false
        }
        if (connectDecisionLatch != null) {
            // Timed out without a decision.
            synchronized(lock) {
                connectDecisionLatch = null
            }
        }
        return connectDecision.get()
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

    /**
     * Initiates prompt-based pairing with a client fingerprint.
     * Returns true if the prompt was initiated, false if already paired or expired.
     */
    fun requestPromptPairing(fingerprint: String): Boolean {
        synchronized(lock) {
            if (pairedFingerprints().contains(fingerprint)) {
                return false  // Already paired
            }
            pendingPromptFingerprint = fingerprint
            pendingPromptExpiresAtMs = System.currentTimeMillis() +
                TimeUnit.MINUTES.toMillis(PAIRING_PROMPT_TTL_MINUTES)
            promptDecision.set(false)
            promptDecisionLatch = CountDownLatch(1)
            return true
        }
    }

    /** Server user approves the pending prompt pairing. */
    fun approvePromptPairing(): Boolean {
        synchronized(lock) {
            if (pendingPromptFingerprint == null || promptDecisionLatch == null) return false
            promptDecision.set(true)
            promptDecisionLatch?.countDown()
            promptDecisionLatch = null
            return true
        }
    }

    /** Server user rejects the pending prompt pairing. */
    fun rejectPromptPairing(): Boolean {
        synchronized(lock) {
            if (pendingPromptFingerprint == null || promptDecisionLatch == null) return false
            promptDecision.set(false)
            promptDecisionLatch?.countDown()
            promptDecisionLatch = null
            return true
        }
    }

    /** Blocks until the server user decides (approve/reject) or the timeout elapses. */
    fun waitForPromptDecision(timeoutMs: Long = 30_000): Boolean {
        val latch = promptDecisionLatch ?: return false
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return false
        }
        if (promptDecisionLatch != null) {
            // Timed out without a decision.
            synchronized(lock) {
                pendingPromptFingerprint = null
                pendingPromptExpiresAtMs = 0L
                promptDecisionLatch = null
            }
        }
        return promptDecision.get()
    }

    /** Persists the fingerprint as paired once the client sent PAIR_CONFIRM. */
    fun completePromptPairing(fingerprint: String): Boolean {
        synchronized(lock) {
            if (pendingPromptFingerprint != fingerprint) return false
            val registry = pairedFingerprints() + fingerprint
            preferences.edit()
                .putStringSet(KEY_PAIRED_FINGERPRINTS, registry)
                .putString(KEY_PAIRED_DEVICE_PREFIX + fingerprint, "Prompt-paired device")
                .apply()
            pendingPromptFingerprint = null
            pendingPromptExpiresAtMs = 0L
            return true
        }
    }

    fun getPendingPromptFingerprint(): String? {
        synchronized(lock) {
            val expired = System.currentTimeMillis() > pendingPromptExpiresAtMs
            if (expired) {
                pendingPromptFingerprint = null
                pendingPromptExpiresAtMs = 0L
                return null
            }
            return pendingPromptFingerprint
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
        const val PAIRING_PROMPT_TTL_MINUTES = 2L
    }
}
