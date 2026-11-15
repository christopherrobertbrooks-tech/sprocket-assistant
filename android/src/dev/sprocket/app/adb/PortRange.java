// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/LocalPortScan.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

/** An inclusive port range: stands in for the Kotlin {@code IntRange} the original uses (first..last). */
public final class PortRange {
    public final int first;
    public final int last;

    public PortRange(int first, int last) {
        this.first = first;
        this.last = last;
    }

    public boolean contains(int v) {
        return v >= first && v <= last;
    }

    public boolean isEmpty() {
        return first > last;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PortRange)) return false;
        PortRange r = (PortRange) o;
        return isEmpty() && r.isEmpty() || first == r.first && last == r.last;
    }

    @Override
    public int hashCode() {
        return isEmpty() ? -1 : 31 * first + last;
    }

    @Override
    public String toString() {
        return first + ".." + last;
    }
}
