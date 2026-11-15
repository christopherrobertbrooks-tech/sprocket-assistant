// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbConnectPort.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.util.Objects;

/**
 * How many candidates each source gave during one connection-port search (not the ports themselves; for diagnostics).
 * When {@link #scanned} is true this device was scanned: {@link #scanOpen} ports were open, {@link #scanAdb} of them
 * looked like adbd; {@link #manual} means this attempt carried a port the user typed in.
 */
public final class ConnectCandidates {
    public final int property;
    public final int mdns;
    public final int cache;
    public final boolean connected;
    public final boolean scanned;
    public final int scanOpen;
    public final int scanAdb;
    public final boolean manual;

    public ConnectCandidates(int property, int mdns, int cache, boolean connected) {
        this(property, mdns, cache, connected, false, 0, 0, false);
    }

    public ConnectCandidates(int property, int mdns, int cache, boolean connected, boolean scanned, int scanOpen, int scanAdb, boolean manual) {
        this.property = property;
        this.mdns = mdns;
        this.cache = cache;
        this.connected = connected;
        this.scanned = scanned;
        this.scanOpen = scanOpen;
        this.scanAdb = scanAdb;
        this.manual = manual;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConnectCandidates)) return false;
        ConnectCandidates c = (ConnectCandidates) o;
        return property == c.property && mdns == c.mdns && cache == c.cache && connected == c.connected
                && scanned == c.scanned && scanOpen == c.scanOpen && scanAdb == c.scanAdb && manual == c.manual;
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, mdns, cache, connected, scanned, scanOpen, scanAdb, manual);
    }

    @Override
    public String toString() {
        return "ConnectCandidates(property=" + property + ", mdns=" + mdns + ", cache=" + cache + ", connected=" + connected
                + ", scanned=" + scanned + ", scanOpen=" + scanOpen + ", scanAdb=" + scanAdb + ", manual=" + manual + ")";
    }
}
