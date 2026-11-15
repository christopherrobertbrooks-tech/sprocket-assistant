// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbTls.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import javax.net.ssl.SSLSocket;

/**
 * TLS keying-material export (RFC 8446 section 7.5). The JDK has no public API for it, so the platform supplies it:
 * on Android that is {@code SSLSockets.exportKeyingMaterial} (see {@code AndroidAdb.EXPORTER}).
 */
public interface KeyingMaterialExporter {
    byte[] export(SSLSocket socket, String label, int length) throws IOException;
}
