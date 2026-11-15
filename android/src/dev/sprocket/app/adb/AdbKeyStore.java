// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Storage for the key (PKCS#8). In the app it is wrapped with Android Keystore encryption.
 * {@link #read()} returns null when nothing was ever stored; when something is stored but can't be read it throws
 * {@link AdbKeyUnreadableException} (the two must stay distinct: the latter must not be treated as "none").
 */
public interface AdbKeyStore {
    byte[] read() throws AdbKeyUnreadableException;

    void write(byte[] pkcs8) throws IOException, GeneralSecurityException;

    void clear();
}
