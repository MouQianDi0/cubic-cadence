package com.cubiccadence.client.library;

import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryCacheStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndDeletesPublicLibrarySnapshot() {
        Path cacheFile = temporaryDirectory.resolve("cache").resolve("library.json");
        LibrarySnapshot snapshot = new LibrarySnapshot(
                new UserProfile("netease", "42", "test", "avatar", 8, MembershipTier.BLACK_VINYL_VIP),
                List.of(new PlaylistSummary(
                        "netease",
                        "1001",
                        "liked",
                        "cover",
                        12,
                        PlaylistOwnership.SPECIAL
                )),
                1234L
        );

        try (LibraryCacheStore store = new LibraryCacheStore(cacheFile)) {
            store.writeAsync(snapshot).join();
            assertEquals(snapshot, store.readAsync().join().orElseThrow());

            store.deleteAsync().join();
            assertTrue(store.readAsync().join().isEmpty());
            assertTrue(Files.notExists(cacheFile));
        }
    }
}
