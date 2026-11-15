// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbPairing.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** The identity the device reports when pairing succeeds (usually type=1, the device GUID). */
public final class AdbPeerInfo {
    public final int type;
    public final String data;

    public AdbPeerInfo(int type, String data) {
        this.type = type;
        this.data = data;
    }
}
