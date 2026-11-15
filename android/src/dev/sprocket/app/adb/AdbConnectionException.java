// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbConnection.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;

public class AdbConnectionException extends IOException {
    public enum Reason {
        /** The target is not one of this device's own addresses: refused before creating a socket; must not be retried as a network failure. */
        NOT_LOCAL,
        CONNECT_FAILED,
        TLS_FAILED,
        /** The device doesn't accept this key (never paired, revoked, or authorization expired): pair again. */
        UNAUTHORIZED,
        PROTOCOL,
    }

    public final Reason reason;

    public AdbConnectionException(Reason reason, String message) {
        this(reason, message, null);
    }

    public AdbConnectionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
