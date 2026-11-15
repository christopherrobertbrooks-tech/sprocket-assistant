// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbHosts.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * adb only connects to this device itself: 127/8, IPv6 loopback, or an address on one of this device's interfaces.
 *
 * Domain names are never handed to the system resolver here. Apart from the exact {@code localhost} (optionally with
 * one trailing dot, mapped to 127.0.0.1), only numeric literals are accepted; the {@link InetAddress} that is parsed,
 * checked and then actually used by the socket is the same object, so there is no gap where the original string is
 * resolved again after the check.
 */
public final class AdbHosts {
    private static final byte[] LOOPBACK4 = {127, 0, 0, 1};

    private AdbHosts() {}

    private static final class LocalInterface {
        final String name;
        final int index;
        final List<InetAddress> addresses;

        LocalInterface(String name, int index, List<InetAddress> addresses) {
            this.name = name;
            this.index = index;
            this.addresses = addresses;
        }
    }

    /** Returns an address verified to be this device; domain names, invalid literals and other machines' addresses give null. No DNS at any point. */
    public static InetAddress ownAddress(String host) {
        List<LocalInterface> interfaces;
        try {
            interfaces = new ArrayList<>();
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all != null) {
                for (NetworkInterface it : Collections.list(all)) {
                    interfaces.add(new LocalInterface(it.getName(), it.getIndex(), Collections.list(it.getInetAddresses())));
                }
            }
        } catch (Exception e) {
            interfaces = Collections.emptyList();
        }
        return ownAddress(host, interfaces);
    }

    /** Returns a resolved address that can go straight to a Socket; callers must not build an {@link InetSocketAddress} from the original host again. */
    public static InetSocketAddress ownSocketAddress(String host, int port) {
        InetAddress a = ownAddress(host);
        return a == null ? null : new InetSocketAddress(a, port);
    }

    private static InetAddress ownAddress(String host, List<LocalInterface> interfaces) {
        InetAddress address = parseLiteral(host, interfaces);
        if (address == null) return null;
        if (address.isLoopbackAddress() || address.getAddress().length == 4 && address.getAddress()[0] == (byte) 127) return address;
        for (LocalInterface nic : interfaces) {
            for (InetAddress local : nic.addresses) {
                if (sameAddress(address, local)) return address;
            }
        }
        return null;
    }

    private static boolean sameAddress(InetAddress target, InetAddress local) {
        if (!Arrays.equals(target.getAddress(), local.getAddress())) return false;
        // A literal without a scope matches by address; one with a scope must land on the same interface, so a
        // same-named address on another interface can't let it through.
        return !(target instanceof Inet6Address) || !(local instanceof Inet6Address)
                || ((Inet6Address) target).getScopeId() == 0 || ((Inet6Address) target).getScopeId() == ((Inet6Address) local).getScopeId();
    }

    private static InetAddress parseLiteral(String raw, List<LocalInterface> interfaces) {
        String host = ktTrim(raw);
        if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("localhost.")) {
            return byAddress(LOOPBACK4);
        }
        if (host.isEmpty()) return null;
        for (int i = 0; i < host.length(); i++) if (isKtWhitespace(host.charAt(i))) return null;
        if (host.startsWith("[") || host.endsWith("]")) {
            if (!host.startsWith("[") || !host.endsWith("]") || host.length() < 3) return null;
            host = host.substring(1, host.length() - 1);
            // Square brackets are IPv6 literal syntax, not a general wrapper for any string.
            if (host.indexOf(':') < 0 || host.indexOf('[') >= 0 || host.indexOf(']') >= 0) return null;
        }
        if (host.indexOf(':') >= 0) return parseIpv6(host, interfaces);
        byte[] v4 = parseIpv4(host);
        return v4 == null ? null : byAddress(v4);
    }

    /** Strictly four decimal parts; no abbreviated, hex, octal or single-integer IPv4. */
    private static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return null;
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') return null;
                value = value * 10 + (c - '0');
            }
            if (value > 255) return null;
            out[i] = (byte) value;
        }
        return out;
    }

    private static InetAddress parseIpv6(String host, List<LocalInterface> interfaces) {
        int percent = host.indexOf('%');
        if (percent >= 0 && host.indexOf('%', percent + 1) >= 0) return null;
        String address = percent >= 0 ? host.substring(0, percent) : host;
        String zone = percent >= 0 ? host.substring(percent + 1) : "";
        if (percent >= 0 && zone.isEmpty()) return null;
        int scope;
        if (percent < 0) {
            scope = 0;
        } else if (allDigits(zone)) {
            long v;
            try {
                v = Long.parseLong(zone);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v > Integer.MAX_VALUE) return null;
            scope = (int) v;
        } else {
            LocalInterface match = null;
            for (LocalInterface it : interfaces) {
                if (it.name.equals(zone)) { match = it; break; }
            }
            if (match == null) return null;
            scope = match.index;
        }
        byte[] bytes = ipv6Bytes(address);
        if (bytes == null) return null;
        // Normalise IPv4-mapped IPv6 to IPv4, so the 16-byte form can't bypass the 127/8 and local-interface checks.
        if (isIpv4Mapped(bytes)) {
            if (scope != 0) return null;
            return byAddress(Arrays.copyOfRange(bytes, 12, 16));
        }
        try {
            return Inet6Address.getByAddress(null, bytes, scope);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    /** Expands IPv6 by hand, since InetAddress.getByName may fall back to system name resolution even for malformed numeric input. */
    private static byte[] ipv6Bytes(String address) {
        if (address.isEmpty()) return null;
        String[] halves = address.split("::", -1);
        if (halves.length > 2) return null;
        boolean compressed = halves.length == 2;
        List<Integer> left;
        List<Integer> right;
        List<Integer> whole;
        if (compressed) {
            left = ipv6Groups(halves[0], false);
            if (left == null) return null;
            right = ipv6Groups(halves[1], true);
            if (right == null) return null;
            whole = new ArrayList<>(left);
            whole.addAll(right);
            if (whole.size() >= 8) return null;
        } else {
            whole = ipv6Groups(address, true);
            if (whole == null) return null;
            if (whole.size() != 8) return null;
            left = whole;
            right = Collections.emptyList();
        }
        byte[] out = new byte[16];
        if (compressed) {
            write(out, left, 0);
            write(out, right, 8 - right.size());
        } else {
            write(out, whole, 0);
        }
        return out;
    }

    private static void write(byte[] out, List<Integer> groups, int startGroup) {
        for (int i = 0; i < groups.size(); i++) {
            int group = groups.get(i);
            out[(startGroup + i) * 2] = (byte) (group >>> 8);
            out[(startGroup + i) * 2 + 1] = (byte) group;
        }
    }

    /** Returns the 16-bit groups; a trailing embedded IPv4 takes two groups. */
    private static List<Integer> ipv6Groups(String text, boolean allowIpv4) {
        if (text.isEmpty()) return Collections.emptyList();
        String[] parts = text.split(":", -1);
        for (String p : parts) if (p.isEmpty()) return null;
        List<Integer> groups = new ArrayList<>(8);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf('.') >= 0) {
                if (!allowIpv4 || i != parts.length - 1) return null;
                byte[] ipv4 = parseIpv4(part);
                if (ipv4 == null) return null;
                groups.add(((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff));
                groups.add(((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff));
            } else {
                if (part.length() > 4) return null;
                for (int j = 0; j < part.length(); j++) {
                    char c = part.charAt(j);
                    if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) return null;
                }
                groups.add(Integer.parseInt(part, 16));
            }
        }
        return groups.size() <= 8 ? groups : null;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static InetAddress byAddress(byte[] b) {
        try {
            return InetAddress.getByAddress(null, b);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Kotlin's Char.isWhitespace(): Java whitespace or a Unicode space separator. */
    static boolean isKtWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Kotlin's String.trim(): strips {@link #isKtWhitespace} characters from both ends (Java's trim() only strips chars <= U+0020). */
    static String ktTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isKtWhitespace(s.charAt(start))) start++;
        while (end > start && isKtWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }
}
