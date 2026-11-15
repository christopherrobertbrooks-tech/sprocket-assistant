// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbPairing.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;

public class AdbPairingException extends IOException {
    public enum Reason {
        /** The target is not one of this device's own addresses: refused before creating a socket; must not be retried as a network failure. */
        NOT_LOCAL,
        CONNECT_FAILED, TLS_FAILED, WRONG_CODE, PROTOCOL,
    }

    public final Reason reason;

    public AdbPairingException(Reason reason, String message) {
        this(reason, message, null);
    }

    public AdbPairingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
