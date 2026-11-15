// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Ports the app itself listens on locally (e.g. a sign-in callback): skipped when scanning, so we don't connect to ourselves. */
public final class OwnListeningPorts {
    private static final Set<Integer> ports = ConcurrentHashMap.newKeySet();

    private OwnListeningPorts() {}

    public static void add(int port) { ports.add(port); }

    public static void remove(int port) { ports.remove(port); }

    public static Set<Integer> snapshot() { return new HashSet<>(ports); }
}
