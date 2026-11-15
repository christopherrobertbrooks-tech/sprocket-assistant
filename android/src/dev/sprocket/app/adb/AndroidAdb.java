// Ported to Java from Mika (https://github.com/wangzan101/Mika), app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/AndroidAdb.kt, Copyright 2026 wangzan101, Apache License 2.0.
// Sprocket changes: key name "sprocket@<model>" (was "mika@<model>"), Keystore alias / log tag renamed, and an API 31
// check in the exporter (Sprocket's minSdk is 26; SSLSockets.exportKeyingMaterial only exists from Android 12).
package dev.sprocket.app.adb;

import android.content.Context;
import android.net.ssl.SSLSockets;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLException;

/** adb identity and TLS on Android: the key is wrapped with an AES key from the Keystore and kept in app-private storage; key export goes through the platform TLS. */
public final class AndroidAdb {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "sprocket_adb_key_wrap";
    private static final String TAG = "SprocketAdbKey";

    private AndroidAdb() {}

    // The device exports without a context; in TLS 1.3 an empty context gives the same result as no context (RFC 8446 section 7.5)
    public static final KeyingMaterialExporter EXPORTER = (socket, label, length) -> {
        if (Build.VERSION.SDK_INT < 31) throw new SSLException("keying material export needs Android 12 (API 31)");
        byte[] out = SSLSockets.exportKeyingMaterial(socket, label, new byte[0], length);
        if (out == null) throw new SSLException("keying material export failed");
        return out;
    };

    private static volatile AdbIdentitySource source;

    /** The one identity source in this process: pairing and connecting both take from here and get the same key. */
    public static AdbIdentitySource identities(Context context) {
        AdbIdentitySource s = source;
        if (s != null) return s;
        synchronized (AndroidAdb.class) {
            if (source == null) {
                source = new AdbIdentitySource(keyStore(context), () -> "sprocket@" + Build.MODEL, (msg, e) -> Log.w(TAG, msg, e));
            }
            return source;
        }
    }

    /** The identity for connecting; throws {@link AdbKeyUnreadableException} when the stored key can't be read. */
    public static AdbIdentity identity(Context context) throws AdbKeyUnreadableException, GeneralSecurityException, IOException {
        return identities(context).forConnect();
    }

    /** The identity for pairing: replaces a stored key that can't be read (see {@link AdbIdentitySource#forPairing()}). */
    public static AdbIdentity pairingIdentity(Context context) throws GeneralSecurityException, IOException {
        return identities(context).forPairing();
    }

    public static AdbTls tls(AdbIdentity identity) {
        return new AdbTls(identity, EXPORTER);
    }

    /** Deletes the key and this process's cache ("unpair"); the user revokes the device-side authorization in system Settings. */
    public static void forget(Context context) {
        identities(context).forget();
    }

    /** The key file + the Keystore key wrapping it: clearing deletes both. */
    private static AdbKeyStore keyStore(Context context) {
        final WrappedKeyFile file = new WrappedKeyFile(new File(context.getApplicationContext().getNoBackupFilesDir(), "adb/adbkey.bin"), AndroidAdb::wrappingKey);
        return new AdbKeyStore() {
            @Override public byte[] read() throws AdbKeyUnreadableException { return file.read(); }
            @Override public void write(byte[] pkcs8) throws IOException, GeneralSecurityException { file.write(pkcs8); }
            @Override public void clear() {
                file.clear();
                try {
                    KeyStore ks = KeyStore.getInstance(KEYSTORE);
                    ks.load(null);
                    ks.deleteEntry(WRAP_ALIAS);
                } catch (Exception ignored) {
                }
            }
        };
    }

    private static SecretKey wrappingKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        Key existing = ks.getKey(WRAP_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
