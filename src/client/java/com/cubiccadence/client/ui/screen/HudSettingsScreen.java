package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Selects the provider-neutral fields shown by the now-playing HUD. */
public final class HudSettingsScreen extends Screen {
    private static final int CONTROL_WIDTH = 240;
    private static final int CONTROL_GAP = 8;
    private static final int COLUMN_GAP = 12;

    private final Screen parent;

    public HudSettingsScreen(Screen parent) {
        super(Component.translatable("screen.cubic-cadence.hud_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig config = ModConfig.getInstance();
        int left = (this.width - CONTROL_WIDTH) / 2;
        int rowHeight = Button.DEFAULT_HEIGHT + CONTROL_GAP;
        int contentHeight = Button.DEFAULT_HEIGHT * 5 + CONTROL_GAP * 4;
        int top = Math.max(46, (this.height - contentHeight) / 2);
        int columnWidth = (CONTROL_WIDTH - COLUMN_GAP) / 2;
        int right = left + columnWidth + COLUMN_GAP;

        addOption(left, top, CONTROL_WIDTH, "checkbox.cubic-cadence.hud_enabled", config.isHudEnabled(), config::setHudEnabled);
        addOption(left, top + rowHeight, columnWidth, "checkbox.cubic-cadence.hud_cover", config.isHudShowCover(), config::setHudShowCover);
        addOption(right, top + rowHeight, columnWidth, "checkbox.cubic-cadence.hud_title", config.isHudShowTitle(), config::setHudShowTitle);
        addOption(left, top + rowHeight * 2, columnWidth, "checkbox.cubic-cadence.hud_artist", config.isHudShowArtist(), config::setHudShowArtist);
        addOption(right, top + rowHeight * 2, columnWidth, "checkbox.cubic-cadence.hud_progress", config.isHudShowProgress(), config::setHudShowProgress);
        addOption(left, top + rowHeight * 3, CONTROL_WIDTH, "checkbox.cubic-cadence.hud_lyrics", config.isHudShowLyrics(), config::setHudShowLyrics);
        this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.done"), button -> onClose())
                        .bounds(left, top + rowHeight * 4, CONTROL_WIDTH, Button.DEFAULT_HEIGHT)
                        .build()
        );
    }

    private void addOption(
            int x,
            int y,
            int width,
            String key,
            boolean selected,
            Consumer<Boolean> setter
    ) {
        this.addRenderableWidget(
                Checkbox.builder(Component.translatable(key), this.font)
                        .pos(x, y)
                        .maxWidth(width)
                        .selected(selected)
                        .onValueChange((checkbox, value) -> setter.accept(value))
                        .build()
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        extractor.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
