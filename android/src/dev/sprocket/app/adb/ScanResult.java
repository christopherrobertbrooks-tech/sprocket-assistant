// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.util.List;
import java.util.Objects;

/** The result of one local scan: how many ports were open and how many looked like adbd (diagnostics only keep the counts). */
public final class ScanResult {
    public final List<AdbEndpoint> endpoints;
    public final int open;
    public final int adb;

    public ScanResult(List<AdbEndpoint> endpoints, int open, int adb) {
        this.endpoints = endpoints;
        this.open = open;
        this.adb = adb;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScanResult)) return false;
        ScanResult r = (ScanResult) o;
        return endpoints.equals(r.endpoints) && open == r.open && adb == r.adb;
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoints, open, adb);
    }

    @Override
    public String toString() {
        return "ScanResult(endpoints=" + endpoints + ", open=" + open + ", adb=" + adb + ")";
    }
}
