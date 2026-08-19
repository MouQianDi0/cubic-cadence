package com.cubiccadence.client.auth;

import com.cubiccadence.auth.AuthSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiTokenStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void encryptsRoundTripsAndClearsTheGatewaySession() throws Exception {
        Path file = temporaryDirectory.resolve("auth-session.dpapi");
        WindowsDpapiTokenStore store = new WindowsDpapiTokenStore(file);
        AuthSession session = new AuthSession("netease", "secret-cookie", 2_000_000_000_000L);

        store.save(session);

        assertEquals(session, store.load().orElseThrow());
        String rawFile = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(rawFile.contains("secret-cookie"));

        store.clear();
        assertTrue(store.load().isEmpty());
    }
}
