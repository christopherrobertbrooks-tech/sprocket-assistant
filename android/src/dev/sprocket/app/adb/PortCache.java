// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbConnectPort.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** The wireless-debugging port that last connected (app-private storage). */
public interface PortCache {
    /** Null when nothing is cached. */
    Integer load();

    void save(int port);
}
