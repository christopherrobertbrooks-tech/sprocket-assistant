// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Our own adb identity: one RSA-2048 key + a self-signed certificate. When pairing, {@link #getAdbPublicKey()} is
 * handed to the device; every later TLS connection presents {@link #getCertificate()} (the device only compares the
 * public key inside the certificate).
 */
public final class AdbIdentity {
    /** Public exponent for new keys: F4 (65537). When a stored key gives no exponent, this one is filled in, and checked. */
    public static final BigInteger PUBLIC_EXPONENT = RSAKeyGenParameterSpec.F4;

    public final KeyPair keyPair;
    public final String name;
    private X509Certificate certificate;
    private String adbPublicKey;

    public AdbIdentity(KeyPair keyPair, String name) {
        this.keyPair = keyPair;
        this.name = name;
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /** Built on first use (Kotlin: {@code by lazy}). */
    public synchronized X509Certificate getCertificate() throws GeneralSecurityException {
        if (certificate == null) certificate = SelfSignedCertificate.create(keyPair);
        return certificate;
    }

    /**
     * One line of the device's keystore: base64(524-byte public key structure) + a space + the name.
     * The name must contain no whitespace: system Settings splits this line on whitespace, and a name with spaces can't
     * be removed from "Paired devices".
     */
    public synchronized String getAdbPublicKey() {
        if (adbPublicKey == null) {
            adbPublicKey = Base64.getEncoder().encodeToString(AdbPublicKey.encode(getPublicKey())) + " " + name.replaceAll("\\s", "_");
        }
        return adbPublicKey;
    }

    public static AdbIdentity generate(String name) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, PUBLIC_EXPONENT));
        return new AdbIdentity(g.generateKeyPair(), name);
    }

    /**
     * Reads an identity back from stored PKCS#8. Doesn't require the platform to hand back a private key with CRT
     * parameters: any RSA private key gives the modulus, and the public exponent is found by {@link #publicExponent}.
     * Throws {@link AdbKeyUnreadableException} when it can't be read.
     */
    public static AdbIdentity fromPkcs8(byte[] pkcs8, String name) throws AdbKeyUnreadableException, GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv;
        try {
            priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new AdbKeyUnreadableException("stored key is not valid PKCS#8", e);
        }
        if (!(priv instanceof RSAPrivateKey)) {
            throw new AdbKeyUnreadableException("stored key is not RSA (" + priv.getClass().getSimpleName() + ")");
        }
        RSAPrivateKey rsa = (RSAPrivateKey) priv;
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(rsa.getModulus(), publicExponent(rsa, pkcs8)));
        return new AdbIdentity(new KeyPair(pub, rsa), name);
    }

    /**
     * The public exponent for a private key: use it directly when the platform gives the CRT form; otherwise read it
     * from the RSAPrivateKey structure inside the PKCS#8; only when the structure lacks it (writes 0) fill in
     * {@link #PUBLIC_EXPONENT}, after one sign-and-verify check; if that doesn't match, throw rather than connect with
     * the wrong public key.
     */
    static BigInteger publicExponent(RSAPrivateKey key, byte[] pkcs8) throws AdbKeyUnreadableException {
        if (key instanceof RSAPrivateCrtKey) {
            BigInteger e = ((RSAPrivateCrtKey) key).getPublicExponent();
            if (e != null && e.signum() > 0) return e;
        }
        BigInteger stored = Pkcs8Rsa.publicExponent(pkcs8);
        if (stored != null && stored.signum() > 0) return stored;
        if (!signsWith(key, PUBLIC_EXPONENT)) throw new AdbKeyUnreadableException("cannot recover the public exponent of the stored key");
        return PUBLIC_EXPONENT;
    }

    private static boolean signsWith(RSAPrivateKey key, BigInteger e) {
        try {
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(), e));
            byte[] data = "mika-adb-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(data);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pub);
            verifier.update(data);
            return verifier.verify(sig);
        } catch (Exception ex) {
            return false;
        }
    }
}
