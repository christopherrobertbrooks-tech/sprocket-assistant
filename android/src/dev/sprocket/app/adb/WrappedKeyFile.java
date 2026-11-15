// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The key stored encrypted in a file: file contents = 1 byte IV length + IV + AES-GCM ciphertext.
 * The Cipher generates the IV itself (Android Keystore keys don't let the caller choose the IV).
 */
public final class WrappedKeyFile implements AdbKeyStore {
    /** Supplies the wrapping key (in the app: an AES key in Android Keystore). */
    public interface WrappingKey {
        SecretKey get() throws GeneralSecurityException, IOException;
    }

    private final File file;
    private final WrappingKey wrappingKey;

    public WrappedKeyFile(File file, WrappingKey wrappingKey) {
        this.file = file;
        this.wrappingKey = wrappingKey;
    }

    @Override
    public byte[] read() throws AdbKeyUnreadableException {
        if (!file.isFile()) return null;
        try {
            byte[] b = Files.readAllBytes(file.toPath());
            if (b.length == 0) throw new IllegalArgumentException("key file is empty");
            int ivLen = b[0] & 0xff;
            if (!(b.length > 1 + ivLen)) throw new IllegalArgumentException("key file is truncated");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[ivLen];
            System.arraycopy(b, 1, iv, 0, ivLen);
            c.init(Cipher.DECRYPT_MODE, wrappingKey.get(), new GCMParameterSpec(128, iv));
            return c.doFinal(b, 1 + ivLen, b.length - 1 - ivLen);
        } catch (Exception e) {
            throw new AdbKeyUnreadableException("cannot unwrap " + file.getName(), e);
        }
    }

    @Override
    public void write(byte[] pkcs8) throws IOException, GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrappingKey.get());
        byte[] iv = c.getIV();
        byte[] sealed = c.doFinal(pkcs8);
        byte[] out = new byte[1 + iv.length + sealed.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(sealed, 0, out, 1 + iv.length, sealed.length);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(out);
        }
        if (!tmp.renameTo(file)) throw new IllegalStateException("cannot write " + file.getName());
    }

    @Override
    public void clear() {
        file.delete();
    }
}
