package com.upi.vpa_service.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts bank account numbers at rest.
 *
 * <h3>What this replaces</h3>
 *
 * {@code VpaMapper} previously did this:
 *
 * <pre>
 *   private String encrypt(String accountNumber) {
 *       return Base64.getEncoder().encodeToString(accountNumber.getBytes());
 *   }
 * </pre>
 *
 * with a comment promising AES-256 "for production". Base64 is an
 * <em>encoding</em>, not encryption: it is trivially and publicly reversible,
 * requires no key, and provides exactly no confidentiality. Anyone with read
 * access to the table has every account number. Worse than the weakness itself
 * is that the surrounding code called it {@code encrypt} and the column comment
 * read "Stored AES-256 encrypted" — so the schema documented a protection that
 * did not exist. A security control that is believed but absent is more
 * dangerous than one that is known to be missing, because nobody goes looking.
 *
 * <h3>AES-256-GCM, and why GCM specifically</h3>
 *
 * GCM is an <em>authenticated</em> mode: it produces a tag that is verified on
 * decryption, so tampering with a stored ciphertext is detected rather than
 * silently yielding different plaintext. With an unauthenticated mode such as
 * CBC, an attacker with write access to the table could flip bits in a stored
 * account number and the service would decrypt it to <em>some</em> other value
 * and pay it. For a field that determines where money goes, integrity matters
 * as much as confidentiality.
 *
 * <p>A fresh random 12-byte IV is generated per encryption and stored
 * alongside the ciphertext. Reusing an IV with GCM is catastrophic — it leaks
 * the XOR of plaintexts and, worse, allows forgery — which is why it is
 * generated here rather than configured.
 *
 * <h3>Stored format, and crypto agility</h3>
 *
 * <pre>
 *   v1:BASE64(iv ‖ ciphertext ‖ tag)
 * </pre>
 *
 * The version prefix is not decoration. Rows written by the old Base64 code
 * are still in the database, and a hard cutover would have made every existing
 * VPA unresolvable — every payment to an existing payee would fail. The prefix
 * lets {@link #decrypt} recognise legacy values and read them, so the change is
 * deployable without a migration window. It also means a future move to a new
 * algorithm or a rotated key is a {@code v2:} branch rather than another
 * breaking change.
 *
 * <h3>Key management, stated honestly</h3>
 *
 * The key comes from configuration, which for local development means a
 * default value in {@code application.yml}. <b>That is not key management.</b>
 * In a real deployment the key belongs in a KMS or secret manager, is never
 * written to disk, is rotated on a schedule, and the ciphertext carries a key
 * id so old data stays readable across rotations. The version prefix above is
 * the hook that would make that possible; nothing else here is production key
 * handling, and this comment exists so nobody mistakes it for such.
 */
@Component
@Slf4j
public class AccountNumberCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;      // 96 bits, the GCM standard
    private static final int TAG_LENGTH_BITS = 128;
    private static final String VERSION_PREFIX = "v1:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public AccountNumberCipher(
            @Value("${vpa.encryption.key:}") String configuredKey) {

        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException(
                    "vpa.encryption.key is not set. Generate one with:\n"
                  + "  openssl rand -base64 32\n"
                  + "and set it in application.yml or the VPA_ENCRYPTION_KEY "
                  + "environment variable. Refusing to start rather than "
                  + "silently storing account numbers in the clear.");
        }

        byte[] keyBytes = Base64.getDecoder().decode(configuredKey);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "vpa.encryption.key must decode to 32 bytes for AES-256, got "
                            + keyBytes.length + ". Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypt, returning {@code v1:BASE64(iv ‖ ciphertext ‖ tag)}. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            RANDOM.nextBytes(iv);   // fresh per encryption — never reuse under GCM

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            // Deliberately fatal. Falling back to storing the value unencrypted
            // would be the worst possible outcome: the caller would see success
            // and the data would be in the clear.
            throw new IllegalStateException("Failed to encrypt account number", e);
        }
    }

    /**
     * Decrypt a stored value.
     *
     * <p>Handles both the current format and the legacy Base64 rows written
     * before this class existed, so deploying it does not strand existing VPAs.
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }

        if (!stored.startsWith(VERSION_PREFIX)) {
            // Legacy: plain Base64 from the previous implementation. Read it so
            // existing payees keep working; it is re-encrypted on next write.
            log.debug("Reading a legacy (unencrypted) account number - will be "
                    + "upgraded on next write");
            try {
                return new String(Base64.getDecoder().decode(stored),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notBase64) {
                // Older still, or hand-edited: assume it is already plaintext.
                return stored;
            }
        }

        try {
            byte[] combined = Base64.getDecoder().decode(
                    stored.substring(VERSION_PREFIX.length()));

            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext),
                    java.nio.charset.StandardCharsets.UTF_8);

        } catch (javax.crypto.AEADBadTagException tampered) {
            // GCM's authentication tag failed. The stored value was modified,
            // or the wrong key is configured. Either way, returning a
            // best-effort plaintext here would mean paying an account number
            // somebody else chose.
            log.error("Account number failed integrity check - stored value was "
                    + "modified or the key is wrong");
            throw new IllegalStateException("Account number failed integrity verification", tampered);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt account number", e);
        }
    }

    /** True if the stored value predates this class and should be re-encrypted. */
    public boolean isLegacyFormat(String stored) {
        return stored != null && !stored.startsWith(VERSION_PREFIX);
    }
}
