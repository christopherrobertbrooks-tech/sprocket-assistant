// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbPairing.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Wireless-debugging pairing with a pairing code (client side). Flow:
 * TLS 1.3 -> export 64 bytes of keying material ("adb-label\0") -> password = pairing code + exported key -> exchange
 * SPAKE2 messages -> swap 8192-byte PeerInfo blocks under the negotiated AES-GCM (we send our adb public key, the
 * device answers with its GUID).
 * Every packet has a 6-byte header: version (1), type (0=SPAKE2, 1=PEER_INFO), payload length (big-endian uint32).
 */
public final class AdbPairing {
    public static final String EXPORT_LABEL = "adb-label\u0000";
    public static final int EXPORT_LENGTH = 64;
    public static final int PEER_INFO_SIZE = 8192;
    public static final int MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    public static final byte VERSION = 1;
    public static final byte TYPE_SPAKE2 = 0;
    public static final byte TYPE_PEER_INFO = 1;
    public static final byte PEER_RSA_PUB_KEY = 0;
    public static final byte PEER_DEVICE_GUID = 1;

    private final AdbTls tls;

    public AdbPairing(AdbTls tls) {
        this.tls = tls;
    }

    public AdbPeerInfo pair(String host, int port, String code) throws IOException, GeneralSecurityException {
        return pair(host, port, code, 15_000);
    }

    /** Pairs with this device; the verified address is also the one actually connected to, the original host is not resolved again. */
    public AdbPeerInfo pair(String host, int port, String code, int timeoutMs) throws IOException, GeneralSecurityException {
        InetSocketAddress target = AdbHosts.ownSocketAddress(host, port);
        if (target == null) {
            throw new AdbPairingException(AdbPairingException.Reason.NOT_LOCAL,
                    "refusing to pair with " + host + ": adb is only reachable on this device");
        }
        SSLSocket socket;
        try {
            socket = tls.connect(target, timeoutMs);
        } catch (SSLException e) {
            throw new AdbPairingException(AdbPairingException.Reason.TLS_FAILED, "TLS handshake failed", e);
        } catch (IOException e) {
            throw new AdbPairingException(AdbPairingException.Reason.CONNECT_FAILED, "cannot connect to " + host + ":" + port, e);
        }
        try (SSLSocket s = socket) {
            return exchange(s, code);
        }
    }

    private AdbPeerInfo exchange(SSLSocket socket, String code) throws IOException, GeneralSecurityException {
        byte[] exported = tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH);
        PairingAuth auth = PairingAuth.client(concat(code.getBytes(StandardCharsets.UTF_8), exported));
        DataInputStream input = new DataInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();

        writePacket(output, TYPE_SPAKE2, auth.getMessage());
        byte[] theirMsg = readPacket(input, TYPE_SPAKE2);
        if (theirMsg == null) throw protocol("connection closed before key exchange");
        try {
            auth.initCipher(theirMsg);
        } catch (IllegalArgumentException e) {
            throw protocol("bad key exchange message: " + e.getMessage());
        }

        byte[] info = new byte[PEER_INFO_SIZE];
        info[0] = PEER_RSA_PUB_KEY;
        byte[] key = tls.identity.getAdbPublicKey().getBytes(StandardCharsets.UTF_8);
        if (!(key.length < PEER_INFO_SIZE - 1)) throw new IllegalStateException("Check failed.");
        System.arraycopy(key, 0, info, 1, key.length);
        writePacket(output, TYPE_PEER_INFO, auth.encrypt(info));

        // With a wrong pairing code the device can't decrypt our PeerInfo and simply disconnects
        byte[] sealed;
        try {
            sealed = readPacket(input, TYPE_PEER_INFO);
        } catch (IOException e) {
            if (e instanceof AdbPairingException) throw e;
            sealed = null;
        }
        if (sealed == null) {
            throw new AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "device closed the connection (wrong pairing code?)");
        }
        byte[] plain;
        try {
            plain = auth.decrypt(sealed);
        } catch (AEADBadTagException e) {
            throw new AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "cannot decrypt device info (wrong pairing code?)", e);
        }
        if (plain.length != PEER_INFO_SIZE) throw protocol("peer info has " + plain.length + " bytes");
        int end = 1;
        while (end < plain.length && plain[end] != 0) end++;
        return new AdbPeerInfo(plain[0] & 0xff, new String(plain, 1, end - 1, StandardCharsets.UTF_8));
    }

    private static AdbPairingException protocol(String msg) {
        return new AdbPairingException(AdbPairingException.Reason.PROTOCOL, msg);
    }

    public static void writePacket(OutputStream out, byte type, byte[] payload) throws IOException {
        byte[] header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.length).array();
        out.write(concat(header, payload));
        out.flush();
    }

    /** Reads one packet and checks its type; returns null when the peer disconnects before a header. */
    public static byte[] readPacket(DataInputStream input, byte expectedType) throws IOException {
        byte[] header = new byte[6];
        try {
            input.readFully(header);
        } catch (EOFException e) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(header);
        byte version = bb.get();
        byte type = bb.get();
        int len = bb.getInt();
        if (version != VERSION) throw new AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unsupported pairing version " + version);
        if (type != expectedType) throw new AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unexpected packet type " + type);
        if (len <= 0 || len > MAX_PAYLOAD) throw new AdbPairingException(AdbPairingException.Reason.PROTOCOL, "bad payload size " + len);
        byte[] out = new byte[len];
        input.readFully(out);
        return out;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
