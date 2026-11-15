// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/PairingAuth.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 (RFC 5869). An empty salt means 32 zero bytes, as the RFC says. */
public final class Hkdf {
    private Hkdf() {}

    public static byte[] sha256(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) throw new IllegalArgumentException("Failed requirement.");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] prev = new byte[0];
        int pos = 0;
        int counter = 1;
        while (pos < length) {
            mac.update(prev);
            mac.update(info);
            mac.update((byte) counter);
            prev = mac.doFinal();
            int n = Math.min(prev.length, length - pos);
            System.arraycopy(prev, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }
}
