// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/PairingAuth.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-GCM on the pairing channel: key = HKDF(SPAKE2 key, no salt, "adb pairing_auth aes-128-gcm key", 16);
 * the first 8 bytes of the 12-byte nonce hold that direction's sequence number (little-endian, from 0, plus one per
 * message), the rest are zero; no associated data, and the 16-byte tag is appended to the ciphertext.
 */
public final class PairingCipher {
    private static final byte[] INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec key;
    private long encSeq = 0L;
    private long decSeq = 0L;

    public PairingCipher(byte[] keyMaterial) throws GeneralSecurityException {
        key = new SecretKeySpec(Hkdf.sha256(keyMaterial, null, INFO, 16), "AES");
    }

    public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        return run(Cipher.ENCRYPT_MODE, encSeq++, plain);
    }

    /** Throws {@link javax.crypto.AEADBadTagException} when authentication fails. */
    public byte[] decrypt(byte[] sealed) throws GeneralSecurityException {
        byte[] out = run(Cipher.DECRYPT_MODE, decSeq, sealed);
        decSeq++;
        return out;
    }

    private byte[] run(int mode, long seq, byte[] data) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (seq >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }
}
