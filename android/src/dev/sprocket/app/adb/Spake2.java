// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/Spake2.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The SPAKE2 variant used by adb wireless pairing (edwards25519 + SHA-512). Must match the device's implementation
 * byte for byte:
 * - private key = (64 random bytes mod L) * 8, i.e. multiplied by the cofactor;
 * - password scalar = SHA-512(password) mod L, then add L, 2L, 4L according to its low three bits in turn, so it
 *   becomes a multiple of 8 (matches the device side's historical fix);
 * - send P* = privateKey*B + passwordScalar*(M for Alice, N for Bob);
 * - shared key = SHA-512(each piece prefixed with its 8-byte little-endian length: Alice's name, Bob's name, Alice's
 *   message, Bob's message, privateKey*(Q* - passwordScalar*peer constant), SHA-512(password)).
 * M and N derive from public seed strings, see {@link #SEED_M}/{@link #SEED_N}; the tests re-derive and check them.
 */
public final class Spake2 {
    public enum Role { ALICE, BOB }

    public static final String SEED_M = "edwards25519 point generation seed (M)";
    public static final String SEED_N = "edwards25519 point generation seed (N)";
    static final EdPoint M = requireNonNull(EdPoint.decode(EdPoint.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")));
    static final EdPoint N = requireNonNull(EdPoint.decode(EdPoint.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")));

    private final Role role;
    private final byte[] myName;
    private final byte[] theirName;

    private BigInteger privateKey;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;
    private boolean done;

    public Spake2(Role role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName;
        this.theirName = theirName;
    }

    public byte[] generateMessage(byte[] password) {
        return generateMessage(password, new SecureRandom());
    }

    public byte[] generateMessage(byte[] password, SecureRandom random) {
        byte[] seed = new byte[64];
        random.nextBytes(seed);
        return generateMessage(password, seed);
    }

    /** seed is 64 random bytes; only tests pass a fixed value. */
    byte[] generateMessage(byte[] password, byte[] seed) {
        if (myMsg != null) throw new IllegalStateException("message already generated");
        if (seed.length != 64) throw new IllegalArgumentException("Failed requirement.");
        BigInteger priv = EdPoint.fromLe(seed).mod(EdPoint.L).shiftLeft(3);
        byte[] hash = sha("SHA-512").digest(password);
        BigInteger s = EdPoint.fromLe(hash).mod(EdPoint.L);
        if (s.testBit(0)) s = s.add(EdPoint.L);
        if (s.testBit(1)) s = s.add(EdPoint.L.shiftLeft(1));
        if (s.testBit(2)) s = s.add(EdPoint.L.shiftLeft(2));
        EdPoint mask = (role == Role.ALICE ? M : N).times(s);
        byte[] msg = EdPoint.BASE.times(priv).plus(mask).encode();
        privateKey = priv;
        passwordScalar = s;
        passwordHash = hash;
        myMsg = msg;
        return msg.clone();
    }

    /** Returns the 64-byte shared key; throws {@link IllegalArgumentException} when the peer's point is not on the curve. */
    public byte[] processMessage(byte[] theirMsg) {
        byte[] msg = myMsg;
        if (msg == null) throw new IllegalStateException("generateMessage first");
        if (done) throw new IllegalStateException("already processed");
        if (theirMsg.length != 32) throw new IllegalArgumentException("peer message must be 32 bytes");
        EdPoint qStar = EdPoint.decode(theirMsg);
        if (qStar == null) throw new IllegalArgumentException("peer point not on curve");
        EdPoint peerMask = (role == Role.ALICE ? N : M).times(passwordScalar);
        byte[] shared = qStar.minus(peerMask).times(privateKey).encode();
        MessageDigest sha = sha("SHA-512");
        if (role == Role.ALICE) {
            put(sha, myName); put(sha, theirName); put(sha, msg); put(sha, theirMsg);
        } else {
            put(sha, theirName); put(sha, myName); put(sha, theirMsg); put(sha, msg);
        }
        put(sha, shared);
        put(sha, passwordHash);
        done = true;
        return sha.digest();
    }

    private static void put(MessageDigest sha, byte[] b) {
        byte[] len = new byte[8];
        long l = b.length;
        for (int i = 0; i < 8; i++) { len[i] = (byte) (l & 0xff); l = l >>> 8; }
        sha.update(len);
        sha.update(b);
    }

    /** Derives a constant point from a seed string: SHA-256(seed); while it doesn't decode, SHA-256 the digest again until it lands on the curve. */
    static EdPoint derivePoint(String seed) {
        byte[] v = sha("SHA-256").digest(seed.getBytes(StandardCharsets.US_ASCII));
        while (true) {
            EdPoint p = EdPoint.decode(v);
            if (p != null) return p;
            v = sha("SHA-256").digest(v);
        }
    }

    static MessageDigest sha(String name) {
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EdPoint requireNonNull(EdPoint p) {
        if (p == null) throw new IllegalArgumentException("Required value was null.");
        return p;
    }
}
