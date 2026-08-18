package com.cubiccadence.client;

import com.cubiccadence.client.ui.screen.MusicLibraryScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CubicCadenceClient implements ClientModInitializer {
    public static final String MOD_ID = "cubic-cadence";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping openLibraryKey;

    @Override
    public void onInitializeClient() {
        registerKeyBinding();
        registerClientTick();
        LOGGER.info("Cubic Cadence client initialized");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
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
            while (openLibraryKey.consumeClick()) {
                openMusicLibrary(client);
            }
        });
    }

    private void openMusicLibrary(Minecraft client) {
        client.setScreenAndShow(new MusicLibraryScreen());
    }
}
