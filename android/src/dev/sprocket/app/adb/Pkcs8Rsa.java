// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.math.BigInteger;

/**
 * Reads the RSA public exponent from PKCS#8: PrivateKeyInfo ::= SEQUENCE { version, algorithm, privateKey OCTET STRING },
 * and privateKey holds RSAPrivateKey ::= SEQUENCE { version, modulus, publicExponent, ... }. Returns null when the
 * structure is wrong.
 */
final class Pkcs8Rsa {
    private Pkcs8Rsa() {}

    static BigInteger publicExponent(byte[] pkcs8) {
        try {
            byte[] info = new Der(pkcs8).read(0x30);
            Der outer = new Der(info);
            outer.read(0x02);
            outer.read(0x30);
            byte[] inner = outer.read(0x04);
            Der rsa = new Der(new Der(inner).read(0x30));
            rsa.read(0x02);
            rsa.read(0x02);
            return new BigInteger(rsa.read(0x02));
        } catch (Exception e) {
            return null;
        }
    }

    /** Just enough DER to read the layers above: takes the contents of the given tag, in order. */
    private static final class Der {
        private final byte[] b;
        private int pos = 0;

        Der(byte[] b) {
            this.b = b;
        }

        byte[] read(int tag) {
            if ((b[pos++] & 0xff) != tag) throw new IllegalArgumentException("unexpected tag");
            int len = b[pos++] & 0xff;
            if (len >= 0x80) {
                int n = len & 0x7f;
                if (n < 1 || n > 3) throw new IllegalArgumentException("unsupported length");
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (b[pos++] & 0xff);
            }
            if (pos + len > b.length) throw new IllegalArgumentException("truncated");
            byte[] out = new byte[len];
            System.arraycopy(b, pos, out, 0, len);
            pos += len;
            return out;
        }
    }
}
