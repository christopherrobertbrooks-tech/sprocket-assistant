// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** The state of the key right now (for diagnostics). Constant names kept as in the Kotlin original. */
public enum AdbKeyState {
    /** No key used yet in this process. */
    NotLoaded,
    /** The stored key was read back. */
    Loaded,
    /** There was none before (or pairing replaced the unreadable one), so a new one was made and stored. */
    Created,
    /** One is stored but can't be read: pair again. */
    Unreadable,
}
