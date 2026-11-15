// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The last resort for finding the connect port: when none of the earlier sources gave a port that connects, scan this
 * device's own addresses.
 * Wireless-debugging adbd listens on a port the system hands out at random, inside the ephemeral port range
 * ({@code range}, {@link #FALLBACK_RANGE} when it can't be read).
 * Scans one address at a time in the order of {@code hosts} (127.0.0.1 first, then this device's Wi-Fi address; adbd
 * may be bound to only one of them), sends CNXN to every open port and checks for an STLS reply; once an address has
 * something that looks like adbd, the next address is not scanned. Only this device is scanned, never another one.
 * Scanning and checking together take no more than {@code budgetMs}.
 */
public final class LocalPortScan {
    /** The range scanned when the system's ephemeral port range can't be read (covers the usual settings across versions). */
    public static final PortRange FALLBACK_RANGE = new PortRange(30_000, 65_535);
    public static final long DEFAULT_BUDGET_MS = 10_000;
    public static final int DEFAULT_PROBE_TIMEOUT_MS = 1_000;

    private final LocalPortScanner scanner;
    private final Supplier<List<String>> hosts;
    private final Supplier<PortRange> range;
    private final Supplier<Set<Integer>> ownPorts;
    private final long budgetMs;
    private final int probeTimeoutMs;
    private final LongSupplier now;

    public LocalPortScan(LocalPortScanner scanner, Supplier<List<String>> hosts) {
        this(scanner, hosts, () -> FALLBACK_RANGE, OwnListeningPorts::snapshot, DEFAULT_BUDGET_MS, DEFAULT_PROBE_TIMEOUT_MS, System::currentTimeMillis);
    }

    public LocalPortScan(LocalPortScanner scanner, Supplier<List<String>> hosts, Supplier<PortRange> range, Supplier<Set<Integer>> ownPorts,
                         long budgetMs, int probeTimeoutMs, LongSupplier now) {
        this.scanner = scanner;
        this.hosts = hosts;
        this.range = range;
        this.ownPorts = ownPorts;
        this.budgetMs = budgetMs;
        this.probeTimeoutMs = probeTimeoutMs;
        this.now = now;
    }

    /** Scans once; {@code skip} holds the ports earlier sources already tried. */
    public ScanResult scan(Set<Integer> skip) throws IOException, InterruptedException {
        long deadline = now.getAsLong() + budgetMs;
        PortRange ports = range.get();
        Set<Integer> excluded = new HashSet<>(skip);
        excluded.addAll(ownPorts.get());
        List<AdbEndpoint> found = new ArrayList<>();
        int open = 0;
        for (String host : new LinkedHashSet<>(hosts.get())) {
            long left = deadline - now.getAsLong();
            if (left <= 0) break;
            List<Integer> openPorts = scanner.openPorts(host, ports, excluded, left);
            open += openPorts.size();
            for (int port : openPorts) {
                if (now.getAsLong() >= deadline) break;
                if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found.add(new AdbEndpoint(host, port));
            }
            if (!found.isEmpty()) break;
        }
        return new ScanResult(found, open, found.size());
    }

    /**
     * Parses {@code /proc/sys/net/ipv4/ip_local_port_range} (two numbers separated by whitespace). Falls back to
     * {@link #FALLBACK_RANGE} when it can't be read, is malformed, or the range is unreasonable.
     */
    public static PortRange parseRange(String text) {
        if (text == null) return FALLBACK_RANGE;
        List<Integer> nums = new ArrayList<>();
        for (String s : AdbHosts.ktTrim(text).split("\\s+")) {
            try {
                nums.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        if (nums.size() != 2) return FALLBACK_RANGE;
        int lo = nums.get(0);
        int hi = nums.get(1);
        return lo >= 1024 && lo <= 65_535 && hi >= lo && hi <= 65_535 ? new PortRange(lo, hi) : FALLBACK_RANGE;
    }
}
