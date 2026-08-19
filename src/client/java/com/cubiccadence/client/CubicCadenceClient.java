package com.cubiccadence.client;

import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.auth.WindowsDpapiTokenStore;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.playback.AudioEngine;
import com.cubiccadence.client.playback.JavaSoundAudioDecoder;
import com.cubiccadence.client.ui.screen.MusicLibraryScreen;
import com.cubiccadence.client.provider.UnavailableMusicProvider;
import com.cubiccadence.client.provider.netease.NeteaseMusicProvider;
import com.cubiccadence.provider.MusicProvider;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
    private static AuthManager authManager;

    public static KeyMapping openLibraryKey;

    @Override
    public void onInitializeClient() {
        AUDIO_ENGINE.start();
        AUDIO_ENGINE.setVolume(ModConfig.getInstance().getVolume());
        authManager = createAuthManager();
        authManager.restoreSession().exceptionally(throwable -> {
            LOGGER.warn("Cubic Cadence could not restore the saved login session");
            return null;
        });
        registerKeyBinding();
        registerClientTick();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ModConfig.getInstance().save();
            authManager.close();
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

    private static AuthManager createAuthManager() {
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
            while (openLibraryKey.consumeClick()) {
                openMusicLibrary(client);
            }
        });
    }

    private void openMusicLibrary(Minecraft client) {
        client.setScreenAndShow(new MusicLibraryScreen());
    }
}
