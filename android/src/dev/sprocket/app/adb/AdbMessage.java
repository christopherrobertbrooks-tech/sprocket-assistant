// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbMessage.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One packet of the adb transport: a 24-byte little-endian header (command, arg0, arg1, data length, data checksum,
 * command bitwise-inverted) + data.
 * The checksum is the sum of all data bytes (unsigned); from protocol version {@link #VERSION_SKIP_CHECKSUM} on, the
 * receiver no longer checks it.
 */
public final class AdbMessage {
    public static final int CNXN = 0x4e584e43;
    public static final int AUTH = 0x48545541;
    public static final int OPEN = 0x4e45504f;
    public static final int OKAY = 0x59414b4f;
    public static final int CLSE = 0x45534c43;
    public static final int WRTE = 0x45545257;
    public static final int STLS = 0x534c5453;

    public static final int VERSION = 0x01000001;
    public static final int VERSION_SKIP_CHECKSUM = 0x01000001;
    public static final int STLS_VERSION = 0x01000000;
    public static final int MAX_PAYLOAD = 1024 * 1024;
    public static final int HEADER_SIZE = 24;
    private static final byte[] EMPTY = new byte[0];

    public final int command;
    public final int arg0;
    public final int arg1;
    public final byte[] data;

    public AdbMessage(int command, int arg0, int arg1) {
        this(command, arg0, arg1, EMPTY);
    }

    public AdbMessage(int command, int arg0, int arg1, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.data = data;
    }

    public byte[] encode() {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(~command);
        bb.put(data);
        return bb.array();
    }

    @Override
    public String toString() {
        return name(command) + "(" + arg0 + ", " + arg1 + ", " + data.length + " bytes)";
    }

    public static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        return sum;
    }

    public static String name(int cmd) {
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) (cmd >>> (8 * i));
        String s = new String(b, StandardCharsets.US_ASCII);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') return String.format("0x%08x", cmd);
        }
        return s;
    }

    public static AdbMessage read(DataInputStream input) throws IOException {
        return read(input, MAX_PAYLOAD, false);
    }

    public static AdbMessage read(DataInputStream input, int maxData) throws IOException {
        return read(input, maxData, false);
    }

    /** Reads one packet; returns null when the stream ends before a header, throws {@link IOException} when the format is wrong. */
    public static AdbMessage read(DataInputStream input, int maxData, boolean verifyChecksum) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        try {
            input.readFully(header);
        } catch (EOFException e) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int a0 = bb.getInt();
        int a1 = bb.getInt();
        int len = bb.getInt();
        int sum = bb.getInt();
        int magic = bb.getInt();
        if (magic != ~cmd) throw new IOException("bad adb packet magic for " + name(cmd));
        if (len < 0 || len > maxData) throw new IOException("adb packet too large: " + len);
        byte[] data;
        if (len == 0) {
            data = EMPTY;
        } else {
            data = new byte[len];
            input.readFully(data);
        }
        if (verifyChecksum && checksum(data) != sum) throw new IOException("bad adb packet checksum");
        return new AdbMessage(cmd, a0, a1, data);
    }
}
