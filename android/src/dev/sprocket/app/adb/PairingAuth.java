// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/PairingAuth.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/** Pairing authentication: exchange SPAKE2 messages, then build a {@link PairingCipher} from the key. The names include the trailing NUL, as on the device. */
public final class PairingAuth {
    public static final byte[] CLIENT_NAME = "adb pair client\u0000".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SERVER_NAME = "adb pair server\u0000".getBytes(StandardCharsets.US_ASCII);

    private final Spake2 spake;
    private final byte[] message;
    private PairingCipher cipher;

    private PairingAuth(Spake2.Role role, byte[] password, byte[] seed) {
        spake = role == Spake2.Role.ALICE ? new Spake2(role, CLIENT_NAME, SERVER_NAME) : new Spake2(role, SERVER_NAME, CLIENT_NAME);
        message = seed == null ? spake.generateMessage(password, new SecureRandom()) : spake.generateMessage(password, seed);
    }

    public static PairingAuth client(byte[] password) { return new PairingAuth(Spake2.Role.ALICE, password, null); }
    public static PairingAuth server(byte[] password) { return new PairingAuth(Spake2.Role.BOB, password, null); }
    static PairingAuth client(byte[] password, byte[] seed) { return new PairingAuth(Spake2.Role.ALICE, password, seed); }
    static PairingAuth server(byte[] password, byte[] seed) { return new PairingAuth(Spake2.Role.BOB, password, seed); }

    /** Our SPAKE2 message (32 bytes). Like the Kotlin property, this is the array itself, not a copy. */
    public byte[] getMessage() { return message; }

    public void initCipher(byte[] theirMessage) throws GeneralSecurityException {
        if (cipher != null) throw new IllegalStateException("Check failed.");
        cipher = new PairingCipher(spake.processMessage(theirMessage));
    }

    public byte[] encrypt(byte[] data) throws GeneralSecurityException { return requireCipher().encrypt(data); }

    public byte[] decrypt(byte[] data) throws GeneralSecurityException { return requireCipher().decrypt(data); }

    private PairingCipher requireCipher() {
        if (cipher == null) throw new IllegalStateException("Required value was null.");
        return cipher;
    }
}
