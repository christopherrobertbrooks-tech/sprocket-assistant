// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** A stored key exists but can't be read (can't be unwrapped or parsed). Must not be silently replaced: the device only trusts the key used when pairing. */
public class AdbKeyUnreadableException extends Exception {
    public AdbKeyUnreadableException(String message) {
        super(message);
    }

    public AdbKeyUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
