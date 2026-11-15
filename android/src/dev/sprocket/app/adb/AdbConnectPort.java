// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbConnectPort.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Finds this device's wireless-debugging connect port and connects. Tries in order and stops at the first that
 * connects: the port that last connected, the system property (only if readable), every port mDNS finds, a scan of
 * this device's ports ({@code scan}, only when all of the above failed), and the port the user typed on the setup page
 * ({@code manual}).
 * mDNS alone isn't enough: adbd registers the service only once when wireless debugging is turned on, the
 * registration is lost when the mDNS daemon restarts, and some networks block multicast; mDNS may also return
 * already-dead old ports alongside, so every port it finds is tried. A stale cached port is refused quickly, so it
 * goes first.
 * When the user typed a port there is no scan (they were asked to type it because the scan found nothing); it is
 * tried directly.
 * A rejection over TLS (UNAUTHORIZED, TLS_FAILED) doesn't necessarily mean the wrong key: some systems let another
 * service take the first TLS handshake on the same port, rejecting anyone's certificate, and the very next connection
 * reaches adbd. So the same port is tried once more after {@code tlsRetryDelayMs}, and if that fails too, the other
 * candidates are tried; only when nothing connects and some port rejected us like this is UNAUTHORIZED reported (pair
 * again). Once a port has rejected us, this device's ports are not scanned: adbd has been found, and a scan would
 * only find it again.
 * (The Kotlin original is a suspend function using coroutine delay; this port blocks the calling thread.)
 */
public final class AdbConnectPort {
    public static final String LOCALHOST = "127.0.0.1";

    /** Opens a connection to host:port (normally {@code (h, p) -> AdbConnection.open(h, p, tls)}). */
    public interface Opener<T> {
        T open(String host, int port) throws IOException, java.security.GeneralSecurityException;
    }

    private interface Source {
        List<AdbEndpoint> get() throws Exception;
    }

    private final Supplier<Integer> property;
    private final AdbServiceFinder finder;
    private final PortCache cache;
    private final long mdnsTimeoutMs;
    private final Consumer<ConnectCandidates> report;
    private final LocalPortScan scan;
    private final Supplier<Integer> manual;
    private final Consumer<Boolean> scanning;
    private final String localHost;
    private final long tlsRetryDelayMs;

    public AdbConnectPort(Supplier<Integer> property, AdbServiceFinder finder, PortCache cache) {
        this(new Builder(property, finder, cache));
    }

    private AdbConnectPort(Builder b) {
        property = b.property;
        finder = b.finder;
        cache = b.cache;
        mdnsTimeoutMs = b.mdnsTimeoutMs;
        report = b.report;
        scan = b.scan;
        manual = b.manual;
        scanning = b.scanning;
        localHost = b.localHost;
        tlsRetryDelayMs = b.tlsRetryDelayMs;
    }

    /** The Kotlin constructor's optional (default) parameters. */
    public static final class Builder {
        private final Supplier<Integer> property;
        private final AdbServiceFinder finder;
        private final PortCache cache;
        private long mdnsTimeoutMs = 3_000;
        private Consumer<ConnectCandidates> report = c -> { };
        private LocalPortScan scan = null;
        private Supplier<Integer> manual = () -> null;
        private Consumer<Boolean> scanning = s -> { };
        private String localHost = LOCALHOST;
        private long tlsRetryDelayMs = 300;

        public Builder(Supplier<Integer> property, AdbServiceFinder finder, PortCache cache) {
            this.property = property;
            this.finder = finder;
            this.cache = cache;
        }

        public Builder mdnsTimeoutMs(long v) { mdnsTimeoutMs = v; return this; }
        /** Called once at the end of every connection attempt (connected or failed) with each source's candidate count. */
        public Builder report(Consumer<ConnectCandidates> v) { report = v; return this; }
        public Builder scan(LocalPortScan v) { scan = v; return this; }
        /** The port the user typed this time; null when none. */
        public Builder manual(Supplier<Integer> v) { manual = v; return this; }
        /** Called when the local port scan starts and ends (the UI shows "searching this device..."). */
        public Builder scanning(Consumer<Boolean> v) { scanning = v; return this; }
        public Builder localHost(String v) { localHost = v; return this; }
        public Builder tlsRetryDelayMs(long v) { tlsRetryDelayMs = v; return this; }
        public AdbConnectPort build() { return new AdbConnectPort(this); }
    }

    /**
     * Tries the candidates in turn with {@code open} and returns the first result that connects; when none connects,
     * throws the UNAUTHORIZED from a port that rejected us if there was one, otherwise the last error.
     */
    public <T> T connect(Opener<T> open) throws IOException, java.security.GeneralSecurityException, InterruptedException {
        Set<AdbEndpoint> tried = new HashSet<>();
        Set<Integer> triedPorts = new HashSet<>();
        AdbConnectionException last = null;
        AdbConnectionException[] rejected = {null};
        Integer m = manual.get();
        Integer manualPort = m != null && m >= 1 && m <= 65535 ? m : null;
        ScanResult[] scanResult = {null};
        List<Source> sources = new ArrayList<>();
        sources.add(() -> local(cache.load()));
        sources.add(() -> local(property.get()));
        sources.add(() -> {
            List<AdbEndpoint> out = new ArrayList<>();
            for (int p : finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs)) out.add(new AdbEndpoint(localHost, p));
            return out;
        });
        sources.add(() -> {
            if (scan == null || manualPort != null || rejected[0] != null) return Collections.<AdbEndpoint>emptyList();
            scanning.accept(true);
            try {
                ScanResult r = scan.scan(triedPorts);
                scanResult[0] = r;
                return r.endpoints;
            } finally {
                scanning.accept(false);
            }
        });
        sources.add(() -> local(manualPort));
        int[] counts = new int[sources.size()];
        boolean connected = false;
        try {
            for (int i = 0; i < sources.size(); i++) {
                // A source that can't be read (property blocked, mDNS won't start) counts as empty, not as a failure
                List<AdbEndpoint> endpoints;
                try {
                    endpoints = sources.get(i).get();
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    endpoints = Collections.emptyList();
                }
                counts[i] = endpoints.size();
                for (AdbEndpoint ep : endpoints) {
                    if (ep.port < 1 || ep.port > 65535 || !tried.add(ep)) continue;
                    triedPorts.add(ep.port);
                    boolean retried = false;
                    while (true) {
                        try {
                            T result = open.open(ep.host, ep.port);
                            cache.save(ep.port);
                            connected = true;
                            return result;
                        } catch (AdbConnectionException e) {
                            // An address-policy failure doesn't mean this port is temporarily unavailable: don't move on
                            // to another port, and certainly don't retry automatically later.
                            if (e.reason == AdbConnectionException.Reason.NOT_LOCAL) throw e;
                            boolean tls = e.reason == AdbConnectionException.Reason.UNAUTHORIZED
                                    || e.reason == AdbConnectionException.Reason.TLS_FAILED;
                            if (tls && !retried) {
                                retried = true;
                                Thread.sleep(tlsRetryDelayMs);
                                continue;
                            }
                            if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) rejected[0] = e;
                            last = e;
                            break;
                        }
                    }
                }
            }
            if (rejected[0] != null) throw rejected[0];
            if (last != null) throw last;
            throw new AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found");
        } finally {
            ScanResult s = scanResult[0];
            report.accept(new ConnectCandidates(counts[1], counts[2], counts[0], connected,
                    s != null, s != null ? s.open : 0, s != null ? s.adb : 0, manualPort != null));
        }
    }

    private List<AdbEndpoint> local(Integer port) {
        if (port == null) return Collections.emptyList();
        List<AdbEndpoint> out = new ArrayList<>(1);
        out.add(new AdbEndpoint(localHost, port));
        return out;
    }
}
