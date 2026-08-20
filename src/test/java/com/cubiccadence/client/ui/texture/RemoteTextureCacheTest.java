package com.cubiccadence.client.ui.texture;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTextureCacheTest {
    @Test
    void upgradesAllowListedNeteaseCdnUrlsToHttps() {
        assertEquals(
                URI.create("https://p4.music.126.net/avatar.jpg?param=180y180"),
                RemoteTextureCache.normalize("http://p4.music.126.net/avatar.jpg?param=180y180").orElseThrow()
        );
    }

    @Test
    void rejectsCredentialsFragmentsAndLookalikeHosts() {
        assertTrue(RemoteTextureCache.normalize("https://user@p1.music.126.net/image.jpg").isEmpty());
        assertTrue(RemoteTextureCache.normalize("https://p1.music.126.net/image.jpg#fragment").isEmpty());
        assertTrue(RemoteTextureCache.normalize("https://music.126.net.example.com/image.jpg").isEmpty());
    }
}
