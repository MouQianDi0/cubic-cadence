package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.CubicCadenceClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class MusicLibraryScreen extends Screen {
    public MusicLibraryScreen() {
        super(Component.literal("Cubic Cadence"));
    }

    @Override
    protected void init() {
        // TODO: initialize the list and buttons
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        extractor.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CubicCadenceClient.openLibraryKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        super.onClose();
    }
}
