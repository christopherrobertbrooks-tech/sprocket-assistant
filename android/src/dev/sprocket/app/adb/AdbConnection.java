// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbConnection.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * One connection to adbd. Handshake: send CNXN -> device answers STLS -> answer STLS and do TLS 1.3 with the paired
 * certificate -> the device sends CNXN (its banner) over TLS. Streams are then multiplexed on top: OPEN -> OKAY, and
 * every WRTE waits for the peer's OKAY.
 * No features are declared (no delayed ack), so OPEN's arg1 is 0.
 */
public final class AdbConnection implements Closeable {
    private static final byte[] EOF = new byte[0];
    private static final byte[] HOST_BANNER = "host::\u0000".getBytes(StandardCharsets.US_ASCII);

    private final Socket socket;
    private final DataInputStream input;
    private final OutputStream output;
    /** Device banner, e.g. {@code device::ro.product.name=...;features=...}. */
    public final String banner;
    private final int peerVersion;
    private final int peerMaxData;
    private final int timeoutMs;

    private final ConcurrentHashMap<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean closed = false;

    private AdbConnection(Socket socket, DataInputStream input, OutputStream output, String banner, int peerVersion, int peerMaxData, int timeoutMs) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.banner = banner;
        this.peerVersion = peerVersion;
        this.peerMaxData = peerMaxData;
        this.timeoutMs = timeoutMs;
        Thread reader = new Thread(this::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Runs the {@code exec:} service: the command's raw stdout (no pty); data written goes to its stdin. */
    public AdbStream exec(String command) throws IOException {
        return open("exec:" + command);
    }

    /**
     * Runs the {@code shell:} service (Sprocket addition, not in Mika: a one-line wrapper over {@link #open(String)}).
     * Without the shell_v2 feature (never declared here) adbd runs a non-empty command raw, without a pty.
     */
    public AdbStream shell(String command) throws IOException {
        return open("shell:" + command);
    }

    public AdbStream open(String destination) throws IOException {
        if (closed) throw new IOException("adb connection closed");
        AdbStream s = new AdbStream(nextId.getAndIncrement());
        streams.put(s.localId, s);
        send(new AdbMessage(AdbMessage.OPEN, s.localId, 0, (destination + "\u0000").getBytes(StandardCharsets.UTF_8)));
        boolean ok;
        try {
            ok = s.opened.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            streams.remove(s.localId);
            throw new IOException("open '" + destination + "' timed out", e);
        }
        if (!ok) {
            streams.remove(s.localId);
            throw new IOException("device refused '" + destination + "'");
        }
        return s;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        for (AdbStream s : new ArrayList<>(streams.values())) s.remoteClosed();
        streams.clear();
    }

    private void send(AdbMessage m) throws IOException {
        byte[] bytes = m.encode();
        synchronized (output) {
            output.write(bytes);
            output.flush();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                AdbMessage m = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, peerVersion < AdbMessage.VERSION_SKIP_CHECKSUM);
                if (m == null) break;
                AdbStream s = streams.get(m.arg1);
                switch (m.command) {
                    case AdbMessage.OKAY:
                        if (s != null) {
                            if (s.remoteId == 0) {
                                s.remoteId = m.arg0;
                                s.opened.complete(true);
                            } else {
                                s.acks.release();
                            }
                        }
                        break;
                    case AdbMessage.WRTE:
                        if (s != null) {
                            s.incoming.offer(m.data);
                            send(new AdbMessage(AdbMessage.OKAY, s.localId, s.remoteId));
                        }
                        break;
                    case AdbMessage.CLSE:
                        if (s != null) {
                            streams.remove(s.localId);
                            s.opened.complete(false);
                            s.remoteClosed();
                        }
                        break;
                    default:
                        break; // CNXN/AUTH etc. after the connection is up are ignored
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    /** One stream. {@link #read()} blocks until data arrives and returns null at end of stream. */
    public final class AdbStream implements Closeable {
        public final int localId;
        volatile int remoteId = 0;
        final CompletableFuture<Boolean> opened = new CompletableFuture<>();
        final Semaphore acks = new Semaphore(0);
        final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private volatile boolean remoteGone = false;
        private volatile boolean localClosed = false;

        AdbStream(int localId) {
            this.localId = localId;
        }

        public byte[] read() throws InterruptedException {
            byte[] b = incoming.take();
            if (b == EOF) {
                incoming.offer(EOF);
                return null;
            }
            return b;
        }

        /** Reads until the peer closes. */
        public byte[] readAll() throws InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                byte[] b = read();
                if (b == null) return out.toByteArray();
                out.write(b, 0, b.length);
            }
        }

        public String readText() throws InterruptedException {
            return new String(readAll(), StandardCharsets.UTF_8);
        }

        public void write(byte[] data) throws IOException, InterruptedException {
            int pos = 0;
            while (pos < data.length) {
                if (remoteGone || localClosed) throw new IOException("stream closed");
                int n = Math.min(peerMaxData, data.length - pos);
                byte[] chunk = new byte[n];
                System.arraycopy(data, pos, chunk, 0, n);
                send(new AdbMessage(AdbMessage.WRTE, localId, remoteId, chunk));
                if (!acks.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("write not acknowledged");
                if (remoteGone) throw new IOException("stream closed");
                pos += n;
            }
        }

        /** A fresh InputStream view of this stream on every call (as the Kotlin property getter). */
        public InputStream getInputStream() {
            return new InputStream() {
                private byte[] buf = new byte[0];
                private int off = 0;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int o, int len) throws IOException {
                    if (len == 0) return 0;
                    while (off >= buf.length) {
                        byte[] next;
                        try {
                            next = AdbStream.this.read();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("interrupted");
                        }
                        if (next == null) return -1;
                        buf = next;
                        off = 0;
                    }
                    int n = Math.min(len, buf.length - off);
                    System.arraycopy(buf, off, b, o, n);
                    off += n;
                    return n;
                }
            };
        }

        void remoteClosed() {
            remoteGone = true;
            opened.complete(false);
            acks.release();
            incoming.offer(EOF);
        }

        @Override
        public void close() {
            if (localClosed) return;
            localClosed = true;
            if (!remoteGone && !closed) {
                try {
                    send(new AdbMessage(AdbMessage.CLSE, localId, remoteId));
                } catch (Exception ignored) {
                }
            }
            streams.remove(localId);
            remoteClosed();
        }
    }

    public static AdbConnection open(String host, int port, AdbTls tls) throws IOException, GeneralSecurityException {
        return open(host, port, tls, 10_000);
    }

    /** Connects to adbd on this device's host:port. The verified address is also the one actually connected to; the original host is not resolved again. */
    public static AdbConnection open(String host, int port, AdbTls tls, int timeoutMs) throws IOException, GeneralSecurityException {
        InetSocketAddress target = AdbHosts.ownSocketAddress(host, port);
        if (target == null) {
            throw new AdbConnectionException(AdbConnectionException.Reason.NOT_LOCAL,
                    "refusing to connect to " + host + ": adb is only reachable on this device");
        }
        Socket plain = new Socket();
        try {
            plain.setTcpNoDelay(true);
            plain.connect(target, timeoutMs);
            plain.setSoTimeout(timeoutMs);
        } catch (IOException e) {
            plain.close();
            throw new AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "cannot connect to " + host + ":" + port, e);
        }
        try {
            return handshake(plain, target, tls, timeoutMs);
        } catch (Throwable e) {
            try {
                plain.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private static AdbConnection handshake(Socket plain, InetSocketAddress target, AdbTls tls, int timeoutMs) throws IOException, GeneralSecurityException {
        OutputStream out = plain.getOutputStream();
        out.write(new AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode());
        out.flush();
        AdbMessage first = AdbMessage.read(new DataInputStream(plain.getInputStream()));
        if (first == null) throw new AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "device closed the connection");
        switch (first.command) {
            case AdbMessage.CNXN:
                return finish(plain, new DataInputStream(plain.getInputStream()), out, first, timeoutMs);
            case AdbMessage.STLS: {
                out.write(new AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode());
                out.flush();
                SSLSocket ssl;
                try {
                    ssl = tls.wrap(plain, target, true);
                } catch (IOException e) {
                    throw new AdbConnectionException(AdbConnectionException.Reason.TLS_FAILED, "TLS handshake failed", e);
                }
                DataInputStream input = new DataInputStream(ssl.getInputStream());
                // In TLS 1.3 the device rejects a client certificate after the handshake, which shows up as an error or
                // a disconnect when reading the first packet. The client can't tell whether adbd refused this key or
                // another service took this handshake; the caller retries (see AdbConnectPort).
                AdbMessage cnxn;
                try {
                    cnxn = AdbMessage.read(input);
                } catch (SSLException e) {
                    throw new AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED,
                            "TLS rejected our certificate (key not paired, or another service took the handshake)", e);
                } catch (IOException e) {
                    if (e instanceof SocketTimeoutException) {
                        throw new AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "no CNXN from device", e);
                    }
                    cnxn = null;
                }
                if (cnxn == null) {
                    throw new AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "device closed the connection after TLS (key not paired?)");
                }
                if (cnxn.command != AdbMessage.CNXN) {
                    throw new AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "expected CNXN, got " + cnxn);
                }
                return finish(ssl, input, ssl.getOutputStream(), cnxn, timeoutMs);
            }
            case AdbMessage.AUTH:
                throw new AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "device asks for legacy RSA auth (not a wireless-debugging port?)");
            default:
                throw new AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "unexpected " + first);
        }
    }

    private static AdbConnection finish(Socket socket, DataInputStream input, OutputStream out, AdbMessage cnxn, int timeoutMs) throws IOException {
        socket.setSoTimeout(0);
        String banner = trimEndNul(new String(cnxn.data, StandardCharsets.UTF_8));
        int maxData = cnxn.arg1 >= 1 && cnxn.arg1 <= AdbMessage.MAX_PAYLOAD ? cnxn.arg1 : 4096;
        return new AdbConnection(socket, input, out, banner, cnxn.arg0, maxData, timeoutMs);
    }

    private static String trimEndNul(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\u0000') end--;
        return s.substring(0, end);
    }
}
