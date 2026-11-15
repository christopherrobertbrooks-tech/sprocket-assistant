// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.interfaces.RSAPublicKey;

/**
 * adb's binary RSA public key format (all little-endian, 524 bytes in total):
 * modulus word count (64), n0inv = -1/n mod 2^32, modulus n, rr = (2^2048)^2 mod n, public exponent.
 */
public final class AdbPublicKey {
    private static final int WORDS = 64;
    public static final int SIZE = 4 + 4 + WORDS * 4 * 2 + 4;

    private AdbPublicKey() {}

    public static byte[] encode(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        if (n.bitLength() != 2048) throw new IllegalArgumentException("adb keys are RSA-2048");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        ByteBuffer buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(WORDS);
        buf.putInt(n0inv.intValue());
        buf.put(EdPoint.toLe(n, 256));
        buf.put(EdPoint.toLe(rr, 256));
        buf.putInt(key.getPublicExponent().intValue());
        return buf.array();
    }
}
