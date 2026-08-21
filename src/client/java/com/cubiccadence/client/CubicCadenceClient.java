package com.cubiccadence.client;

import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.auth.WindowsDpapiTokenStore;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.library.MusicLibraryManager;
import com.cubiccadence.client.library.PlaylistDetailManager;
import com.cubiccadence.client.lyrics.LyricsManager;
import com.cubiccadence.client.playback.AudioEngine;
import com.cubiccadence.client.playback.JavaSoundAudioDecoder;
import com.cubiccadence.client.playback.PlayerController;
import com.cubiccadence.client.ui.screen.MusicLibraryScreen;
import com.cubiccadence.client.ui.hud.NowPlayingHudElement;
import com.cubiccadence.client.ui.hud.PlayerNowPlayingSource;
import com.cubiccadence.client.ui.texture.RemoteTextureCache;
import com.cubiccadence.client.provider.UnavailableMusicProvider;
import com.cubiccadence.client.provider.netease.NeteaseMusicProvider;
import com.cubiccadence.provider.MusicProvider;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;

public class CubicCadenceClient implements ClientModInitializer {
    public static final String MOD_ID = "cubic-cadence";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier LOCAL_TEST_AUDIO = id("audio/test-audio.wav");
    public static final Identifier LOCAL_TEST_AUDIO_MP3 = id("audio/eee.mp3");

    private static final AudioEngine AUDIO_ENGINE = new AudioEngine(new JavaSoundAudioDecoder());
    private static final RemoteTextureCache REMOTE_TEXTURE_CACHE = new RemoteTextureCache();
    private static AuthManager authManager;
    private static MusicLibraryManager libraryManager;
    private static PlaylistDetailManager playlistDetailManager;
    private static PlayerController playerController;
    private static LyricsManager lyricsManager;

    public static KeyMapping openLibraryKey;

    @Override
    public void onInitializeClient() {
        AUDIO_ENGINE.start();
        AUDIO_ENGINE.setVolume(ModConfig.getInstance().getVolume());
        MusicProvider musicProvider = createMusicProvider();
        authManager = createAuthManager(musicProvider);
        libraryManager = new MusicLibraryManager(musicProvider, authManager);
        playlistDetailManager = new PlaylistDetailManager(musicProvider, authManager);
        playerController = new PlayerController(
                musicProvider,
                authManager::getSession,
                AUDIO_ENGINE,
                Minecraft.getInstance()::execute
        );
        lyricsManager = new LyricsManager(
                musicProvider,
                authManager::getSession,
                Minecraft.getInstance()::execute
        );
        registerNowPlayingHud();
        authManager.restoreSession().exceptionally(throwable -> {
            LOGGER.warn("Cubic Cadence could not restore the saved login session");
            return null;
        });
        registerKeyBinding();
        registerClientTick();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ModConfig.getInstance().save();
            libraryManager.close();
            playlistDetailManager.close();
            playerController.stop();
            lyricsManager.close();
            authManager.close();
            REMOTE_TEXTURE_CACHE.close();
            AUDIO_ENGINE.close();
        });
        LOGGER.info("Cubic Cadence client initialized");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static AudioEngine getAudioEngine() {
        return AUDIO_ENGINE;
    }

    public static AuthManager getAuthManager() {
        if (authManager == null) {
            throw new IllegalStateException("Cubic Cadence authentication is not initialized");
        }
        return authManager;
    }

    public static MusicLibraryManager getLibraryManager() {
        if (libraryManager == null) {
            throw new IllegalStateException("Cubic Cadence music library is not initialized");
        }
        return libraryManager;
    }

    public static PlaylistDetailManager getPlaylistDetailManager() {
        if (playlistDetailManager == null) {
            throw new IllegalStateException("Cubic Cadence playlist details are not initialized");
        }
        return playlistDetailManager;
    }

    public static RemoteTextureCache getRemoteTextureCache() {
        return REMOTE_TEXTURE_CACHE;
    }

    public static PlayerController getPlayerController() {
        if (playerController == null) {
            throw new IllegalStateException("Cubic Cadence player is not initialized");
        }
        return playerController;
    }

    private static MusicProvider createMusicProvider() {
        String apiEnhancedUrl = ModConfig.getInstance().getApiEnhancedBaseUrl();
        MusicProvider provider;
        try {
            provider = apiEnhancedUrl.isBlank()
                    ? new UnavailableMusicProvider()
                    : new NeteaseMusicProvider(URI.create(apiEnhancedUrl));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignoring an invalid api-enhanced base URL");
            provider = new UnavailableMusicProvider();
        }
        return provider;
    }

    private static AuthManager createAuthManager(MusicProvider provider) {
        Path sessionFile = FabricLoader.getInstance().getConfigDir()
                .resolve("cubic-cadence")
                .resolve("auth-session.dpapi");
        return new AuthManager(provider, new WindowsDpapiTokenStore(sessionFile));
    }

    private void registerKeyBinding() {
        openLibraryKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.cubic-cadence.open_library",
                        InputConstants.KEY_M,
                        KeyMapping.Category.MISC
                )
        );
    }

    private void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AUDIO_ENGINE.tick();
            playerController.tick();
            lyricsManager.tick(playerController.getCurrentTrack());
            lyricsManager.preload(playerController.getUpcomingTrack());
            libraryManager.tick();
            playlistDetailManager.tick();
            while (openLibraryKey.consumeClick()) {
                openMusicLibrary(client);
            }
        });
    }

    private void registerNowPlayingHud() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR,
                id("now_playing_hud"),
                new NowPlayingHudElement(
                        new PlayerNowPlayingSource(playerController, lyricsManager),
                        REMOTE_TEXTURE_CACHE
                )
        );
    }

    private void openMusicLibrary(Minecraft client) {
        client.setScreenAndShow(new MusicLibraryScreen());
    }
}
