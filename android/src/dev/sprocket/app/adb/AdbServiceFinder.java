// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbPairing.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.util.List;

/**
 * Finds the service ports this device's adbd announces. One service may turn up several entries (after wireless
 * debugging is turned off and on again, the old entry may linger in mDNS while the new one is renamed with a number),
 * so all of them are handed over, earliest found first, for the caller to try one by one. Nothing found (timeout)
 * gives an empty list.
 * (The Kotlin original is a suspend function; this port blocks the calling thread instead.)
 */
public interface AdbServiceFinder {
    List<Integer> findPorts(String serviceType, long timeoutMs) throws InterruptedException;
}
