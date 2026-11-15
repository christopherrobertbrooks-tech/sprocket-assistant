// Ported to Java from Mika (https://github.com/wangzan101/Mika), core/adb/AdbIdentity.kt, Copyright 2026 wangzan101, Apache License 2.0.
package dev.sprocket.app.adb;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * There is exactly one adb identity per process: pairing and connecting always get the same key, and never each use
 * a different one because one read failed.
 * Only pairing ({@link #forPairing()}) replaces a stored key that can't be read (pairing hands the new key to the
 * device anyway); connecting ({@link #forConnect()}) throws {@link AdbKeyUnreadableException} instead, and the UI asks
 * the user to pair again.
 */
public final class AdbIdentitySource {
    private final AdbKeyStore store;
    private final Supplier<String> name;
    private final BiConsumer<String, Throwable> log;
    private AdbIdentity cached;

    private volatile AdbKeyState state = AdbKeyState.NotLoaded;

    /** Why the last read failed (for diagnostics); null when it reads fine. */
    private volatile String problem;

    public AdbIdentitySource(AdbKeyStore store, Supplier<String> name) {
        this(store, name, (msg, e) -> { });
    }

    public AdbIdentitySource(AdbKeyStore store, Supplier<String> name, BiConsumer<String, Throwable> log) {
        this.store = store;
        this.name = name;
        this.log = log;
    }

    public AdbKeyState getState() { return state; }

    public String getProblem() { return problem; }

    /** For connecting: the stored key; when none exists, make and store one; when one is stored but unreadable, throw and don't overwrite it. */
    public synchronized AdbIdentity forConnect() throws AdbKeyUnreadableException, GeneralSecurityException, IOException {
        return cached != null ? cached : load();
    }

    /** For pairing: the same key as {@link #forConnect()}; only when the stored one is unreadable, replace it here with a new stored one (the pairing that follows hands it to the device). */
    public synchronized AdbIdentity forPairing() throws GeneralSecurityException, IOException {
        if (cached != null) return cached;
        try {
            return load();
        } catch (AdbKeyUnreadableException e) {
            log.accept("replacing the unreadable adb key for a new pairing", e);
            store.clear();
            return create();
        }
    }

    /** Unpair: delete the key and the cache together; the next pairing uses a new key. */
    public synchronized void forget() {
        cached = null;
        store.clear();
        state = AdbKeyState.NotLoaded;
        problem = null;
    }

    private AdbIdentity load() throws AdbKeyUnreadableException, GeneralSecurityException, IOException {
        byte[] bytes;
        try {
            bytes = store.read();
        } catch (AdbKeyUnreadableException e) {
            throw unreadable(e);
        }
        if (bytes == null) return create();
        AdbIdentity id;
        try {
            id = AdbIdentity.fromPkcs8(bytes, name.get());
        } catch (AdbKeyUnreadableException e) {
            throw unreadable(e);
        }
        state = AdbKeyState.Loaded;
        problem = null;
        cached = id;
        return id;
    }

    private AdbIdentity create() throws GeneralSecurityException, IOException {
        AdbIdentity id = AdbIdentity.generate(name.get());
        store.write(id.keyPair.getPrivate().getEncoded());
        state = AdbKeyState.Created;
        problem = null;
        cached = id;
        return id;
    }

    private AdbKeyUnreadableException unreadable(AdbKeyUnreadableException e) {
        state = AdbKeyState.Unreadable;
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        problem = sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
        log.accept("stored adb key is unreadable; pair again to replace it", e);
        return e;
    }
}
