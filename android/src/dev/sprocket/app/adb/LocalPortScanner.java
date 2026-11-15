// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** The low-level actions of a local port scan; replaced by fakes in tests. */
public interface LocalPortScanner {
    /**
     * Which of {@code ports} on {@code host} accept a TCP connection (ascending), skipping {@code skip}; stops when
     * {@code budgetMs} runs out and returns what it found so far. (Kotlin: suspend; here it blocks, and stops early
     * when the thread is interrupted.)
     */
    List<Integer> openPorts(String host, PortRange ports, Set<Integer> skip, long budgetMs) throws IOException, InterruptedException;

    /** Only counts as wireless-debugging adbd if it answers our CNXN with STLS (other services, or connecting to ourselves, never do). */
    boolean speaksAdbTls(String host, int port, int timeoutMs);
}
