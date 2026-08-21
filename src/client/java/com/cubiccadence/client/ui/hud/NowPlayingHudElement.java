package com.cubiccadence.client.ui.hud;

import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.ui.texture.RemoteTextureCache;
import com.cubiccadence.model.Artist;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.stream.Collectors;

/** Compact, reusable now-playing HUD with horizontally paired lyric lines. */
public final class NowPlayingHudElement implements HudElement {
    private static final int DEFAULT_WIDTH = 228;
    private static final int PADDING = 6;
    private static final int COVER_SIZE = 38;
    private static final int GAP = 6;
    private static final int PROGRESS_HEIGHT = 2;
    private static final int BACKGROUND = 0xB8181E29;
    private static final int BORDER = 0x805B6575;
    private static final int PRIMARY = 0xFFF4F6FA;
    private static final int SECONDARY = 0xFFB7BDC8;
    private static final int ACCENT = 0xFF9ED7A8;
    private static final int NEXT_LYRIC = 0xFF8E96A3;

    private final NowPlayingSource source;
    private final RemoteTextureCache textureCache;

    public NowPlayingHudElement(NowPlayingSource source, RemoteTextureCache textureCache) {
        this.source = Objects.requireNonNull(source, "source");
        this.textureCache = Objects.requireNonNull(textureCache, "textureCache");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        ModConfig config = ModConfig.getInstance();
        if (!config.isHudEnabled()
                || minecraft.player == null
                || minecraft.level == null
                || minecraft.gui.screen() != null
                || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        source.snapshot().ifPresent(snapshot -> render(graphics, minecraft.font, config, snapshot));
    }

    private void render(
            GuiGraphicsExtractor graphics,
            Font font,
            ModConfig config,
            NowPlayingSnapshot snapshot
    ) {
        boolean cover = config.isHudShowCover();
        boolean title = config.isHudShowTitle();
        boolean artist = config.isHudShowArtist();
        boolean progress = config.isHudShowProgress();
        boolean lyrics = config.isHudShowLyrics()
                && (!snapshot.currentLyric().isBlank() || !snapshot.nextLyric().isBlank());
        boolean hasDetails = title || artist || progress;
        if (!cover && !hasDetails && !lyrics) {
            return;
        }

        int availableWidth = graphics.guiWidth() - 16;
        if (availableWidth < 48) {
            return;
        }
        int width = cover && !hasDetails && !lyrics
                ? COVER_SIZE + PADDING * 2
                : Math.min(DEFAULT_WIDTH, availableWidth);
        int detailRows = (title ? 1 : 0) + (artist ? 1 : 0);
        int detailHeight = detailRows * font.lineHeight;
        if (progress) {
            detailHeight += (detailRows == 0 ? 0 : 5) + PROGRESS_HEIGHT;
        }
        int topHeight = Math.max(cover ? COVER_SIZE : 0, detailHeight);
        int lyricHeight = lyrics ? font.lineHeight + 5 : 0;
        int height = PADDING * 2 + topHeight + lyricHeight;
        int x = 8;
        int y = 8;

        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.outline(x, y, width, height, BORDER);

        int contentX = x + PADDING;
        if (cover) {
            renderCover(graphics, snapshot.track().coverUrl(), contentX, y + PADDING);
            contentX += COVER_SIZE + GAP;
        }
        int contentRight = x + width - PADDING;
        int contentWidth = Math.max(1, contentRight - contentX);
        int rowY = y + PADDING;
        if (title) {
            graphics.text(font, fit(font, snapshot.track().title(), contentWidth), contentX, rowY, PRIMARY);
            rowY += font.lineHeight;
        }
        if (artist) {
            graphics.text(font, fit(font, artists(snapshot), contentWidth), contentX, rowY, SECONDARY);
            rowY += font.lineHeight;
        }
        if (progress) {
            int progressY = y + PADDING + topHeight - PROGRESS_HEIGHT;
            graphics.fill(contentX, progressY, contentRight, progressY + PROGRESS_HEIGHT, 0xFF3B4350);
            long duration = snapshot.durationMs();
            double ratio = duration <= 0L
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, (double) snapshot.positionMs() / duration));
            int filled = (int) Math.round(contentWidth * ratio);
            if (filled > 0) {
                graphics.fill(contentX, progressY, contentX + filled, progressY + PROGRESS_HEIGHT, ACCENT);
            }
        }

        if (lyrics) {
            int lyricY = y + PADDING + topHeight + 5;
            int lyricWidth = width - PADDING * 2;
            int columnGap = 8;
            int currentWidth = Math.max(1, (lyricWidth - columnGap) * 55 / 100);
            int nextWidth = Math.max(1, lyricWidth - columnGap - currentWidth);
            Component current = fit(font, snapshot.currentLyric(), currentWidth);
            Component next = fit(font, snapshot.nextLyric(), nextWidth);
            if (!snapshot.currentLyric().isBlank()) {
                graphics.text(font, current, x + PADDING, lyricY, ACCENT);
            }
            if (!snapshot.nextLyric().isBlank()) {
                int nextTextWidth = font.width(next);
                graphics.text(font, next, contentRight - nextTextWidth, lyricY, NEXT_LYRIC);
            }
        }
    }

    private void renderCover(GuiGraphicsExtractor graphics, String url, int x, int y) {
        textureCache.getOrRequest(url).ifPresentOrElse(
                identifier -> graphics.blit(identifier, x, y, x + COVER_SIZE, y + COVER_SIZE, 0f, 1f, 0f, 1f),
                () -> {
                    graphics.fill(x, y, x + COVER_SIZE, y + COVER_SIZE, 0xFF2A313D);
                    graphics.outline(x, y, COVER_SIZE, COVER_SIZE, 0xFF596273);
                }
        );
    }

    private static Component fit(Font font, String value, int width) {
        if (value == null || value.isBlank()) {
            return Component.empty();
        }
        if (font.width(value) <= width) {
            return Component.literal(value);
        }
        int ellipsisWidth = font.width("…");
        return Component.literal(font.plainSubstrByWidth(value, Math.max(0, width - ellipsisWidth)) + "…");
    }

    private static String artists(NowPlayingSnapshot snapshot) {
        String value = snapshot.track().artists().stream()
                .map(Artist::name)
                .collect(Collectors.joining(" / "));
        return value.isBlank() ? "-" : value;
    }
}
