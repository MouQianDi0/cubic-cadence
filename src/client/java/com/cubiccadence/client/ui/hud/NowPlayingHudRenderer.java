package com.cubiccadence.client.ui.hud;

import com.cubiccadence.client.config.HudSettings;
import com.cubiccadence.client.ui.texture.RemoteTextureCache;
import com.cubiccadence.model.Artist;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.stream.Collectors;

/** Shared renderer used by both the live HUD and its settings preview. */
public final class NowPlayingHudRenderer {
    private static final int DEFAULT_WIDTH = 228;
    private static final int SCREEN_MARGIN = 8;
    private static final int PADDING = 6;
    private static final int COVER_SIZE = 38;
    private static final int GAP = 6;
    private static final int PROGRESS_HEIGHT = 2;
    private static final int BACKGROUND = 0xB8181E29;
    private static final int BORDER = 0x805B6575;
    private static final int PRIMARY = 0xFFF4F6FA;
    private static final int SECONDARY = 0xFFB7BDC8;
    private static final int PROGRESS_BACKGROUND = 0xFF3B4350;

    private NowPlayingHudRenderer() {
    }

    public static void render(
            GuiGraphicsExtractor graphics,
            Font font,
            HudSettings settings,
            HudContent content,
            int viewportWidth,
            int viewportHeight,
            int originX,
            int originY,
            float viewportScale,
            RemoteTextureCache textureCache
    ) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(content, "content");
        if (!settings.enabled() || viewportWidth <= 0 || viewportHeight <= 0 || viewportScale <= 0.0f) {
            return;
        }

        Layout layout = measure(font, settings, content, viewportWidth);
        if (layout == null) {
            return;
        }

        int scaledWidth = Math.round(layout.width() * settings.scale());
        int scaledHeight = Math.round(layout.height() * settings.scale());
        int x = settings.position().x(viewportWidth, scaledWidth, settings.offsetX(), SCREEN_MARGIN);
        int y = settings.position().y(viewportHeight, scaledHeight, settings.offsetY(), SCREEN_MARGIN);

        graphics.pose().pushMatrix();
        graphics.pose().translate(originX, originY);
        graphics.pose().scale(viewportScale, viewportScale);
        graphics.pose().translate(x, y);
        graphics.pose().scale(settings.scale(), settings.scale());
        renderLocal(graphics, font, settings, content, layout, textureCache);
        graphics.pose().popMatrix();
    }

    public static HudContent fromSnapshot(NowPlayingSnapshot snapshot) {
        String artists = snapshot.track().artists().stream()
                .map(Artist::name)
                .collect(Collectors.joining(" / "));
        return new HudContent(
                snapshot.track().title(),
                artists.isBlank() ? "-" : artists,
                snapshot.track().coverUrl(),
                snapshot.positionMs(),
                snapshot.durationMs(),
                snapshot.currentLyric(),
                snapshot.nextLyric()
        );
    }

    private static Layout measure(Font font, HudSettings settings, HudContent content, int viewportWidth) {
        boolean lyrics = settings.showLyrics()
                && (!content.currentLyric().isBlank() || !content.nextLyric().isBlank());
        boolean hasDetails = settings.showTitle() || settings.showArtist() || settings.showProgress();
        if (!settings.showCover() && !hasDetails && !lyrics) {
            return null;
        }

        int availableWidth = viewportWidth - SCREEN_MARGIN * 2;
        int maximumBaseWidth = (int) Math.floor(availableWidth / settings.scale());
        if (maximumBaseWidth < 48) {
            return null;
        }
        int width = settings.showCover() && !hasDetails && !lyrics
                ? COVER_SIZE + PADDING * 2
                : Math.min(DEFAULT_WIDTH, maximumBaseWidth);
        int detailHeight = 0;
        if (settings.showTitle()) {
            detailHeight += scaledLineHeight(font, settings.titleScale());
        }
        if (settings.showArtist()) {
            detailHeight += font.lineHeight;
        }
        if (settings.showProgress()) {
            detailHeight += (detailHeight == 0 ? 0 : 5) + PROGRESS_HEIGHT;
        }
        int topHeight = Math.max(settings.showCover() ? COVER_SIZE : 0, detailHeight);
        int lyricHeight = lyrics ? scaledLineHeight(font, settings.lyricScale()) + 5 : 0;
        return new Layout(width, PADDING * 2 + topHeight + lyricHeight, topHeight, lyrics);
    }

    private static void renderLocal(
            GuiGraphicsExtractor graphics,
            Font font,
            HudSettings settings,
            HudContent content,
            Layout layout,
            RemoteTextureCache textureCache
    ) {
        if (settings.backgroundEnabled()) {
            graphics.fill(0, 0, layout.width(), layout.height(), BACKGROUND);
            graphics.outline(0, 0, layout.width(), layout.height(), BORDER);
        }

        int contentX = PADDING;
        if (settings.showCover()) {
            renderCover(graphics, content.coverUrl(), contentX, PADDING, textureCache);
            contentX += COVER_SIZE + GAP;
        }
        int contentRight = layout.width() - PADDING;
        int contentWidth = Math.max(1, contentRight - contentX);
        int rowY = PADDING;
        if (settings.showTitle()) {
            drawScaledText(
                    graphics,
                    font,
                    fit(font, content.title(), availableTextWidth(contentWidth, settings.titleScale())),
                    contentX,
                    rowY,
                    PRIMARY,
                    settings.titleScale()
            );
            rowY += scaledLineHeight(font, settings.titleScale());
        }
        if (settings.showArtist()) {
            graphics.text(font, fit(font, content.artist(), contentWidth), contentX, rowY, SECONDARY);
        }
        if (settings.showProgress()) {
            int progressY = PADDING + layout.topHeight() - PROGRESS_HEIGHT;
            graphics.fill(contentX, progressY, contentRight, progressY + PROGRESS_HEIGHT, PROGRESS_BACKGROUND);
            double ratio = content.durationMs() <= 0L
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, (double) content.positionMs() / content.durationMs()));
            int filled = (int) Math.round(contentWidth * ratio);
            if (filled > 0) {
                graphics.fill(contentX, progressY, contentX + filled, progressY + PROGRESS_HEIGHT, settings.lyricColor());
            }
        }

        if (layout.lyrics()) {
            int lyricY = PADDING + layout.topHeight() + 5;
            int lyricWidth = layout.width() - PADDING * 2;
            int columnGap = 8;
            int currentWidth = Math.max(1, (lyricWidth - columnGap) * 55 / 100);
            int nextWidth = Math.max(1, lyricWidth - columnGap - currentWidth);
            Component current = fit(font, content.currentLyric(), availableTextWidth(currentWidth, settings.lyricScale()));
            Component next = fit(font, content.nextLyric(), availableTextWidth(nextWidth, settings.lyricScale()));
            if (!content.currentLyric().isBlank()) {
                drawScaledText(
                        graphics,
                        font,
                        current,
                        PADDING,
                        lyricY,
                        settings.lyricColor(),
                        settings.lyricScale()
                );
            }
            if (!content.nextLyric().isBlank()) {
                int nextTextWidth = Math.round(font.width(next) * settings.lyricScale());
                drawScaledText(
                        graphics,
                        font,
                        next,
                        contentRight - nextTextWidth,
                        lyricY,
                        darken(settings.lyricColor(), 0.6f),
                        settings.lyricScale()
                );
            }
        }
    }

    private static void renderCover(
            GuiGraphicsExtractor graphics,
            String url,
            int x,
            int y,
            RemoteTextureCache textureCache
    ) {
        if (textureCache != null && url != null && !url.isBlank()) {
            textureCache.getOrRequest(url).ifPresentOrElse(
                    identifier -> graphics.blit(identifier, x, y, x + COVER_SIZE, y + COVER_SIZE, 0f, 1f, 0f, 1f),
                    () -> renderCoverPlaceholder(graphics, x, y)
            );
            return;
        }
        renderCoverPlaceholder(graphics, x, y);
    }

    private static void renderCoverPlaceholder(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + COVER_SIZE, y + COVER_SIZE, 0xFF2A313D);
        graphics.outline(x, y, COVER_SIZE, COVER_SIZE, 0xFF596273);
        graphics.fill(x + 9, y + 9, x + 29, y + 29, 0xFF414B5A);
        graphics.fill(x + 15, y + 6, x + 29, y + 12, 0xFF7C8798);
    }

    private static void drawScaledText(
            GuiGraphicsExtractor graphics,
            Font font,
            Component text,
            int x,
            int y,
            int color,
            float scale
    ) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, text, 0, 0, color);
        graphics.pose().popMatrix();
    }

    private static int availableTextWidth(int renderedWidth, float scale) {
        return Math.max(1, (int) Math.floor(renderedWidth / scale));
    }

    private static int scaledLineHeight(Font font, float scale) {
        return Math.max(1, (int) Math.ceil(font.lineHeight * scale));
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

    private static int darken(int color, float factor) {
        int red = Math.round((color >> 16 & 0xFF) * factor);
        int green = Math.round((color >> 8 & 0xFF) * factor);
        int blue = Math.round((color & 0xFF) * factor);
        return color & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private record Layout(int width, int height, int topHeight, boolean lyrics) {
    }

    public record HudContent(
            String title,
            String artist,
            String coverUrl,
            long positionMs,
            long durationMs,
            String currentLyric,
            String nextLyric
    ) {
        public HudContent {
            title = normalize(title);
            artist = normalize(artist);
            coverUrl = normalize(coverUrl);
            positionMs = Math.max(0L, positionMs);
            durationMs = Math.max(0L, durationMs);
            currentLyric = normalize(currentLyric);
            nextLyric = normalize(nextLyric);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
