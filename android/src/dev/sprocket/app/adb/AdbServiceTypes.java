// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbPairing.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** Service types adbd announces over mDNS. */
public final class AdbServiceTypes {
    private AdbServiceTypes() {}

    /** Only announced while the "Pair device with pairing code" dialog is open. */
    public static final String PAIRING = "_adb-tls-pairing._tcp";
    /** Announced while wireless debugging is on; the port changes every time wireless debugging is toggled. */
    public static final String CONNECT = "_adb-tls-connect._tcp";
}
