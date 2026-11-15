// Ported to Java from Mika (https://github.com/wangzan101/Mika), app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/NsdAdbServiceFinder.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Finds the ports this device's adbd announces using the system mDNS (NsdManager). Only results whose address belongs
 * to one of this device's interfaces count: other phones on the same LAN announce services with the same name. With
 * the port in hand the caller connects to 127.0.0.1.
 * After the first hit, waits another SETTLE_MS to collect entries announced at the same time (a stale old record and
 * the new one may come back together), resolving them one at a time (older systems resolve only one at once).
 * (The Kotlin original uses coroutine channels and timeouts; this port blocks the calling thread on queues.)
 */
public final class NsdAdbServiceFinder implements AdbServiceFinder {
    private static final long SETTLE_MS = 400L;
    private static final long RESOLVE_MS = 1_500L;
    /** Marks the discovery channel as closed (Kotlin: services.close()). */
    private static final NsdServiceInfo CLOSED = new NsdServiceInfo();

    private final NsdManager nsd;

    public NsdAdbServiceFinder(Context context) {
        nsd = context.getApplicationContext().getSystemService(NsdManager.class);
    }

    @Override
    public List<Integer> findPorts(String serviceType, long timeoutMs) throws InterruptedException {
        final LinkedBlockingQueue<NsdServiceInfo> services = new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) { services.offer(info); }
            @Override public void onServiceLost(NsdServiceInfo info) {}
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { services.offer(CLOSED); }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            long settleUntil = Long.MAX_VALUE;
            while (System.currentTimeMillis() < settleUntil) {
                long wait = Math.min(Math.min(settleUntil - System.currentTimeMillis(), timeoutMs), deadline - System.currentTimeMillis());
                if (wait <= 0) break;
                NsdServiceInfo info = services.poll(wait, TimeUnit.MILLISECONDS);
                if (info == null || info == CLOSED) {
                    if (info == CLOSED) services.offer(CLOSED);
                    break;
                }
                Integer port = resolve(info, deadline);
                if (port != null) {
                    ports.add(port);
                    if (settleUntil == Long.MAX_VALUE) settleUntil = System.currentTimeMillis() + SETTLE_MS;
                }
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(ports);
    }

    /** Resolves one service and gives its port if it is this device; null when resolving fails or it isn't this device. */
    @SuppressWarnings("deprecation")
    private Integer resolve(NsdServiceInfo info, long deadline) throws InterruptedException {
        final ArrayBlockingQueue<Integer> done = new ArrayBlockingQueue<>(1);
        final int[] none = {-1};
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { done.offer(none[0]); }
            @Override public void onServiceResolved(NsdServiceInfo info) { done.offer(isLocal(info.getHost()) ? info.getPort() : none[0]); }
        });
        long wait = Math.min(RESOLVE_MS, deadline - System.currentTimeMillis());
        if (wait <= 0) return null;
        Integer port = done.poll(wait, TimeUnit.MILLISECONDS);
        return port == null || port == none[0] ? null : port;
    }

    private static boolean isLocal(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nic.getInetAddresses())) if (a.equals(host)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
