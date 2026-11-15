// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * The real scan: java.nio non-blocking connects, at most {@code concurrency} in flight at once, each waiting at most
 * {@code connectTimeoutMs}. Ports that refuse on this device's own address are refused at once, so a full range takes
 * a few seconds. (Kotlin: runs on the IO dispatcher and stops when the coroutine is cancelled; here it blocks the
 * calling thread and stops when that thread is interrupted.)
 */
public final class NioPortScanner implements LocalPortScanner {
    private static final long SELECT_MS = 20L;
    private static final byte[] HOST_BANNER = "host::\u0000".getBytes(StandardCharsets.US_ASCII);

    private final int concurrency;
    private final long connectTimeoutMs;

    public NioPortScanner() {
        this(200, 150);
    }

    public NioPortScanner(int concurrency, long connectTimeoutMs) {
        this.concurrency = concurrency;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public List<Integer> openPorts(String host, PortRange ports, Set<Integer> skip, long budgetMs) throws IOException, InterruptedException {
        InetAddress address = AdbHosts.ownAddress(host);
        if (address == null) return Collections.emptyList();
        List<Integer> out = sweep(address, ports, skip, System.currentTimeMillis() + budgetMs, () -> !Thread.currentThread().isInterrupted());
        if (Thread.interrupted()) throw new InterruptedException();
        return out;
    }

    private List<Integer> sweep(InetAddress addr, PortRange ports, Set<Integer> skip, long deadline, BooleanSupplier active) throws IOException {
        TreeSet<Integer> open = new TreeSet<>();
        int next = ports.first;
        try (Selector selector = Selector.open()) {
            Map<SelectionKey, Long> startedAt = new HashMap<>();
            try {
                while ((next <= ports.last || !startedAt.isEmpty()) && System.currentTimeMillis() < deadline && active.getAsBoolean()) {
                    while (next <= ports.last && startedAt.size() < concurrency) {
                        int port = next++;
                        if (skip.contains(port)) continue;
                        SocketChannel ch = SocketChannel.open();
                        try {
                            ch.configureBlocking(false);
                            if (ch.connect(new InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open.add(port);
                                ch.close();
                            } else {
                                startedAt.put(ch.register(selector, SelectionKey.OP_CONNECT, port), System.currentTimeMillis());
                            }
                        } catch (IOException e) {
                            ch.close();
                        }
                    }
                    selector.select(SELECT_MS);
                    for (SelectionKey key : selector.selectedKeys()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        boolean ok;
                        try {
                            ok = ch.finishConnect();
                        } catch (IOException e) {
                            ok = false;
                        }
                        if (ok && !selfConnected(ch, (Integer) key.attachment())) open.add((Integer) key.attachment());
                        startedAt.remove(key);
                        key.cancel();
                        ch.close();
                    }
                    selector.selectedKeys().clear();
                    long t = System.currentTimeMillis();
                    List<SelectionKey> late = new ArrayList<>();
                    for (Map.Entry<SelectionKey, Long> e : startedAt.entrySet()) if (t - e.getValue() > connectTimeoutMs) late.add(e.getKey());
                    for (SelectionKey key : late) {
                        startedAt.remove(key);
                        key.cancel();
                        key.channel().close();
                    }
                }
            } finally {
                for (SelectionKey key : startedAt.keySet()) {
                    try {
                        key.channel().close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return new ArrayList<>(open);
    }

    /** When scanning the ephemeral range, the system may happen to give this connection the same local port, so it connected to itself. */
    private static boolean selfConnected(SocketChannel ch, int port) {
        try {
            Socket s = ch.socket();
            return s.getLocalPort() == port && s.getLocalAddress().equals(s.getInetAddress());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean speaksAdbTls(String host, int port, int timeoutMs) {
        try {
            InetSocketAddress target = AdbHosts.ownSocketAddress(host, port);
            if (target == null) return false;
            try (Socket s = new Socket()) {
                s.connect(target, timeoutMs);
                if (s.getLocalPort() == port && s.getLocalAddress().equals(s.getInetAddress())) return false;
                s.setSoTimeout(timeoutMs);
                OutputStream out = s.getOutputStream();
                out.write(new AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode());
                out.flush();
                AdbMessage m = AdbMessage.read(new DataInputStream(s.getInputStream()), 4096);
                return m != null && m.command == AdbMessage.STLS;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
