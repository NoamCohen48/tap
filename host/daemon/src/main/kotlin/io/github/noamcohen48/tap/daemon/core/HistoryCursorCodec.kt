package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.daemon.storage.StoredHistoryCursor
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Opaque read state. Encryption also keeps search patterns out of exported cursor text. */
internal class HistoryCursorCodec(
    root: Path,
) {
    private val random = SecureRandom()
    private val key: SecretKeySpec

    init {
        val path = root.resolve("cursor-key")
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            val bytes = ByteArray(32).also(random::nextBytes)
            atomicPrivateFile(path) { it.write(bytes) }
        }
        privateFile(path)
        check(Files.size(path) == 32L) { "invalid private history cursor key" }
        key = SecretKeySpec(Files.readAllBytes(path), "AES")
    }

    fun encode(value: StoredHistoryCursor): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val encoded = nonce + cipher.doFinal(value.toByteArray())
        require(encoded.size <= MAX_TOKEN_BYTES) { "history selection has too many sources; narrow the selection" }
        return "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
    }

    fun decode(value: String): StoredHistoryCursor {
        require(value.startsWith("1.") && value.length <= MAX_TOKEN_BYTES * 2) { "invalid history cursor" }
        try {
            val bytes = Base64.getUrlDecoder().decode(value.substring(2))
            require(bytes.size in NONCE_BYTES + 16..MAX_TOKEN_BYTES) { "invalid history cursor" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, NONCE_BYTES)))
            return StoredHistoryCursor.parseFrom(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)).also {
                require(it.version == 2) { "this history cursor is from an older daemon; start a new read" }
            }
        } catch (invalid: GeneralSecurityException) {
            throw IllegalArgumentException("invalid or expired history cursor", invalid)
        } catch (invalid: com.google.protobuf.InvalidProtocolBufferException) {
            throw IllegalArgumentException("invalid history cursor", invalid)
        }
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val MAX_TOKEN_BYTES = 64 * 1024
    }
}
