// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/src/test/kotlin/com/wangzan101/mika/core/adb/
// (CryptoTest.kt, TestTls.kt, AdbPairingTest.kt, AdbConnectionTest.kt, AdbHostsTest.kt, AdbIdentitySourceTest.kt,
// AdbConnectPortTest.kt, LocalPortScanTest.kt), Copyright 2026 wangzan101, Apache License 2.0.
//
// Desktop JDK only, no phone and no network beyond 127.0.0.1. In the package of the code under test (it needs the
// package-private pieces Kotlin marks internal). Differences from the Kotlin tests:
// - TLS is the JDK's own TLS 1.3, not Conscrypt (no third-party libraries). The JDK has no public TLS exporter, so both
//   ends of the in-process fakes use a fixed test exporter; Mika's tls13ExporterAgreesAndIgnoresEmptyContext (which
//   checks Conscrypt's exporter) is reduced to "the handshake negotiates TLSv1.3".
// - Coroutine runTest/virtual time is replaced by plain blocking calls (the 300 ms TLS retry delay is really waited).
package dev.sprocket.app.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLSocket;

/** adb wireless pairing + connection (ported from Mika) on the desktop JDK. No phone. */
public class AdbTest {
    static int fails, n;
    static final List<File> tempDirs = new ArrayList<>();

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static void ok(String what, boolean cond) { eq(what, cond, true); }

    interface Body { void run() throws Exception; }

    static <T extends Throwable> T thrown(String what, Class<T> cls, Body b) {
        n++;
        try {
            b.run();
        } catch (Throwable t) {
            if (cls.isInstance(t)) return cls.cast(t);
            fails++;
            System.out.println("FAIL " + what + ": threw " + t + ", wanted " + cls.getSimpleName());
            return null;
        }
        fails++;
        System.out.println("FAIL " + what + ": nothing thrown, wanted " + cls.getSimpleName());
        return null;
    }

    static String reason(AdbPairingException e) { return e == null ? "(none)" : e.reason.name(); }
    static String reason(AdbConnectionException e) { return e == null ? "(none)" : e.reason.name(); }

    static void test(String name, Body t) {
        try {
            t.run();
        } catch (Throwable e) {
            n++;
            fails++;
            System.out.println("FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static String hexs(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] hex(String s) { return EdPoint.hex(s); }

    static byte[] fill(int size, java.util.function.IntUnaryOperator f) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) f.applyAsInt(i);
        return b;
    }

    static byte[] cat(byte[] a, byte[] b) { return AdbPairing.concat(a, b); }

    static InetAddress loopback4() throws IOException { return InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); }

    static File tempDir() throws IOException {
        File d = Files.createTempDirectory("adbkey").toFile();
        tempDirs.add(d);
        return d;
    }

    // ---------------------------------------------------------------- TestTls.kt

    /** JVM-test TLS: the JDK's TLS 1.3, plus a fixed exporter both in-process ends share (see the file header). */
    static final class TestTls {
        static final KeyingMaterialExporter EXPORTER = (s, label, len) -> {
            byte[] h = Spake2.sha("SHA-512").digest(label.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(h, len);
        };
        private static AdbIdentity client, device;

        /** Shared test keys (RSA-2048 generation is slow; once is enough). */
        static synchronized AdbIdentity client() throws Exception {
            if (client == null) client = AdbIdentity.generate("mika@test");
            return client;
        }

        static synchronized AdbIdentity device() throws Exception {
            if (device == null) device = AdbIdentity.generate("device");
            return device;
        }

        static AdbTls tls(AdbIdentity identity) { return new AdbTls(identity, EXPORTER); }
    }

    /** In-process fake pairing server, walking the device-side flow. behavior creates the failure cases. */
    static final class FakePairingServer implements AutoCloseable {
        enum Behavior { NORMAL, BAD_HEADER_VERSION, WRONG_TYPE, CLOSE_AFTER_SPAKE }

        private final String code;
        private final Behavior behavior;
        private final String guid;
        private final ServerSocket server;
        volatile String receivedKey;
        volatile byte[] clientCertKey;
        volatile Throwable error;
        private final AdbTls tls;
        private final Thread worker;

        FakePairingServer(String code) throws Exception { this(code, Behavior.NORMAL); }

        FakePairingServer(String code, Behavior behavior) throws Exception {
            this.code = code;
            this.behavior = behavior;
            this.guid = "adb-FAKE0001-abc123";
            server = new ServerSocket(0, 1, loopback4());
            tls = TestTls.tls(TestTls.device());
            worker = new Thread(() -> {
                try (Socket plain = server.accept()) {
                    serve(plain);
                } catch (Throwable t) {
                    error = t;
                }
            }, "fake-pairing");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve(Socket plain) throws Exception {
            try (SSLSocket s = tls.wrap(plain, new InetSocketAddress(plain.getLocalAddress(), port()), false)) {
                clientCertKey = ((X509Certificate) s.getSession().getPeerCertificates()[0]).getPublicKey().getEncoded();
                byte[] exported = tls.exportKeyingMaterial(s, AdbPairing.EXPORT_LABEL, AdbPairing.EXPORT_LENGTH);
                PairingAuth auth = PairingAuth.server(cat(code.getBytes(StandardCharsets.UTF_8), exported));
                DataInputStream input = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                byte[] clientMsg = AdbPairing.readPacket(input, AdbPairing.TYPE_SPAKE2);
                switch (behavior) {
                    case BAD_HEADER_VERSION:
                        out.write(cat(new byte[] {2, 0, 0, 0, 0, 32}, new byte[32])); out.flush(); return;
                    case WRONG_TYPE:
                        AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, auth.getMessage()); return;
                    default:
                        AdbPairing.writePacket(out, AdbPairing.TYPE_SPAKE2, auth.getMessage());
                }
                auth.initCipher(clientMsg);
                byte[] sealed = AdbPairing.readPacket(input, AdbPairing.TYPE_PEER_INFO);
                if (behavior == Behavior.CLOSE_AFTER_SPAKE) return;
                byte[] info;
                try {
                    info = auth.decrypt(sealed);
                } catch (AEADBadTagException e) {
                    return; // like the device: can't decrypt, disconnect
                }
                if (!(info.length == AdbPairing.PEER_INFO_SIZE && info[0] == AdbPairing.PEER_RSA_PUB_KEY)) throw new IllegalStateException("Check failed.");
                int end = 1;
                while (end < info.length && info[end] != 0) end++;
                receivedKey = new String(info, 1, end - 1, StandardCharsets.UTF_8);
                byte[] mine = new byte[AdbPairing.PEER_INFO_SIZE];
                mine[0] = AdbPairing.PEER_DEVICE_GUID;
                byte[] g = guid.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(g, 0, mine, 1, g.length);
                AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, auth.encrypt(mine));
                s.getInputStream().read(); // wait for the client to close
            }
        }

        void join() throws InterruptedException { worker.join(10_000); }

        @Override public void close() throws IOException { server.close(); }
    }

    // ---------------------------------------------------------------- CryptoTest.kt

    static final String[][] VECTORS = { // RFC 8032 section 7.1 TEST 1-3: secret, public, message, signature
        {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
         "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
        {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
         "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
        {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
         "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
         "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
    };

    static void edPointTests() {
        test("publicKeyFromSecretMatchesRfc8032", () -> {
            for (String[] v : VECTORS) {
                byte[] h = MessageDigest.getInstance("SHA-512").digest(hex(v[0]));
                byte[] a = Arrays.copyOf(h, 32);
                a[0] = (byte) (a[0] & 248);
                a[31] = (byte) ((a[31] & 127) | 64);
                eq("RFC 8032 public key from secret", hexs(EdPoint.BASE.times(EdPoint.fromLe(a)).encode()), v[1]);
            }
        });
        // The verification equation [S]B = R + [k]A tests decoding, addition and scalar multiplication of any point at once.
        test("signatureEquationHoldsForRfc8032Vectors", () -> {
            for (String[] v : VECTORS) {
                byte[] sig = hex(v[3]);
                EdPoint r = EdPoint.decode(Arrays.copyOf(sig, 32));
                EdPoint pub = EdPoint.decode(hex(v[1]));
                BigInteger s = EdPoint.fromLe(Arrays.copyOfRange(sig, 32, 64));
                MessageDigest sha = MessageDigest.getInstance("SHA-512");
                sha.update(Arrays.copyOf(sig, 32)); sha.update(hex(v[1])); sha.update(hex(v[2]));
                BigInteger k = EdPoint.fromLe(sha.digest()).mod(EdPoint.L);
                eq("[S]B = R + [k]A", EdPoint.BASE.times(s).equals(r.plus(pub.times(k))), true);
                eq("[S]B != R + [k+1]A", !EdPoint.BASE.times(s).equals(r.plus(pub.times(k.add(BigInteger.ONE)))), true);
            }
        });
        test("groupLaws", () -> {
            EdPoint b = EdPoint.BASE;
            ok("L*B is the identity", EdPoint.IDENTITY.equals(b.times(EdPoint.L)));
            ok("5B = B+B+B+B+B", b.times(BigInteger.valueOf(5)).equals(b.plus(b).plus(b).plus(b).plus(b)));
            ok("B - B is the identity", EdPoint.IDENTITY.equals(b.minus(b)));
            ok("decode(encode(B)) = B", b.equals(EdPoint.decode(b.encode())));
            eq("B encodes as RFC 8032 says", hexs(b.encode()), "5866666666666666666666666666666666666666666666666666666666666666");
        });
        test("decodeRejectsBadEncodings", () -> {
            eq("y = p (not canonical)", EdPoint.decode(EdPoint.toLe(EdPoint.P, 32)), null);
            eq("y = 2: x^2 has no solution", EdPoint.decode(EdPoint.toLe(BigInteger.valueOf(2), 32)), null);
            byte[] negZero = new byte[32];
            negZero[0] = 1;
            negZero[31] = (byte) 0x80;
            eq("x = 0 but marked negative (y = 1)", EdPoint.decode(negZero), null);
            eq("31 bytes", EdPoint.decode(new byte[31]), null);
        });
    }

    static void spake2ConstantsTests() {
        test("mAndNDeriveFromSeeds", () -> {
            ok("M derives from its seed", Spake2.M.equals(Spake2.derivePoint(Spake2.SEED_M)));
            ok("N derives from its seed", Spake2.N.equals(Spake2.derivePoint(Spake2.SEED_N)));
            eq("M", hexs(Spake2.derivePoint(Spake2.SEED_M).encode()), "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
            eq("N", hexs(Spake2.derivePoint(Spake2.SEED_N).encode()), "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");
        });
        // Checked one by one against the coordinates and first precomputed-table entry hard-coded in the reference implementation (x, y as 32-byte little-endian).
        test("mAndNMatchHardCodedReferenceValues", () -> {
            BigInteger[] m = Spake2.M.affine();
            eq("M.x", m[0], new BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"));
            eq("M.y", m[1], new BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"));
            BigInteger[] nn = Spake2.N.affine();
            eq("N.x", nn[0], new BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"));
            eq("N.y", nn[1], new BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"));
            eq("M.x LE", hexs(EdPoint.toLe(m[0], 32)), "c8a663c597f1ee40ab6242ee256f326c752ca7d3bd323b1e119cbd04a9786f45");
            eq("N.x LE", hexs(EdPoint.toLe(nn[0], 32)), "201bc5b343177110441e73b3ae3fbf9ff544c8138fd101c28a1a6dea4d005d6e");
        });
    }

    static List<Map<String, String>> spakeCases() throws IOException {
        String text = new String(Files.readAllBytes(Paths.get("android/test/adb-spake2-vectors.txt")), StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        for (String block : text.split("\n\n")) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : block.split("\n")) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                int eqi = line.indexOf('=');
                m.put(eqi < 0 ? line : line.substring(0, eqi), eqi < 0 ? line : line.substring(eqi + 1));
            }
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    static void spake2GoldenTests() {
        test("matchesReferenceImplementation", () -> {
            List<Map<String, String>> all = spakeCases();
            eq("golden cases", all.size(), 8);
            int matched = 0;
            for (Map<String, String> c : all) {
                PairingAuth alice = PairingAuth.client(hex(c.get("alice_password")), hex(c.get("alice_seed")));
                PairingAuth bob = PairingAuth.server(hex(c.get("bob_password")), hex(c.get("bob_seed")));
                eq("alice_msg", hexs(alice.getMessage()), c.get("alice_msg"));
                eq("bob_msg", hexs(bob.getMessage()), c.get("bob_msg"));

                Spake2 aliceSpake = new Spake2(Spake2.Role.ALICE, PairingAuth.CLIENT_NAME, PairingAuth.SERVER_NAME);
                aliceSpake.generateMessage(hex(c.get("alice_password")), hex(c.get("alice_seed")));
                byte[] aliceKey = aliceSpake.processMessage(hex(c.get("bob_msg")));
                Spake2 bobSpake = new Spake2(Spake2.Role.BOB, PairingAuth.SERVER_NAME, PairingAuth.CLIENT_NAME);
                bobSpake.generateMessage(hex(c.get("bob_password")), hex(c.get("bob_seed")));
                byte[] bobKey = bobSpake.processMessage(hex(c.get("alice_msg")));
                eq("alice_key", hexs(aliceKey), c.get("alice_key"));
                eq("bob_key", hexs(bobKey), c.get("bob_key"));
                eq("alice_aes_key", hexs(Hkdf.sha256(aliceKey, null, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8), 16)), c.get("alice_aes_key"));

                alice.initCipher(bob.getMessage());
                bob.initCipher(alice.getMessage());
                byte[] plain = hex(c.get("plaintext"));
                byte[] s0 = alice.encrypt(plain);
                byte[] s1 = alice.encrypt(plain);
                eq("alice_sealed0", hexs(s0), c.get("alice_sealed0"));
                eq("alice_sealed1", hexs(s1), c.get("alice_sealed1"));
                if (c.get("alice_password").equals(c.get("bob_password"))) {
                    eq("same password, same key", c.get("alice_key"), c.get("bob_key"));
                    eq("bob decrypts 0", hexs(bob.decrypt(s0)), hexs(plain));
                    eq("bob decrypts 1", hexs(bob.decrypt(s1)), hexs(plain));
                    matched++;
                } else {
                    ok("different password, different key", !c.get("alice_key").equals(c.get("bob_key")));
                    thrown("wrong password can't decrypt", AEADBadTagException.class, () -> bob.decrypt(s0));
                }
            }
            eq("matching-password cases", matched, 7);
        });
    }

    static void spake2RoundTripTests() {
        test("randomKeysAgreeAndWrongCodeDisagrees", () -> {
            byte[] pw = cat("654321".getBytes(StandardCharsets.UTF_8), fill(64, i -> i));
            PairingAuth c = PairingAuth.client(pw);
            PairingAuth s = PairingAuth.server(pw);
            c.initCipher(s.getMessage()); s.initCipher(c.getMessage());
            byte[] msg = fill(8192, i -> i * 7);
            eq("client -> server", hexs(s.decrypt(c.encrypt(msg))), hexs(msg));
            eq("server -> client", hexs(c.decrypt(s.encrypt(msg))), hexs(msg));

            PairingAuth wrong = PairingAuth.server(cat("654320".getBytes(StandardCharsets.UTF_8), fill(64, i -> i)));
            PairingAuth c2 = PairingAuth.client(pw);
            c2.initCipher(wrong.getMessage()); wrong.initCipher(c2.getMessage());
            thrown("wrong code can't decrypt", AEADBadTagException.class, () -> wrong.decrypt(c2.encrypt(msg)));
        });
        test("sequenceNumbersMustLineUp", () -> {
            byte[] pw = "000000".getBytes(StandardCharsets.UTF_8);
            PairingAuth c = PairingAuth.client(pw);
            PairingAuth s = PairingAuth.server(pw);
            c.initCipher(s.getMessage()); s.initCipher(c.getMessage());
            c.encrypt(new byte[] {1}); // the peer never gets this one
            thrown("out-of-step sequence fails", AEADBadTagException.class, () -> s.decrypt(c.encrypt(new byte[] {2})));
        });
        test("rejectsOffCurvePeerMessage", () -> {
            PairingAuth c = PairingAuth.client("1".getBytes(StandardCharsets.UTF_8));
            thrown("off-curve point", IllegalArgumentException.class, () -> c.initCipher(EdPoint.toLe(BigInteger.valueOf(2), 32)));
            PairingAuth c2 = PairingAuth.client("1".getBytes(StandardCharsets.UTF_8));
            thrown("16-byte message", IllegalArgumentException.class, () -> c2.initCipher(new byte[16]));
        });
    }

    static void hkdfTests() {
        test("rfc5869Sha256Cases", () -> {
            eq("RFC 5869 A.1", hexs(Hkdf.sha256(fill(22, i -> 0x0b), fill(13, i -> i), fill(10, i -> 0xf0 + i), 42)),
                    "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
            eq("RFC 5869 A.2", hexs(Hkdf.sha256(fill(80, i -> i), fill(80, i -> 0x60 + i), fill(80, i -> 0xb0 + i), 82)),
                    "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87");
            eq("RFC 5869 A.3 (no salt)", hexs(Hkdf.sha256(fill(22, i -> 0x0b), null, new byte[0], 42)),
                    "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
        });
    }

    // ---------------------------------------------------------------- AdbPairingTest.kt

    static void pairingTests() throws Exception {
        AdbTls client = TestTls.tls(TestTls.client());
        test("pairsWithFakeServer", () -> {
            try (FakePairingServer server = new FakePairingServer("123456")) {
                AdbPeerInfo peer = new AdbPairing(client).pair("127.0.0.1", server.port(), "123456");
                server.join();
                eq("server error", server.error, null);
                eq("peer type (GUID)", peer.type, 1);
                eq("peer GUID", peer.data, "adb-FAKE0001-abc123");
                eq("device got our adb key", server.receivedKey, TestTls.client().getAdbPublicKey());
                eq("TLS client cert is our key", hexs(server.clientCertKey), hexs(TestTls.client().getPublicKey().getEncoded()));
            }
        });
        test("wrongCodeIsReported", () -> {
            try (FakePairingServer server = new FakePairingServer("123456")) {
                AdbPairingException e = thrown("wrong code", AdbPairingException.class, () -> new AdbPairing(client).pair("127.0.0.1", server.port(), "654321"));
                eq("wrong code reason", reason(e), "WRONG_CODE");
                server.join();
                eq("device got no key", server.receivedKey, null);
            }
        });
        test("deviceClosingAfterOurPeerInfoMeansWrongCode", () -> {
            try (FakePairingServer server = new FakePairingServer("1", FakePairingServer.Behavior.CLOSE_AFTER_SPAKE)) {
                AdbPairingException e = thrown("closed after spake", AdbPairingException.class, () -> new AdbPairing(client).pair("127.0.0.1", server.port(), "1"));
                eq("closed after spake reason", reason(e), "WRONG_CODE");
            }
        });
        test("protocolViolationsAreRejected", () -> {
            for (FakePairingServer.Behavior b : new FakePairingServer.Behavior[] {FakePairingServer.Behavior.BAD_HEADER_VERSION, FakePairingServer.Behavior.WRONG_TYPE}) {
                try (FakePairingServer server = new FakePairingServer("1", b)) {
                    AdbPairingException e = thrown(b.name(), AdbPairingException.class, () -> new AdbPairing(client).pair("127.0.0.1", server.port(), "1"));
                    eq(b.name() + " reason", reason(e), "PROTOCOL");
                }
            }
        });
        test("pairingUsesTheVerifiedMappedLoopbackAddress", () -> {
            try (FakePairingServer server = new FakePairingServer("123456")) {
                AdbPeerInfo peer = new AdbPairing(client).pair(" [::ffff:127.0.0.1] ", server.port(), "123456");
                server.join();
                eq("server error", server.error, null);
                eq("peer GUID via mapped loopback", peer.data, "adb-FAKE0001-abc123");
            }
        });
        test("pairingRejectsHostileLookalikeHostsBeforeConnecting", () -> {
            for (String host : new String[] {"127.evil.com", "127.0.0.1.example.com", "localhost.example.com"}) {
                AdbPairingException e = thrown(host, AdbPairingException.class, () -> new AdbPairing(client).pair(host, 5555, "123456", 50));
                eq(host + " reason", reason(e), "NOT_LOCAL");
            }
        });
        test("connectFailureIsReported", () -> {
            int port;
            try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
            AdbPairingException e = thrown("nothing listening", AdbPairingException.class, () -> new AdbPairing(client).pair("127.0.0.1", port, "1", 2000));
            eq("nothing listening reason", reason(e), "CONNECT_FAILED");
        });
        // Mika's version also checks Conscrypt's exporter on both ends; the JDK has none, so only the TLS 1.3 part ports.
        test("tls13Negotiated", () -> {
            try (ServerSocket ss = new ServerSocket(0, 1, loopback4())) {
                AdbTls serverTls = TestTls.tls(TestTls.device());
                Thread t = new Thread(() -> {
                    try (Socket p = ss.accept()) {
                        SSLSocket s = serverTls.wrap(p, new InetSocketAddress(p.getLocalAddress(), ss.getLocalPort()), false);
                        s.getInputStream().read();
                    } catch (Exception ignored) {
                    }
                });
                t.start();
                try (SSLSocket s = client.connect(new InetSocketAddress(loopback4(), ss.getLocalPort()), 5000)) {
                    eq("protocol", s.getSession().getProtocol(), "TLSv1.3");
                    s.getOutputStream().write(0);
                    t.join(5000);
                }
            }
        });
        test("packetFraming", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, fill(0x1234, i -> 7));
            byte[] bytes = out.toByteArray();
            eq("header", hexs(Arrays.copyOf(bytes, 6)), hexs(new byte[] {1, 1, 0, 0, 0x12, 0x34}));
            eq("size", bytes.length, 0x1234 + 6);
            byte[] back = AdbPairing.readPacket(new DataInputStream(new ByteArrayInputStream(bytes)), AdbPairing.TYPE_PEER_INFO);
            eq("payload back", back.length, 0x1234);
            eq("EOF before header", AdbPairing.readPacket(new DataInputStream(new ByteArrayInputStream(new byte[0])), (byte) 0), null);
            byte[] tooBig = {1, 0, 0, 0, 0x40, 0x01};
            thrown("too big", AdbPairingException.class, () -> AdbPairing.readPacket(new DataInputStream(new ByteArrayInputStream(tooBig)), (byte) 0));
        });
    }

    // ---------------------------------------------------------------- AdbIdentityTest (in AdbPairingTest.kt)

    static void identityTests() throws Exception {
        test("publicKeyStructure", () -> {
            AdbIdentity id = TestTls.client();
            byte[] raw = AdbPublicKey.encode(id.getPublicKey());
            eq("size", raw.length, 524);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            eq("words", bb.getInt(), 64);
            long n0inv = bb.getInt() & 0xffffffffL;
            BigInteger nn = EdPoint.fromLe(Arrays.copyOfRange(raw, 8, 264));
            BigInteger rr = EdPoint.fromLe(Arrays.copyOfRange(raw, 264, 520));
            bb.position(520);
            eq("exponent", bb.getInt(), 65537);
            eq("modulus", nn, id.getPublicKey().getModulus());
            BigInteger r32 = BigInteger.ONE.shiftLeft(32);
            eq("n0inv * n = -1 mod 2^32", BigInteger.valueOf(n0inv).multiply(nn).mod(r32), r32.subtract(BigInteger.ONE));
            eq("rr", rr, BigInteger.ONE.shiftLeft(4096).mod(nn));
            String[] parts = id.getAdbPublicKey().split(" ", 2);
            eq("base64 part", hexs(Base64.getDecoder().decode(parts[0])), hexs(raw));
            eq("name part", parts[1], "mika@test");
        });
        test("adbKeyNameHasNoWhitespace", () -> {
            AdbIdentity id = new AdbIdentity(TestTls.client().keyPair, "mika@Pixel 4\tXL");
            String k = id.getAdbPublicKey();
            eq("whitespace becomes _", k.substring(k.indexOf(' ') + 1), "mika@Pixel_4_XL");
        });
        test("selfSignedCertificateParsesAndVerifies", () -> {
            X509Certificate cert = TestTls.client().getCertificate();
            eq("version", cert.getVersion(), 3);
            eq("subject", cert.getSubjectX500Principal().getName(), "CN=Mika");
            eq("self-issued", cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
            eq("sig alg", cert.getSigAlgName(), "SHA256withRSA");
            eq("key", hexs(cert.getPublicKey().getEncoded()), hexs(TestTls.client().getPublicKey().getEncoded()));
            cert.verify(TestTls.client().getPublicKey());
            cert.checkValidity();
            eq("not before = epoch", cert.getNotBefore().getTime(), 0L);
        });
        test("derLongLengths", () -> {
            eq("0x80", hexs(Arrays.copyOf(SelfSignedCertificate.tlv(4, new byte[0x80]), 3)), "048180");
            eq("0x100", hexs(Arrays.copyOf(SelfSignedCertificate.tlv(4, new byte[0x100]), 4)), "04820100");
        });
        test("wrappedKeyFileRoundTripAndIdentitySource", () -> {
            File dir = tempDir();
            SecretKey aes = aesKey();
            File file = new File(dir, "k.bin");
            WrappedKeyFile store = new WrappedKeyFile(file, () -> aes);
            eq("nothing stored", store.read(), null);
            AdbIdentity a = new AdbIdentitySource(store, () -> "mika@x").forConnect();
            byte[] raw = Files.readAllBytes(file.toPath());
            ok("not stored in clear", !new String(raw, StandardCharsets.ISO_8859_1)
                    .contains(new String(Arrays.copyOfRange(a.keyPair.getPrivate().getEncoded(), 30, 60), StandardCharsets.ISO_8859_1)));
            // another process (a new source) reads back the same key
            AdbIdentity b = new AdbIdentitySource(store, () -> "mika@x").forConnect();
            eq("same public key", b.getPublicKey(), a.getPublicKey());
            eq("same adb key", b.getAdbPublicKey(), a.getAdbPublicKey());

            // a different wrapping key (e.g. Keystore wiped) can't read it: reported, key not replaced, file untouched
            SecretKey other = aesKey();
            WrappedKeyFile otherStore = new WrappedKeyFile(file, () -> other);
            thrown("other wrapping key", AdbKeyUnreadableException.class, otherStore::read);
            AdbIdentitySource src = new AdbIdentitySource(otherStore, () -> "mika@x");
            thrown("connect with unreadable key", AdbKeyUnreadableException.class, src::forConnect);
            eq("state", src.getState(), AdbKeyState.Unreadable);
            eq("file untouched", hexs(Files.readAllBytes(file.toPath())), hexs(raw));
            store.clear();
            eq("cleared", store.read(), null);
        });
        test("wrappedKeyFileThatIsEmptyOrTruncatedIsUnreadableNotAbsent", () -> {
            File dir = tempDir();
            SecretKey aes = aesKey();
            File file = new File(dir, "k.bin");
            WrappedKeyFile store = new WrappedKeyFile(file, () -> aes);
            Files.write(file.toPath(), new byte[0]);
            thrown("empty file", AdbKeyUnreadableException.class, store::read);
            Files.write(file.toPath(), new byte[] {12, 1, 2, 3});
            thrown("truncated file", AdbKeyUnreadableException.class, store::read);
        });
    }

    static SecretKey aesKey() throws Exception {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }

    // ---------------------------------------------------------------- AdbIdentitySourceTest.kt

    static final class MemKeyStore implements AdbKeyStore {
        byte[] bytes;
        int writes = 0;
        boolean unreadable = false;

        MemKeyStore() { this(null); }
        MemKeyStore(byte[] bytes) { this.bytes = bytes; }

        @Override public byte[] read() throws AdbKeyUnreadableException {
            if (unreadable) throw new AdbKeyUnreadableException("cannot unwrap");
            return bytes == null ? null : bytes.clone();
        }
        @Override public void write(byte[] pkcs8) { bytes = pkcs8.clone(); writes++; }
        @Override public void clear() { bytes = null; unreadable = false; }
    }

    /** The same key, but the platform hands back an RSA private key without CRT parameters (the encoding writes the public exponent as 0). */
    static RSAPrivateKey nonCrt(RSAPrivateCrtKey key) throws Exception {
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateKeySpec(key.getModulus(), key.getPrivateExponent()));
    }

    static void identitySourceTests() throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) TestTls.client().keyPair.getPrivate();
        test("generatedKeyRoundTripsToTheIdenticalPublicKey", () -> {
            // Keys stored before an upgrade are exactly generate()'s private-key encoding: reading them back must give the same public key, so paired devices needn't re-pair
            for (int i = 0; i < 3; i++) {
                AdbIdentity id = AdbIdentity.generate("mika@x");
                AdbIdentity back = AdbIdentity.fromPkcs8(id.keyPair.getPrivate().getEncoded(), "mika@x");
                eq("public key round trip", hexs(back.getPublicKey().getEncoded()), hexs(id.getPublicKey().getEncoded()));
                eq("adb key round trip", back.getAdbPublicKey(), id.getAdbPublicKey());
                eq("cert key round trip", hexs(back.getCertificate().getPublicKey().getEncoded()), hexs(id.getCertificate().getPublicKey().getEncoded()));
            }
        });
        test("nonCrtPrivateKeyStillParsesToTheSamePublicKey", () -> {
            RSAPrivateKey key = nonCrt(crt);
            ok("really not CRT", !(key instanceof RSAPrivateCrtKey));
            AdbIdentity id = AdbIdentity.fromPkcs8(key.getEncoded(), "mika@x");
            eq("same public key", id.getPublicKey(), TestTls.client().getPublicKey());
            eq("exponent", id.getPublicKey().getPublicExponent(), BigInteger.valueOf(65537));
            id.getCertificate().verify(TestTls.client().getPublicKey());
        });
        test("publicExponentComesFromTheEncodingWhenTheKeyObjectLacksIt", () -> {
            eq("from CRT encoding", Pkcs8Rsa.publicExponent(crt.getEncoded()), BigInteger.valueOf(65537));
            // the non-CRT encoding writes 0: treated as unknown
            eq("non-CRT encoding", Pkcs8Rsa.publicExponent(nonCrt(crt).getEncoded()), BigInteger.ZERO);
            eq("garbage", Pkcs8Rsa.publicExponent(new byte[] {0x30, 0x05, 1}), null);
            // non-CRT key object but the encoding has the exponent: use the encoding's
            eq("key without CRT, encoding with exponent", AdbIdentity.publicExponent(nonCrt(crt), crt.getEncoded()), BigInteger.valueOf(65537));
        });
        test("exponentThatIsNotF4AndNotStoredIsRejectedNotGuessed", () -> {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(2048, BigInteger.valueOf(65539)));
            RSAPrivateCrtKey odd = (RSAPrivateCrtKey) g.generateKeyPair().getPrivate();
            RSAPrivateKey stripped = nonCrt(odd);
            thrown("odd exponent, not stored", AdbKeyUnreadableException.class, () -> AdbIdentity.fromPkcs8(stripped.getEncoded(), "mika@x"));
            // with the exponent in the encoding it reads as usual
            eq("odd exponent, stored", AdbIdentity.fromPkcs8(odd.getEncoded(), "mika@x").getPublicKey().getPublicExponent(), BigInteger.valueOf(65539));
        });
        test("garbageIsUnreadable", () ->
            thrown("garbage", AdbKeyUnreadableException.class, () -> AdbIdentity.fromPkcs8(new byte[] {1, 2, 3}, "mika@x")));
        test("noKeyStoredCreatesAndStoresOne", () -> {
            MemKeyStore store = new MemKeyStore();
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x");
            eq("state before", src.getState(), AdbKeyState.NotLoaded);
            AdbIdentity id = src.forConnect();
            eq("state after", src.getState(), AdbKeyState.Created);
            eq("writes", store.writes, 1);
            eq("stored", hexs(store.bytes), hexs(id.keyPair.getPrivate().getEncoded()));
            AdbIdentitySource again = new AdbIdentitySource(store, () -> "mika@x");
            eq("loaded again", again.forConnect().getPublicKey(), id.getPublicKey());
            eq("state loaded", again.getState(), AdbKeyState.Loaded);
            eq("no new write", store.writes, 1);
        });
        test("unreadableStoredKeyIsReportedAndNeverOverwrittenByConnect", () -> {
            byte[] garbage = {0x30, 0x03, 2, 1, 0};
            MemKeyStore store = new MemKeyStore(garbage);
            List<String> logged = new ArrayList<>();
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x", (msg, e) -> logged.add(msg));
            thrown("unreadable 1", AdbKeyUnreadableException.class, src::forConnect);
            thrown("unreadable 2", AdbKeyUnreadableException.class, src::forConnect);
            eq("state", src.getState(), AdbKeyState.Unreadable);
            ok("problem set", src.getProblem() != null);
            ok("logged", !logged.isEmpty());
            eq("bytes untouched", hexs(store.bytes), hexs(garbage));
            eq("no writes", store.writes, 0);

            // same when it can't be unwrapped (wrapping key gone)
            MemKeyStore locked = new MemKeyStore(TestTls.client().keyPair.getPrivate().getEncoded());
            locked.unreadable = true;
            thrown("locked", AdbKeyUnreadableException.class, () -> new AdbIdentitySource(locked, () -> "mika@x").forConnect());
            eq("locked: no writes", locked.writes, 0);
        });
        test("pairingReplacesAnUnreadableKeyAndConnectThenUsesTheNewOne", () -> {
            MemKeyStore store = new MemKeyStore(new byte[] {9, 9, 9});
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x");
            AdbIdentity paired = src.forPairing();
            eq("state", src.getState(), AdbKeyState.Created);
            eq("problem", src.getProblem(), null);
            eq("stored", hexs(store.bytes), hexs(paired.keyPair.getPrivate().getEncoded()));
            ok("connect gets the same object", paired == src.forConnect());
        });
        test("pairingReusesAReadableKey", () -> {
            byte[] stored = TestTls.client().keyPair.getPrivate().getEncoded();
            MemKeyStore store = new MemKeyStore(stored);
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x");
            eq("same key", src.forPairing().getPublicKey(), TestTls.client().getPublicKey());
            eq("no writes", store.writes, 0);
        });
        test("pairAndConnectInOneProcessShareOneIdentityEvenIfTheStoreFailsLater", () -> {
            MemKeyStore store = new MemKeyStore();
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x");
            AdbIdentity forPair = src.forPairing();
            // the stored copy goes bad later: this process keeps using the pairing key
            store.unreadable = true;
            ok("connect: same object", forPair == src.forConnect());
            ok("pairing: same object", forPair == src.forPairing());
        });
        test("forgetClearsTheCacheAndTheStore", () -> {
            MemKeyStore store = new MemKeyStore();
            AdbIdentitySource src = new AdbIdentitySource(store, () -> "mika@x");
            AdbIdentity first = src.forConnect();
            src.forget();
            eq("store cleared", store.bytes, null);
            eq("state", src.getState(), AdbKeyState.NotLoaded);
            AdbIdentity second = src.forPairing();
            ok("new key", !first.getPublicKey().equals(second.getPublicKey()));
            eq("new key stored", hexs(store.bytes), hexs(second.keyPair.getPrivate().getEncoded()));
        });
    }

    // ---------------------------------------------------------------- AdbConnectionTest.kt

    /**
     * In-process fake adbd: CNXN -> STLS -> TLS (client certificate required, only keys in authorized accepted) -> CNXN,
     * then a few exec services. Single-threaded event loop; every WRTE it sends waits for the client's OKAY first.
     * Sprocket addition: "shell:echo ..." is served too (for AdbConnection.shell); "shell:nope" is still refused.
     */
    static final class FakeAdbd implements AutoCloseable {
        enum Mode { TLS, PLAIN, LEGACY_AUTH }

        private final List<AdbIdentity> authorized;
        private final Mode mode;
        private final int maxData;
        private final ServerSocket server;
        final CopyOnWriteArrayList<AdbMessage> received = new CopyOnWriteArrayList<>();
        volatile Throwable error;
        private volatile Socket current;
        private final LinkedBlockingQueue<Boolean> sessionsEnded = new LinkedBlockingQueue<>();
        private final AdbTls tls;
        private final Thread worker;

        private static final class Stream {
            final int local, remote;
            final String service;
            final ArrayDeque<byte[]> pending = new ArrayDeque<>();
            boolean awaitingAck = false;
            boolean closeWhenDrained = false;

            Stream(int local, int remote, String service) { this.local = local; this.remote = remote; this.service = service; }
        }

        FakeAdbd(List<AdbIdentity> authorized) throws Exception { this(authorized, Mode.TLS, 4096); }

        FakeAdbd(List<AdbIdentity> authorized, Mode mode, int maxData) throws Exception {
            this.authorized = authorized;
            this.mode = mode;
            this.maxData = maxData;
            server = new ServerSocket(0, 4, loopback4());
            tls = TestTls.tls(TestTls.device());
            worker = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        current = s;
                        try {
                            serve(s);
                        } catch (IOException e) {
                            // client disconnected
                        } finally {
                            s.close();
                            sessionsEnded.offer(true);
                        }
                    }
                } catch (IOException e) {
                    // closed
                } catch (Throwable t) {
                    error = t;
                }
            }, "fake-adbd");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return server.getLocalPort(); }

        private AdbMessage readMsg(DataInputStream input) throws IOException {
            AdbMessage m = AdbMessage.read(input, 1 << 20, true);
            if (m != null) received.add(m);
            return m;
        }

        private static void check(boolean c, String msg) { if (!c) throw new IllegalStateException(msg); }

        private void serve(Socket plain) throws Exception {
            DataInputStream input = new DataInputStream(plain.getInputStream());
            OutputStream out = plain.getOutputStream();
            AdbMessage cnxn = readMsg(input);
            if (cnxn == null) return;
            check(cnxn.command == AdbMessage.CNXN && cnxn.arg0 == AdbMessage.VERSION
                    && new String(cnxn.data, StandardCharsets.US_ASCII).equals("host::\u0000"), "Check failed.");
            switch (mode) {
                case LEGACY_AUTH:
                    out.write(new AdbMessage(AdbMessage.AUTH, 1, 0, new byte[20]).encode()); out.flush(); input.read(); return;
                case PLAIN:
                    break;
                case TLS: {
                    out.write(new AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode()); out.flush();
                    AdbMessage stls = readMsg(input);
                    if (stls == null) return;
                    check(stls.command == AdbMessage.STLS && stls.arg0 == AdbMessage.STLS_VERSION, "Check failed.");
                    SSLSocket ssl = tls.wrap(plain, new InetSocketAddress(plain.getLocalAddress(), port()), false);
                    byte[] key = ssl.getSession().getPeerCertificates()[0].getPublicKey().getEncoded();
                    boolean known = false;
                    for (AdbIdentity id : authorized) if (Arrays.equals(id.getPublicKey().getEncoded(), key)) known = true;
                    if (!known) { ssl.close(); return; }
                    input = new DataInputStream(ssl.getInputStream());
                    out = ssl.getOutputStream();
                    break;
                }
            }
            final OutputStream o = out;
            Consumer<AdbMessage> send = m -> {
                try { o.write(m.encode()); o.flush(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            };
            send.accept(new AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, maxData, "device::ro.product.name=fake;features=shell_v2\u0000".getBytes(StandardCharsets.UTF_8)));

            Map<Integer, Stream> streams = new HashMap<>();
            int nextId = 100;
            try {
                while (true) {
                    AdbMessage m = readMsg(input);
                    if (m == null) return;
                    check(m.data.length <= maxData, "client ignored maxdata");
                    switch (m.command) {
                        case AdbMessage.OPEN: {
                            check(m.arg1 == 0, "Check failed.");
                            String dest = new String(m.data, StandardCharsets.UTF_8);
                            check(dest.endsWith("\u0000"), "Check failed.");
                            String body = dest.substring(0, dest.length() - 1);
                            boolean exec = body.startsWith("exec:");
                            boolean shell = body.startsWith("shell:");
                            String cmd = exec ? body.substring(5) : shell ? body.substring(6) : body;
                            if (!(exec || shell) || !cmd.equals("cat") && !cmd.equals("big") && !cmd.startsWith("echo ")) {
                                send.accept(new AdbMessage(AdbMessage.CLSE, 0, m.arg0));
                                continue;
                            }
                            Stream st = new Stream(nextId++, m.arg0, cmd);
                            streams.put(st.local, st);
                            send.accept(new AdbMessage(AdbMessage.OKAY, st.local, st.remote));
                            if (cmd.startsWith("echo ")) {
                                st.pending.add((cmd.substring(5) + "\n").getBytes(StandardCharsets.UTF_8));
                                st.closeWhenDrained = true;
                            } else if (cmd.equals("big")) {
                                for (int i = 0; i < 3; i++) { byte v = (byte) (i + 1); st.pending.add(fill(maxData, x -> v)); }
                                st.closeWhenDrained = true;
                            }
                            pump(st, streams, send);
                            break;
                        }
                        case AdbMessage.OKAY: {
                            Stream st = streams.get(m.arg1);
                            if (st != null) { st.awaitingAck = false; pump(st, streams, send); }
                            break;
                        }
                        case AdbMessage.WRTE: {
                            Stream st = streams.get(m.arg1);
                            if (st != null) {
                                send.accept(new AdbMessage(AdbMessage.OKAY, st.local, st.remote));
                                if (st.service.equals("cat")) { st.pending.add(m.data); pump(st, streams, send); }
                            }
                            break;
                        }
                        case AdbMessage.CLSE: {
                            Stream st = streams.remove(m.arg1);
                            if (st != null) send.accept(new AdbMessage(AdbMessage.CLSE, st.local, st.remote));
                            break;
                        }
                        default:
                            break;
                    }
                }
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }

        private static void pump(Stream st, Map<Integer, Stream> streams, Consumer<AdbMessage> send) {
            if (st.awaitingAck) return;
            byte[] chunk = st.pending.pollFirst();
            if (chunk != null) {
                send.accept(new AdbMessage(AdbMessage.WRTE, st.local, st.remote, chunk));
                st.awaitingAck = true;
            } else if (st.closeWhenDrained) {
                send.accept(new AdbMessage(AdbMessage.CLSE, st.local, st.remote));
                streams.remove(st.local);
            }
        }

        /** Wait until the device side has finished this client's session; call before reading received after the client disconnects, or the last messages may not be recorded yet. */
        void awaitSessionEnd() throws InterruptedException {
            if (sessionsEnded.poll(5, TimeUnit.SECONDS) == null) throw new IllegalStateException("fake adbd session did not end");
        }

        /** Simulates the device dropping us (wireless debugging off, adbd restart). */
        void dropClients() throws IOException {
            Socket s = current;
            if (s != null) s.close();
        }

        @Override public void close() throws Exception {
            server.close();
            dropClients();
            worker.join(5000);
        }
    }

    static List<String> commands(List<AdbMessage> received, int command) {
        List<String> out = new ArrayList<>();
        for (AdbMessage m : received) if (m.command == command) out.add(new String(m.data, StandardCharsets.UTF_8));
        return out;
    }

    static int count(List<AdbMessage> received, int command) {
        int c = 0;
        for (AdbMessage m : received) if (m.command == command) c++;
        return c;
    }

    static void connectionTests() throws Exception {
        AdbTls tls = TestTls.tls(TestTls.client());
        List<AdbIdentity> paired = Collections.singletonList(TestTls.client());
        test("execReadsOutputUntilClose", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    ok("banner", c.banner.startsWith("device::ro.product.name=fake"));
                    eq("exec echo", c.exec("echo hello world").readText(), "hello world\n");
                    // another stream on the same connection
                    eq("second stream", c.exec("echo again").readText(), "again\n");
                }
                d.awaitSessionEnd();
                eq("adbd error", d.error, null);
                eq("OPEN destinations", commands(d.received, AdbMessage.OPEN), Arrays.asList("exec:echo hello world\u0000", "exec:echo again\u0000"));
            }
        });
        // Sprocket addition: the shell: service through AdbConnection.shell.
        test("shellReadsOutputUntilClose", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    eq("shell echo", c.shell("echo from shell").readText(), "from shell\n");
                }
                d.awaitSessionEnd();
                eq("OPEN destination", commands(d.received, AdbMessage.OPEN), Arrays.asList("shell:echo from shell\u0000"));
            }
        });
        test("largeOutputIsAcknowledgedPerPacket", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    byte[] out = c.exec("big").readAll();
                    eq("size", out.length, 3 * 4096);
                    eq("last byte", out[out.length - 1], (byte) 3);
                }
                d.awaitSessionEnd();
                ok("an OKAY per packet", count(d.received, AdbMessage.OKAY) >= 3);
            }
        });
        test("writeIsChunkedByDeviceMaxDataAndEchoed", () -> {
            try (FakeAdbd d = new FakeAdbd(paired, FakeAdbd.Mode.TLS, 1000)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    AdbConnection.AdbStream s = c.exec("cat");
                    byte[] data = fill(2500, i -> i);
                    s.write(data);
                    ByteArrayOutputStream got = new ByteArrayOutputStream();
                    while (got.size() < data.length) { byte[] b = s.read(); got.write(b, 0, b.length); }
                    eq("echoed", hexs(got.toByteArray()), hexs(data));
                    s.close();
                    eq("read after close", s.read(), null);
                }
                d.awaitSessionEnd();
                eq("adbd error", d.error, null);
                List<Integer> wrtes = new ArrayList<>();
                for (AdbMessage m : d.received) if (m.command == AdbMessage.WRTE) wrtes.add(m.data.length);
                eq("WRTE sizes", wrtes, Arrays.asList(1000, 1000, 500));
                ok("CLSE sent", count(d.received, AdbMessage.CLSE) > 0);
            }
        });
        test("inputStreamView", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.exec("echo abc").getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    int ch;
                    while ((ch = r.read()) >= 0) sb.append((char) ch);
                    eq("InputStream view", sb, "abc\n");
                }
            }
        });
        test("refusedServiceThrows", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    thrown("refused service", IOException.class, () -> c.open("shell:nope"));
                    eq("connection still works", c.exec("echo ok").readText(), "ok\n");
                }
            }
        });
        test("unpairedKeyIsUnauthorized", () -> {
            try (FakeAdbd d = new FakeAdbd(Collections.emptyList())) {
                AdbConnectionException e = thrown("unpaired", AdbConnectionException.class, () -> AdbConnection.open("127.0.0.1", d.port(), tls));
                eq("unpaired reason", reason(e), "UNAUTHORIZED");
            }
        });
        test("legacyAuthPortIsUnauthorized", () -> {
            try (FakeAdbd d = new FakeAdbd(paired, FakeAdbd.Mode.LEGACY_AUTH, 4096)) {
                AdbConnectionException e = thrown("legacy auth", AdbConnectionException.class, () -> AdbConnection.open("127.0.0.1", d.port(), tls));
                eq("legacy auth reason", reason(e), "UNAUTHORIZED");
            }
        });
        test("deviceWithoutAuthSkipsTls", () -> {
            try (FakeAdbd d = new FakeAdbd(Collections.emptyList(), FakeAdbd.Mode.PLAIN, 4096)) {
                try (AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls)) {
                    eq("plain exec", c.exec("echo p").readText(), "p\n");
                }
            }
        });
        test("localhostAndMappedLoopbackConnectThroughTheVerifiedAddress", () -> {
            // the original string has whitespace, brackets or IPv4-mapped form; only using the verified InetAddress directly can connect.
            for (String host : new String[] {" localhost. ", " [::ffff:127.0.0.1] "}) {
                try (FakeAdbd d = new FakeAdbd(paired)) {
                    try (AdbConnection c = AdbConnection.open(host, d.port(), tls)) {
                        eq(host, c.exec("echo ok").readText(), "ok\n");
                    }
                    d.awaitSessionEnd();
                    eq(host + " adbd error", d.error, null);
                }
            }
        });
        test("hostileLookalikeHostsAreRejectedBeforeConnecting", () -> {
            for (String host : new String[] {"127.evil.com", "127.0.0.1.example.com", "evil.localhost", "::ffff:192.0.2.1"}) {
                AdbConnectionException e = thrown(host, AdbConnectionException.class, () -> AdbConnection.open(host, 5555, tls, 50));
                eq(host + " reason", reason(e), "NOT_LOCAL");
            }
        });
        test("connectFailure", () -> {
            int port;
            try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
            AdbConnectionException e = thrown("nothing listening", AdbConnectionException.class, () -> AdbConnection.open("127.0.0.1", port, tls, 2000));
            eq("nothing listening reason", reason(e), "CONNECT_FAILED");
        });
        test("connectionLossEndsStreams", () -> {
            try (FakeAdbd d = new FakeAdbd(paired)) {
                AdbConnection c = AdbConnection.open("127.0.0.1", d.port(), tls);
                AdbConnection.AdbStream s = c.exec("cat");
                d.dropClients();
                eq("stream ends", s.read(), null);
                thrown("write after loss", IOException.class, () -> s.write(new byte[] {1}));
                thrown("open after loss", IOException.class, () -> c.exec("echo x"));
                ok("closed", c.isClosed());
            }
        });
    }

    static void messageTests() {
        test("encodesHeaderLittleEndianWithChecksumAndMagic", () -> {
            AdbMessage m = new AdbMessage(AdbMessage.OPEN, 1, 0, "exec:id\u0000".getBytes(StandardCharsets.UTF_8));
            byte[] b = m.encode();
            eq("size", b.length, 24 + 8);
            eq("command", new String(Arrays.copyOf(b, 4), StandardCharsets.US_ASCII), "OPEN");
            eq("arg0", hexs(Arrays.copyOfRange(b, 4, 8)), "01000000");
            eq("length", hexs(Arrays.copyOfRange(b, 12, 16)), "08000000");
            int sum = 0;
            for (char ch : "exec:id".toCharArray()) sum += ch;
            eq("checksum", (b[16] & 0xff) | ((b[17] & 0xff) << 8), sum);
            eq("magic", hexs(Arrays.copyOfRange(b, 20, 24)), "b0afbab1");
            AdbMessage back = AdbMessage.read(new DataInputStream(new ByteArrayInputStream(b)), AdbMessage.MAX_PAYLOAD, true);
            eq("command back", back.command, AdbMessage.OPEN);
            eq("data back", hexs(back.data), hexs(m.data));
            eq("name OPEN", AdbMessage.name(AdbMessage.OPEN), "OPEN");
            eq("name STLS", AdbMessage.name(AdbMessage.STLS), "STLS");
            eq("name CNXN", AdbMessage.name(AdbMessage.CNXN), "CNXN");
        });
        test("rejectsCorruptPackets", () -> {
            byte[] b = new AdbMessage(AdbMessage.WRTE, 1, 2, new byte[] {1, 2, 3}).encode();
            byte[] badMagic = b.clone();
            badMagic[20] = 0;
            thrown("bad magic", IOException.class, () -> AdbMessage.read(new DataInputStream(new ByteArrayInputStream(badMagic))));
            byte[] badSum = b.clone();
            badSum[24] = 9;
            thrown("bad checksum", IOException.class, () -> AdbMessage.read(new DataInputStream(new ByteArrayInputStream(badSum)), AdbMessage.MAX_PAYLOAD, true));
            ok("bad checksum ignored when not verifying", AdbMessage.read(new DataInputStream(new ByteArrayInputStream(badSum)), AdbMessage.MAX_PAYLOAD, false) != null);
            byte[] tooBig = new AdbMessage(AdbMessage.WRTE, 1, 2, new byte[10]).encode();
            thrown("too big", IOException.class, () -> AdbMessage.read(new DataInputStream(new ByteArrayInputStream(tooBig)), 5));
            eq("EOF before header", AdbMessage.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))), null);
        });
    }

    // ---------------------------------------------------------------- AdbHostsTest.kt

    static void hostsTests() {
        test("loopbackLiteralsAndExactLocalhostAreOwn", () -> {
            for (String host : new String[] {
                "127.0.0.1", "127.255.255.254", "127.000.000.001", " 127.0.0.1 ",
                "::1", "[::1]", " [::1%0] ", "0:0:0:0:0:0:0:1",
                "::ffff:127.0.0.1", "[::ffff:127.255.255.254]", "0:0:0:0:0:ffff:7f00:1",
                "localhost", "LOCALHOST.", " localhost ",
            }) {
                ok("own: " + host, AdbHosts.ownAddress(host) != null);
            }
            // localhost doesn't consult hosts/DNS and doesn't accept subdomains; it is pinned to this verified IPv4 loopback.
            eq("localhost is 127.0.0.1", hexs(AdbHosts.ownAddress("LOCALHOST.").getAddress()), "7f000001");
            InetSocketAddress target = AdbHosts.ownSocketAddress("[::1]", 5555);
            ok("socket address", target != null && !target.isUnresolved());
        });
        test("everyAddressOnThisMachineIncludingScopedIpv6IsOwn", () -> {
            List<InetAddress> own = new ArrayList<>();
            try {
                for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) own.addAll(Collections.list(nic.getInetAddresses()));
            } catch (Exception ignored) {
            }
            for (InetAddress address : own) {
                // hostAddress may carry %en0 or %3; keep the scope: what connects after the check must be this same address.
                ok("own: " + address.getHostAddress(), AdbHosts.ownAddress(address.getHostAddress()) != null);
                if (address instanceof Inet4Address) {
                    ok("own: ::ffff:" + address.getHostAddress(), AdbHosts.ownAddress("::ffff:" + address.getHostAddress()) != null);
                }
            }
        });
        test("lookalikeNamesAndNonCanonicalNumbersAreRejectedWithoutResolution", () -> {
            for (String host : new String[] {
                "127.evil.com", "127.0.0.1.example.com", "localhost.example.com", "evil.localhost", ".localhost",
                "adb.example.com", "my-phone.local", "127.0.0.1@evil.com", "http://127.0.0.1",
                "127.1", "2130706433", "0x7f000001", "127.0.0.1.", "256.1.1.1", "1..2.3", "1.2.3.4.5",
            }) {
                eq("rejected: " + host, AdbHosts.ownAddress(host), null);
            }
        });
        test("otherMachinesAndMappedOrTranslatedRemoteAddressesAreRejected", () -> {
            for (String host : new String[] {
                "192.0.2.1", "203.0.113.9", "8.8.8.8", "2001:db8::1",
                "::ffff:192.0.2.1", "::ffff:8.8.8.8", "::127.0.0.1", "::ffff:0:127.0.0.1", "64:ff9b::7f00:1",
            }) {
                eq("rejected: " + host, AdbHosts.ownAddress(host), null);
            }
        });
        test("malformedIpv6BracketsAndScopesAreRejected", () -> {
            for (String host : new String[] {
                "", "   ", "[127.0.0.1]", "[::1", "::1]", "[[::1]]", "[ ::1 ]", "::1%", "::1%a%b",
                "::1%interface-that-does-not-exist", "1::2::3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::",
                "::ffff:999.0.0.1", ":::1", "127.0.0. 1",
            }) {
                eq("rejected: <" + host + ">", AdbHosts.ownAddress(host), null);
            }
        });
    }

    // ---------------------------------------------------------------- AdbConnectPortTest.kt

    static final class Cache implements PortCache {
        Integer port;
        Cache() { this(null); }
        Cache(Integer port) { this.port = port; }
        @Override public Integer load() { return port; }
        @Override public void save(int port) { this.port = port; }
    }

    /** Fake scan: open = open ports per address; only those in adb answer STLS. Records which addresses were swept and which ports skipped. */
    static final class FakeScanner implements LocalPortScanner {
        final Map<String, List<Integer>> open;
        final Set<AdbEndpoint> adb;
        final List<String> swept = new ArrayList<>();
        Set<Integer> skipped = Collections.emptySet();

        FakeScanner(Map<String, List<Integer>> open, Set<AdbEndpoint> adb) { this.open = open; this.adb = adb; }

        @Override public List<Integer> openPorts(String host, PortRange ports, Set<Integer> skip, long budgetMs) {
            swept.add(host);
            skipped = skip;
            List<Integer> out = new ArrayList<>();
            for (int p : open.getOrDefault(host, Collections.emptyList())) if (ports.contains(p) && !skip.contains(p)) out.add(p);
            return out;
        }

        @Override public boolean speaksAdbTls(String host, int port, int timeoutMs) { return adb.contains(new AdbEndpoint(host, port)); }
    }

    static <K, V> Map<K, V> mapOf(Object... kv) {
        Map<K, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kv[i];
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put(k, v);
        }
        return m;
    }

    static Set<AdbEndpoint> setOf(AdbEndpoint... e) { return new HashSet<>(Arrays.asList(e)); }

    static LocalPortScan scanOf(LocalPortScanner scanner, String... hosts) { return new LocalPortScan(scanner, () -> Arrays.asList(hosts)); }

    static final String WIFI = "192.0.2.10";

    /** One test's state (Kotlin: the test class's fields). */
    static final class PortCase {
        final List<Integer> attempts = new ArrayList<>();
        int mdnsAsked = 0;

        AdbConnectPort chain(Integer prop, Integer mdns, Cache cache) { return chain(prop, mdns, cache, false, null); }

        AdbConnectPort chain(Integer prop, Integer mdns, Cache cache, boolean propThrows, List<Integer> mdnsAll) {
            return new AdbConnectPort(
                    () -> { if (propThrows) throw new SecurityException("denied"); return prop; },
                    (type, timeout) -> {
                        mdnsAsked++;
                        if (mdnsAll != null) return mdnsAll;
                        return mdns == null ? Collections.<Integer>emptyList() : Collections.singletonList(mdns);
                    },
                    cache);
        }

        AdbConnectPort.Opener<String> openerRejecting(Integer unauthorized, int... ok) {
            return (host, port) -> {
                attempts.add(port);
                if (unauthorized != null && port == unauthorized) throw new AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "no");
                for (int p : ok) if (p == port) return "conn@" + port;
                throw new AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "closed");
            };
        }

        AdbConnectPort.Opener<String> opener(int... ok) { return openerRejecting(null, ok); }

        /** The first rejectFirst connections to port are rejected over TLS (reason), then it connects; other ports per rest. */
        AdbConnectPort.Opener<String> flaky(int port, int rejectFirst, AdbConnectPort.Opener<String> rest, AdbConnectionException.Reason reason) {
            int[] left = {rejectFirst};
            return (host, p) -> {
                if (p == port && left[0] > 0) {
                    attempts.add(p);
                    left[0]--;
                    throw new AdbConnectionException(reason, "tls");
                }
                return rest.open(host, p);
            };
        }
    }

    static AdbConnectionException connectFails(String what, AdbConnectPort port, AdbConnectPort.Opener<String> opener) {
        return thrown(what, AdbConnectionException.class, () -> port.connect(opener));
    }

    static void connectPortTests() {
        test("cachedFirstThenPropertyAndMdnsNotAskedWhenItWorks", () -> {
            PortCase t = new PortCase();
            // the stale cached port is refused quickly, then the property is tried
            Cache cache = new Cache(1111);
            eq("connects via property", t.chain(4000, 5000, cache).connect(t.opener(4000)), "conn@4000");
            eq("attempts", t.attempts, Arrays.asList(1111, 4000));
            eq("mdns not asked", t.mdnsAsked, 0);
            eq("cached", cache.port, 4000);
            t.attempts.clear();
            eq("again", t.chain(4000, 5000, cache).connect(t.opener(4000)), "conn@4000");
            eq("cache hit first", t.attempts, Arrays.asList(4000));
        });
        test("unreadableOrZeroPropertyFallsToMdnsThenCache", () -> {
            PortCase t = new PortCase();
            Cache cache = new Cache(1111);
            eq("property 0 -> mdns", t.chain(0, 5000, cache).connect(t.opener(5000)), "conn@5000");
            eq("cached", cache.port, 5000);
            t.attempts.clear();
            eq("property throws", t.chain(null, null, new Cache(5000), true, null).connect(t.opener(5000)), "conn@5000");
            eq("attempts", t.attempts, Arrays.asList(5000));
        });
        test("stalePortsAreSkippedAndDuplicatesTriedOnce", () -> {
            PortCase t = new PortCase();
            // cache and property are both the old port (neither connects), mDNS gives the same old port again, only the last is right
            Cache cache = new Cache(4000);
            eq("connects", t.chain(4000, null, cache, false, Arrays.asList(4000, 6000)).connect(t.opener(6000)), "conn@6000");
            eq("attempts", t.attempts, Arrays.asList(4000, 6000));
        });
        test("tlsRejectionOnceIsRetriedOnTheSamePort", () -> {
            PortCase t = new PortCase();
            // some systems let another service take the first TLS handshake and reject every certificate; the next connection reaches adbd
            Cache cache = new Cache();
            eq("connects after retry", t.chain(4000, 5000, cache).connect(t.flaky(4000, 1, t.opener(4000), AdbConnectionException.Reason.UNAUTHORIZED)), "conn@4000");
            eq("attempts", t.attempts, Arrays.asList(4000, 4000));
            eq("cached", cache.port, 4000);
            t.attempts.clear();
            eq("TLS_FAILED retried too", t.chain(4000, null, new Cache()).connect(t.flaky(4000, 1, t.opener(4000), AdbConnectionException.Reason.TLS_FAILED)), "conn@4000");
            eq("attempts 2", t.attempts, Arrays.asList(4000, 4000));
        });
        test("notLocalStopsImmediatelyInsteadOfTryingOtherPorts", () -> {
            PortCase t = new PortCase();
            AdbConnectionException e = connectFails("NOT_LOCAL", t.chain(4000, 5000, new Cache()), (host, port) -> {
                t.attempts.add(port);
                throw new AdbConnectionException(AdbConnectionException.Reason.NOT_LOCAL, "not local");
            });
            eq("reason", reason(e), "NOT_LOCAL");
            eq("attempts", t.attempts, Arrays.asList(4000));
        });
        test("aCandidateThatKeepsRejectingDoesNotStopTheOthers", () -> {
            PortCase t = new PortCase();
            Cache cache = new Cache();
            eq("connects", t.chain(null, null, cache, false, Arrays.asList(5000, 5001)).connect(t.openerRejecting(5000, 5001)), "conn@5001");
            eq("attempts", t.attempts, Arrays.asList(5000, 5000, 5001));
            eq("cached", cache.port, 5001);
        });
        test("rejectedEverywhereIsUnauthorized", () -> {
            PortCase t = new PortCase();
            // the only port that answers rejects twice, the rest are stale: the key really isn't accepted, pair again; no local scan
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(41000)), setOf(new AdbEndpoint("127.0.0.1", 41000)));
            AdbConnectPort port = new AdbConnectPort.Builder(() -> 4000, (type, timeout) -> Arrays.asList(5000), new Cache(3000))
                    .scan(scanOf(scanner, "127.0.0.1")).build();
            AdbConnectionException e = connectFails("rejected everywhere", port, t.openerRejecting(4000, 41000));
            eq("reason", reason(e), "UNAUTHORIZED");
            eq("attempts", t.attempts, Arrays.asList(3000, 4000, 4000, 5000));
            ok("not scanned", scanner.swept.isEmpty());
        });
        test("nothingFoundIsConnectFailed", () -> {
            PortCase t = new PortCase();
            Cache cache = new Cache();
            eq("no candidates", reason(connectFails("no candidates", t.chain(null, null, cache), t.opener())), "CONNECT_FAILED");
            eq("not cached", cache.port, null);
            eq("dead property", reason(connectFails("dead property", t.chain(4000, null, cache), t.opener())), "CONNECT_FAILED");
            eq("still not cached", cache.port, null);
        });
        test("everyMdnsPortIsTriedWhenAStaleRecordComesFirst", () -> {
            PortCase t = new PortCase();
            // wireless debugging toggled off and on: the old port's record lingers and returns with the new one
            Cache cache = new Cache(4000);
            eq("connects", t.chain(null, null, cache, false, Arrays.asList(4000, 5000)).connect(t.opener(5000)), "conn@5000");
            eq("attempts", t.attempts, Arrays.asList(4000, 5000));
            eq("cached", cache.port, 5000);
        });
        // Every connection attempt reports how many candidates each source gave: successes and total failures alike.
        test("reportsCandidateCounts", () -> {
            PortCase t = new PortCase();
            List<ConnectCandidates> seen = new ArrayList<>();
            AdbConnectPort okPort = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Arrays.asList(5000, 5001), new Cache(4999))
                    .report(seen::add).build();
            eq("connects", okPort.connect(t.opener(5001)), "conn@5001");
            eq("report", seen, Arrays.asList(new ConnectCandidates(0, 2, 1, true)));
            seen.clear();
            AdbConnectPort none = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), new Cache())
                    .report(seen::add).build();
            eq("none", reason(connectFails("none", none, t.opener())), "CONNECT_FAILED");
            eq("report none", seen, Arrays.asList(new ConnectCandidates(0, 0, 0, false)));
        });
        test("scanRunsOnlyAfterEarlierSourcesFailAndTheHitIsCached", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(40000, 41235)), setOf(new AdbEndpoint("127.0.0.1", 41235)));
            Cache cache = new Cache(39000);
            List<ConnectCandidates> seen = new ArrayList<>();
            List<Boolean> progress = new ArrayList<>();
            AdbConnectPort port = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), cache)
                    .report(seen::add).scan(scanOf(scanner, "127.0.0.1", WIFI)).scanning(progress::add).build();
            eq("connects", port.connect(t.opener(41235)), "conn@41235");
            eq("attempts", t.attempts, Arrays.asList(39000, 41235));
            eq("cached", cache.port, 41235);
            // the already-tried cached port isn't scanned; found on loopback, so the Wi-Fi address isn't scanned
            ok("cached port skipped", scanner.skipped.contains(39000));
            eq("swept", scanner.swept, Arrays.asList("127.0.0.1"));
            eq("progress", progress, Arrays.asList(true, false));
            eq("report", seen, Arrays.asList(new ConnectCandidates(0, 0, 1, true, true, 2, 1, false)));
            // next time the cache connects directly, no scan
            t.attempts.clear();
            eq("again", port.connect(t.opener(41235)), "conn@41235");
            eq("attempts again", t.attempts, Arrays.asList(41235));
            eq("not swept again", scanner.swept, Arrays.asList("127.0.0.1"));
        });
        test("scanNotRunWhenAnEarlierSourceConnects", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(Collections.emptyMap(), Collections.emptySet());
            AdbConnectPort port = new AdbConnectPort.Builder(() -> 4000, (type, timeout) -> Collections.emptyList(), new Cache())
                    .scan(scanOf(scanner, "127.0.0.1")).build();
            eq("connects", port.connect(t.opener(4000)), "conn@4000");
            ok("not scanned", scanner.swept.isEmpty());
        });
        test("scanFallsToWifiAddressAndConnectsThere", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(40000), WIFI, Arrays.asList(40000, 45000)), setOf(new AdbEndpoint(WIFI, 45000)));
            List<String> hosts = new ArrayList<>();
            AdbConnectPort port = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), new Cache())
                    .scan(scanOf(scanner, "127.0.0.1", WIFI)).build();
            AdbConnectPort.Opener<String> inner = t.opener(45000);
            eq("connects", port.connect((host, p) -> { hosts.add(host); return inner.open(host, p); }), "conn@45000");
            eq("swept", scanner.swept, Arrays.asList("127.0.0.1", WIFI));
            eq("hosts", hosts, Arrays.asList(WIFI));
        });
        test("firstVerifiedScanHitWinsAndLaterOnesAreNotTried", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(41000, 42000)),
                    setOf(new AdbEndpoint("127.0.0.1", 41000), new AdbEndpoint("127.0.0.1", 42000)));
            AdbConnectPort port = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), new Cache())
                    .scan(scanOf(scanner, "127.0.0.1")).build();
            eq("first hit", port.connect(t.opener(41000, 42000)), "conn@41000");
            eq("attempts", t.attempts, Arrays.asList(41000));
            // when the first fails, the next is tried
            t.attempts.clear();
            eq("second hit", port.connect(t.opener(42000)), "conn@42000");
            eq("attempts 2", t.attempts, Arrays.asList(41000, 42000));
        });
        test("manualPortIsTriedLastWithoutScanningAndCachedOnSuccess", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(41235)), setOf(new AdbEndpoint("127.0.0.1", 41235)));
            Cache cache = new Cache();
            List<ConnectCandidates> seen = new ArrayList<>();
            AdbConnectPort port = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), cache)
                    .report(seen::add).scan(scanOf(scanner, "127.0.0.1")).manual(() -> 43000).build();
            eq("connects", port.connect(t.opener(43000)), "conn@43000");
            ok("not scanned", scanner.swept.isEmpty());
            eq("cached", cache.port, 43000);
            eq("report", seen, Arrays.asList(new ConnectCandidates(0, 0, 0, true, false, 0, 0, true)));
        });
        test("scanAndManualBothFailingIsConnectFailed", () -> {
            PortCase t = new PortCase();
            FakeScanner scanner = new FakeScanner(mapOf("127.0.0.1", Arrays.asList(40000)), Collections.emptySet());
            List<ConnectCandidates> seen = new ArrayList<>();
            AdbConnectPort port = new AdbConnectPort.Builder(() -> null, (type, timeout) -> Collections.emptyList(), new Cache())
                    .report(seen::add).scan(scanOf(scanner, "127.0.0.1")).build();
            eq("fails", reason(connectFails("scan finds nothing", port, t.opener())), "CONNECT_FAILED");
            eq("report", seen, Arrays.asList(new ConnectCandidates(0, 0, 0, false, true, 1, 0, false)));
        });
    }

    // ---------------------------------------------------------------- LocalPortScanTest.kt

    /** Every sweep and probe moves a fake clock forward: when the budget is used up it stops, without scanning the next address or probing the remaining ports. */
    static final class SlowScanner implements LocalPortScanner {
        final long[] clock;
        final long sweepMs, probeMs;
        final List<Integer> open;
        final List<String> swept = new ArrayList<>();
        final List<Integer> probed = new ArrayList<>();
        PortRange range;
        Set<Integer> skip = Collections.emptySet();

        SlowScanner(long[] clock, long sweepMs, long probeMs, List<Integer> open) {
            this.clock = clock; this.sweepMs = sweepMs; this.probeMs = probeMs; this.open = open;
        }

        @Override public List<Integer> openPorts(String host, PortRange ports, Set<Integer> skip, long budgetMs) {
            swept.add(host);
            range = ports;
            this.skip = skip;
            clock[0] += Math.min(sweepMs, budgetMs);
            List<Integer> out = new ArrayList<>();
            for (int p : open) if (!skip.contains(p)) out.add(p);
            return out;
        }

        @Override public boolean speaksAdbTls(String host, int port, int timeoutMs) {
            probed.add(port);
            clock[0] += probeMs;
            return false;
        }
    }

    static void localPortScanTests() {
        test("parsesTheSystemPortRangeOrFallsBack", () -> {
            eq("tab-separated", LocalPortScan.parseRange("32768\t60999\n"), new PortRange(32768, 60999));
            eq("one port", LocalPortScan.parseRange(" 40000 40000 "), new PortRange(40000, 40000));
            PortRange fallback = LocalPortScan.FALLBACK_RANGE;
            eq("fallback", fallback, new PortRange(30000, 65535));
            for (String bad : new String[] {null, "", "abc", "32768", "1 2 3", "60999 32768", "80 9000", "32768 70000"}) {
                eq("falls back: " + bad, LocalPortScan.parseRange(bad), fallback);
            }
        });
        test("timeCapStopsProbingAndSkipsTheNextHost", () -> {
            long[] clock = {0};
            SlowScanner scanner = new SlowScanner(clock, 6_000, 1_500, Arrays.asList(40001, 40002, 40003, 40004, 40005));
            LocalPortScan scan = new LocalPortScan(scanner, () -> Arrays.asList("127.0.0.1", "192.0.2.10"), () -> LocalPortScan.FALLBACK_RANGE,
                    OwnListeningPorts::snapshot, 10_000, LocalPortScan.DEFAULT_PROBE_TIMEOUT_MS, () -> clock[0]);
            ScanResult r = scan.scan(Collections.emptySet());
            eq("swept", scanner.swept, Arrays.asList("127.0.0.1"));
            // 6 s sweep + 3 probes of 1.5 s reach 10.5 s; the rest aren't probed
            eq("probed", scanner.probed, Arrays.asList(40001, 40002, 40003));
            eq("result", r, new ScanResult(Collections.emptyList(), 5, 0));
            ok("time cap", clock[0] <= 10_500);
        });
        test("bothHostsScannedWithinBudgetWhenNothingAnswers", () -> {
            long[] clock = {0};
            SlowScanner scanner = new SlowScanner(clock, 2_000, 10, Arrays.asList(40001));
            ScanResult r = new LocalPortScan(scanner, () -> Arrays.asList("127.0.0.1", "192.0.2.10", "127.0.0.1"), () -> LocalPortScan.FALLBACK_RANGE,
                    OwnListeningPorts::snapshot, LocalPortScan.DEFAULT_BUDGET_MS, LocalPortScan.DEFAULT_PROBE_TIMEOUT_MS, () -> clock[0]).scan(Collections.emptySet());
            eq("swept (duplicates once)", scanner.swept, Arrays.asList("127.0.0.1", "192.0.2.10"));
            eq("open", r.open, 2);
            eq("adb", r.adb, 0);
        });
        test("usesTheGivenRangeAndSkipsTriedAndOwnPorts", () -> {
            long[] clock = {0};
            SlowScanner scanner = new SlowScanner(clock, 1, 1, Arrays.asList(40001, 40002, 40003));
            LocalPortScan scan = new LocalPortScan(scanner, () -> Arrays.asList("127.0.0.1"), () -> new PortRange(32768, 60999),
                    () -> Collections.singleton(40002), LocalPortScan.DEFAULT_BUDGET_MS, LocalPortScan.DEFAULT_PROBE_TIMEOUT_MS, () -> clock[0]);
            scan.scan(Collections.singleton(40001));
            eq("range", scanner.range, new PortRange(32768, 60999));
            eq("skip", new java.util.TreeSet<>(scanner.skip), new java.util.TreeSet<>(Arrays.asList(40001, 40002)));
            eq("probed", scanner.probed, Arrays.asList(40003));
        });
        test("ownListeningPortsAreTracked", () -> {
            OwnListeningPorts.add(45678);
            ok("added", OwnListeningPorts.snapshot().contains(45678));
            OwnListeningPorts.remove(45678);
            ok("removed", !OwnListeningPorts.snapshot().contains(45678));
        });
        // Real sockets: in a small port range, find the fake adbd and a service that doesn't speak adb; only adbd answers STLS.
        test("nioScannerFindsOpenPortsAndOnlyAdbdAnswersStls", () -> {
            try (FakeAdbd adbd = new FakeAdbd(Collections.emptyList());
                 ServerSocket other = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                NioPortScanner scanner = new NioPortScanner();
                int lo = Math.min(adbd.port(), other.getLocalPort());
                int hi = Math.max(adbd.port(), other.getLocalPort());
                // when the range is too wide, scan just the two ports
                List<PortRange> ranges = hi - lo > 2_000 ? Arrays.asList(new PortRange(lo, lo), new PortRange(hi, hi)) : Arrays.asList(new PortRange(lo, hi));
                List<Integer> open = new ArrayList<>();
                for (PortRange r : ranges) open.addAll(scanner.openPorts("127.0.0.1", r, Collections.emptySet(), 5_000));
                ok("both open: " + open, open.contains(adbd.port()) && open.contains(other.getLocalPort()));
                ok("adbd answers STLS", scanner.speaksAdbTls("127.0.0.1", adbd.port(), 1_000));
                ok("other doesn't", !scanner.speaksAdbTls("127.0.0.1", other.getLocalPort(), 300));
                ok("skip honoured", scanner.openPorts("127.0.0.1", new PortRange(adbd.port(), adbd.port()), Collections.singleton(adbd.port()), 1_000).isEmpty());
            }
        });
        test("nioScannerRejectsNonLocalHostsInsteadOfResolvingOrConnecting", () -> {
            NioPortScanner scanner = new NioPortScanner();
            for (String host : new String[] {"127.evil.com", "127.0.0.1.example.com", "192.0.2.1"}) {
                ok("no sweep: " + host, scanner.openPorts(host, new PortRange(40000, 40001), Collections.emptySet(), 50).isEmpty());
                ok("no probe: " + host, !scanner.speaksAdbTls(host, 40000, 50));
            }
        });
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        edPointTests();
        spake2ConstantsTests();
        spake2GoldenTests();
        spake2RoundTripTests();
        hkdfTests();
        messageTests();
        hostsTests();
        identityTests();
        identitySourceTests();
        pairingTests();
        connectionTests();
        connectPortTests();
        localPortScanTests();
        for (File d : tempDirs) deleteTree(d);
        System.out.println("AdbTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
