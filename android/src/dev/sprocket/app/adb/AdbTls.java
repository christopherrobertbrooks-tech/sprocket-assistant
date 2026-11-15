// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbTls.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TLS 1.3 as adb uses it: both sides present self-signed certificates and neither checks the other's chain.
 * When pairing, identity is bound to the exported keying material by SPAKE2; when connecting, the device compares the
 * public key in our certificate. Only this device's own addresses are connected to.
 */
public final class AdbTls {
    private static final String ALIAS = "adb";

    public final AdbIdentity identity;
    private final KeyingMaterialExporter exporter;
    private final Provider provider;
    private SSLContext context;

    public AdbTls(AdbIdentity identity, KeyingMaterialExporter exporter) {
        this(identity, exporter, null);
    }

    public AdbTls(AdbIdentity identity, KeyingMaterialExporter exporter, Provider provider) {
        this.identity = identity;
        this.exporter = exporter;
        this.provider = provider;
    }

    /** Built on first use (Kotlin: {@code by lazy}). */
    public synchronized SSLContext getContext() throws GeneralSecurityException {
        if (context == null) {
            SSLContext ctx = provider == null ? SSLContext.getInstance("TLS") : SSLContext.getInstance("TLS", provider);
            ctx.init(new KeyManager[] {new FixedKeyManager(identity.keyPair.getPrivate(), identity.getCertificate())},
                    new TrustManager[] {TrustAll.INSTANCE}, null);
            context = ctx;
        }
        return context;
    }

    /** Opens a new TCP connection to the caller-verified target and completes the TLS handshake; the host name is not resolved again. */
    public SSLSocket connect(InetSocketAddress target, int timeoutMs) throws IOException, GeneralSecurityException {
        Socket plain = new Socket();
        try {
            plain.setTcpNoDelay(true);
            plain.connect(target, timeoutMs);
            plain.setSoTimeout(timeoutMs);
        } catch (Exception e) {
            plain.close();
            throw e;
        }
        return wrap(plain, target, true);
    }

    /** Does the TLS handshake on an existing connection; client = false is only for the fake server in tests. */
    public SSLSocket wrap(Socket plain, InetSocketAddress target, boolean client) throws IOException, GeneralSecurityException {
        if (target.getAddress() == null) throw new IllegalArgumentException("ADB address must be resolved");
        String host = target.getAddress().getHostAddress();
        SSLSocket s = (SSLSocket) getContext().getSocketFactory().createSocket(plain, host, target.getPort(), true);
        try {
            s.setEnabledProtocols(new String[] {"TLSv1.3"});
            s.setUseClientMode(client);
            if (!client) s.setNeedClientAuth(true);
            s.startHandshake();
        } catch (Exception e) {
            s.close();
            throw e;
        }
        return s;
    }

    public byte[] exportKeyingMaterial(SSLSocket socket, String label, int length) throws IOException {
        return exporter.export(socket, label, length);
    }

    private static final class FixedKeyManager extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate cert;

        FixedKeyManager(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {cert}; }
        @Override public PrivateKey getPrivateKey(String alias) { return key; }
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        static final TrustAll INSTANCE = new TrustAll();

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
