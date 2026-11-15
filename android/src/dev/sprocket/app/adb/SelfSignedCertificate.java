// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** The smallest self-signed X.509 v3 certificate (no extensions): CN=Mika, 1970-01-01 to 2099-12-31, SHA256withRSA. */
final class SelfSignedCertificate {
    private static final byte[] SHA256_WITH_RSA = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    private static final byte[] COMMON_NAME = {0x55, 0x04, 0x03};

    private SelfSignedCertificate() {}

    static X509Certificate create(KeyPair keyPair) throws GeneralSecurityException {
        byte[] algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, new byte[0]));
        byte[] name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "Mika".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(tlv(0x17, "700101000000Z".getBytes(StandardCharsets.UTF_8)), tlv(0x18, "20991231235959Z".getBytes(StandardCharsets.UTF_8)));
        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[] {2})),
                tlv(0x02, new byte[] {1}),
                algId, name, validity, name,
                keyPair.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();
        byte[] bits = new byte[1 + sig.length];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algId, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) all.write(p, 0, p.length);
        return tlv(0x30, all.toByteArray());
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = content.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81); out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82); out.write(n >>> 8); out.write(n & 0xff);
        } else {
            out.write(0x83); out.write(n >>> 16); out.write((n >>> 8) & 0xff); out.write(n & 0xff);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }
}
