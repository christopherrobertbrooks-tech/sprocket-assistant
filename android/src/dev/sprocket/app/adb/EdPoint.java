// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/Ed25519.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Point arithmetic on edwards25519 (extended homogeneous coordinates X:Y:Z:T, x=X/Z, y=Y/Z, xy=T/Z); formulas and
 * encoding follow RFC 8032 section 5.1.
 * Only used by SPAKE2 during pairing: each pairing does a handful of scalar multiplications, so BigInteger is fast
 * enough. Not constant-time on purpose: the private key is single-use, and the pairing code is visible on the LAN
 * dialog anyway.
 */
final class EdPoint {
    // Not BigInteger.TWO: that field only exists from Android 13; on Android 12 the class throws NoSuchFieldError as
    // soon as it loads, and pairing crashes the whole process.
    private static final BigInteger TWO = BigInteger.valueOf(2);
    static final BigInteger P = TWO.pow(255).subtract(BigInteger.valueOf(19));
    /** Order of the prime-order subgroup. */
    static final BigInteger L = TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.multiply(TWO).remainder(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    static final EdPoint IDENTITY = new EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

    static final EdPoint BASE = requireNonNull(decode(hex("5866666666666666666666666666666666666666666666666666666666666666")));

    private final BigInteger x, y, z, t;

    private EdPoint(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.t = t;
    }

    EdPoint plus(EdPoint o) {
        BigInteger a = y.subtract(x).multiply(o.y.subtract(o.x)).remainder(P);
        BigInteger b = y.add(x).multiply(o.y.add(o.x)).remainder(P);
        BigInteger c = t.multiply(D2).remainder(P).multiply(o.t).remainder(P);
        BigInteger d = z.multiply(TWO).multiply(o.z).remainder(P);
        BigInteger e = b.subtract(a);
        BigInteger f = d.subtract(c);
        BigInteger g = d.add(c);
        BigInteger h = b.add(a);
        return new EdPoint(e.multiply(f).remainder(P), g.multiply(h).remainder(P), f.multiply(g).remainder(P), e.multiply(h).remainder(P));
    }

    EdPoint negate() {
        return new EdPoint(P.subtract(x.mod(P)), y, z, P.subtract(t.mod(P)));
    }

    EdPoint minus(EdPoint o) {
        return plus(o.negate());
    }

    /** k*this, k >= 0. */
    EdPoint times(BigInteger k) {
        if (k.signum() < 0) throw new IllegalArgumentException("Failed requirement.");
        EdPoint acc = IDENTITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            acc = acc.plus(acc);
            if (k.testBit(i)) acc = acc.plus(this);
        }
        return acc;
    }

    /** Affine coordinates {x, y}, both in [0, p). */
    BigInteger[] affine() {
        BigInteger zi = z.mod(P).modInverse(P);
        return new BigInteger[] {x.multiply(zi).mod(P), y.multiply(zi).mod(P)};
    }

    /** 32 bytes: y little-endian, with the parity of x in the top bit. */
    byte[] encode() {
        BigInteger zi = z.mod(P).modInverse(P);
        BigInteger ax = x.multiply(zi).remainder(P);
        BigInteger ay = y.multiply(zi).mod(P);
        byte[] out = toLe(ay, 32);
        if (ax.mod(P).testBit(0)) out[31] = (byte) (out[31] | 0x80);
        return out;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EdPoint)) return false;
        EdPoint o = (EdPoint) other;
        return x.multiply(o.z).subtract(o.x.multiply(z)).mod(P).signum() == 0
                && y.multiply(o.z).subtract(o.y.multiply(z)).mod(P).signum() == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }

    /** RFC 8032 section 5.1.3; returns null when the point is not on the curve or the encoding is not canonical. */
    static EdPoint decode(byte[] bytes) {
        if (bytes.length != 32) return null;
        byte[] raw = bytes.clone();
        int sign = ((raw[31] & 0xff) >>> 7) & 1;
        raw[31] = (byte) (raw[31] & 0x7f);
        BigInteger y = fromLe(raw);
        if (y.compareTo(P) >= 0) return null;
        BigInteger yy = y.multiply(y).remainder(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).remainder(P);
        BigInteger v3 = v.multiply(v).remainder(P).multiply(v).remainder(P);
        BigInteger v7 = v3.multiply(v3).remainder(P).multiply(v).remainder(P);
        BigInteger x = u.multiply(v3).remainder(P)
                .multiply(u.multiply(v7).remainder(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).remainder(P);
        BigInteger vxx = v.multiply(x).remainder(P).multiply(x).remainder(P);
        if (vxx.equals(u)) {
            // x stays
        } else if (vxx.equals(P.subtract(u).mod(P))) {
            x = x.multiply(SQRT_M1).remainder(P);
        } else {
            return null;
        }
        if (x.signum() == 0 && sign == 1) return null;
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x);
        return new EdPoint(x, y, BigInteger.ONE, x.multiply(y).remainder(P));
    }

    static BigInteger fromLe(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }

    static byte[] toLe(BigInteger n, int len) {
        byte[] be = n.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < Math.min(len, be.length); i++) out[i] = be[be.length - 1 - i];
        if (be.length > len) {
            for (int i = 0; i < be.length - len; i++) {
                if (be[i] != 0) throw new IllegalArgumentException("value too large");
            }
        }
        return out;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static EdPoint requireNonNull(EdPoint p) {
        if (p == null) throw new IllegalArgumentException("Required value was null.");
        return p;
    }
}
