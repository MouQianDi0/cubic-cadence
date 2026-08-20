package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthSession;
import com.google.gson.Gson;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Stores only the gateway session, encrypted for the current Windows user. */
public final class WindowsDpapiTokenStore implements SecureTokenStore {
    private static final byte[] ENTROPY = "cubic-cadence:auth-session:v1".getBytes(StandardCharsets.UTF_8);
    private static final Gson GSON = new Gson();

    private final Path file;

    public WindowsDpapiTokenStore(Path file) {
        if (!Platform.isWindows()) {
            throw new IllegalStateException("Windows DPAPI token storage is only available on Windows");
        }
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void save(AuthSession session) {
        try {
            Files.createDirectories(file.getParent());
            byte[] plain = GSON.toJson(session).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = Crypt32Util.cryptProtectData(
                    plain, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Cubic Cadence session", null
            );
            Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, encrypted);
                moveIntoPlace(temporary);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to save the protected session", exception);
        }
    }

    @Override
    public synchronized Optional<AuthSession> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] plain = Crypt32Util.cryptUnprotectData(
                    Files.readAllBytes(file), ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null
            );
            return Optional.ofNullable(GSON.fromJson(new String(plain, StandardCharsets.UTF_8), AuthSession.class));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read the protected session", exception);
        }
    }

    @Override
    public synchronized void clear() {
        try {
            if (Files.isRegularFile(file)) {
                // Invalidate the payload first so a later delete failure cannot restore a logged-out session.
                Files.write(file, new byte[0], StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to clear the protected session", exception);
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
