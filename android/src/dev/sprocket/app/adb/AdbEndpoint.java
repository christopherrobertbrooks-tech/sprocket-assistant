// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.util.Objects;

/** One connect-port candidate: which of this device's addresses, and which port. */
public final class AdbEndpoint {
    public final String host;
    public final int port;

    public AdbEndpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AdbEndpoint)) return false;
        AdbEndpoint e = (AdbEndpoint) o;
        return host.equals(e.host) && port == e.port;
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return "AdbEndpoint(host=" + host + ", port=" + port + ")";
    }
}
