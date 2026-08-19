package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.mixin.CheckboxAccessor;
import com.cubiccadence.client.playback.AudioEngine;
import com.cubiccadence.model.PlaybackState;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

public class MusicLibraryScreen extends Screen {
    private static final int CONTROL_WIDTH = 220;
    private static final int BUTTON_GAP = 8;// 按钮之间的间距
    private static final int MEDIA_BUTTON_WIDTH = 40;// 播放/暂停按钮和停止按钮的宽度
    private static final int FORMAT_BUTTON_WIDTH = 72;
    private static final int MEDIA_CONTROLS_WIDTH = MEDIA_BUTTON_WIDTH * 2 + FORMAT_BUTTON_WIDTH + BUTTON_GAP * 2;
    private static final double MIN_AUDIBLE_VOLUME = 0.0001;
    private static double lastNonZeroVanillaMusicVolume = 1.0;
    private static final Identifier[] TEST_TRACKS = {
            CubicCadenceClient.LOCAL_TEST_AUDIO,
            CubicCadenceClient.LOCAL_TEST_AUDIO_MP3
    };
    private static final String[] TEST_TRACK_LABELS = {"WAV", "MP3"};

    private final AudioEngine audioEngine;
    private Button playPauseButton;
    private Button stopButton;
    private Button formatButton;
    private Checkbox disableVanillaMusicCheckbox;
    private ProgressSlider progressSlider;
    private VanillaMusicVolumeSlider vanillaMusicVolumeSlider;
    private int testTrackIndex;

    public MusicLibraryScreen() {
        super(Component.literal("Cubic Cadence"));
        this.audioEngine = CubicCadenceClient.getAudioEngine();
    }

    @Override
    protected void init() {
        int left = (this.width - CONTROL_WIDTH) / 2;
        int mediaLeft = (this.width - MEDIA_CONTROLS_WIDTH) / 2;
        int controlsTop = Math.max(114, this.height / 2 - 21);
        double vanillaMusicVolume = getVanillaMusicVolume();
        if (vanillaMusicVolume > MIN_AUDIBLE_VOLUME) {
            lastNonZeroVanillaMusicVolume = vanillaMusicVolume;
        }

        this.progressSlider = this.addRenderableWidget(new ProgressSlider(
                (this.width - ProgressSlider.BAR_WIDTH) / 2,
                controlsTop
                        - ProgressSlider.WIDGET_HEIGHT
                        - ProgressSlider.TIME_GAP
                        - this.font.lineHeight
                        - ProgressSlider.BUTTON_GAP,
                this.audioEngine
        ));
        this.playPauseButton = this.addRenderableWidget(
                Button.builder(playPauseIcon(), button -> handlePlayPause())
                        .bounds(mediaLeft, controlsTop, MEDIA_BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
                        .tooltip(Tooltip.create(playPauseLabel()))
                        .build()
        );
        this.stopButton = this.addRenderableWidget(
                Button.builder(Component.literal("■"), button -> this.audioEngine.stop())
                        .bounds(
                                mediaLeft + MEDIA_BUTTON_WIDTH + BUTTON_GAP,
                                controlsTop,
                                MEDIA_BUTTON_WIDTH,
                                Button.DEFAULT_HEIGHT
                        )
                        .tooltip(Tooltip.create(Component.translatable("button.cubic-cadence.stop")))
                        .build()
        );
        this.formatButton = this.addRenderableWidget(
                Button.builder(Component.literal(TEST_TRACK_LABELS[this.testTrackIndex]), button -> cycleTestTrack())
                        .bounds(
                                mediaLeft + MEDIA_BUTTON_WIDTH * 2 + BUTTON_GAP * 2,
                                controlsTop,
                                FORMAT_BUTTON_WIDTH,
                                Button.DEFAULT_HEIGHT
                        )
                        .tooltip(Tooltip.create(Component.literal("Local test audio format")))
                        .build()
        );
        this.disableVanillaMusicCheckbox = this.addRenderableWidget(
                Checkbox.builder(
                                Component.translatable("checkbox.cubic-cadence.disable_vanilla_music"),
                                this.font
                        )
                        .pos(left, controlsTop + Button.DEFAULT_HEIGHT + 8)
                        .maxWidth(CONTROL_WIDTH)
                        .selected(vanillaMusicVolume <= MIN_AUDIBLE_VOLUME)
                        .onValueChange((checkbox, selected) -> handleVanillaMusicToggle(selected))
                        .build()
        );
        this.vanillaMusicVolumeSlider = this.addRenderableWidget(new VanillaMusicVolumeSlider(
                left,
                controlsTop + Button.DEFAULT_HEIGHT + 34,
                CONTROL_WIDTH,
                Button.DEFAULT_HEIGHT,
                getVanillaMusicOption(),
                this::handleVanillaMusicSliderChange
        ));
        this.vanillaMusicVolumeSlider.active = vanillaMusicVolume > MIN_AUDIBLE_VOLUME;
        this.addRenderableWidget(new VolumeSlider(
                left,
                controlsTop + Button.DEFAULT_HEIGHT + 62,
                CONTROL_WIDTH,
                Button.DEFAULT_HEIGHT,
                this.audioEngine
        ));
        updateControls();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        extractor.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        extractor.centeredText(
                this.font,
                Component.translatable("label.cubic-cadence.local_test_track"),
                this.width / 2,
                48,
                0xFFFFFFFF
        );
        extractor.centeredText(
                this.font,
                stateMessage(),
                this.width / 2,
                66,
                this.audioEngine.getState() == PlaybackState.ERROR ? 0xFFFF6B6B : 0xFFBDBDBD
        );
        if (this.progressSlider != null) {
            extractor.centeredText(
                    this.font,
                    this.progressSlider.visibleTimeMessage(),
                    this.progressSlider.getX() + ProgressSlider.BAR_WIDTH / 2,
                    this.progressSlider.getBottom() + ProgressSlider.TIME_GAP,
                    0xFFBDBDBD
            );
        }
    }

    @Override
    public void tick() {
        if (this.progressSlider != null) {
            this.progressSlider.syncFromEngine();
        }
        updateControls();
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
        if (this.minecraft != null) {
            this.minecraft.options.save();
        }
        super.onClose();
    }

    private void handleVanillaMusicToggle(boolean disabled) {
        if (disabled) {
            double currentVolume = getVanillaMusicVolume();
            if (currentVolume > MIN_AUDIBLE_VOLUME) {
                lastNonZeroVanillaMusicVolume = currentVolume;
            }
            setVanillaMusicVolume(0.0);
        } else {
            setVanillaMusicVolume(Math.max(MIN_AUDIBLE_VOLUME, lastNonZeroVanillaMusicVolume));
        }
        syncVanillaMusicControls();
    }

    private void handleVanillaMusicSliderChange(double volume) {
        if (volume > MIN_AUDIBLE_VOLUME) {
            lastNonZeroVanillaMusicVolume = volume;
        }
        syncVanillaMusicControls();
    }

    private void syncVanillaMusicControls() {
        if (this.disableVanillaMusicCheckbox == null || this.vanillaMusicVolumeSlider == null) {
            return;
        }
        double volume = getVanillaMusicVolume();
        boolean disabled = volume <= MIN_AUDIBLE_VOLUME;
        ((CheckboxAccessor) this.disableVanillaMusicCheckbox).cubicCadence$setSelected(disabled);
        this.vanillaMusicVolumeSlider.syncFromOption();
        this.vanillaMusicVolumeSlider.active = !disabled;
    }

    private OptionInstance<Double> getVanillaMusicOption() {
        return this.minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
    }

    private double getVanillaMusicVolume() {
        return getVanillaMusicOption().get();
    }

    private void setVanillaMusicVolume(double volume) {
        getVanillaMusicOption().set(Math.max(0.0, Math.min(1.0, volume)));
    }

    private void handlePlayPause() {
        switch (this.audioEngine.getState()) {
            case PLAYING -> this.audioEngine.pause();
            case PAUSED -> this.audioEngine.resume();
            case BUFFERING, RESOLVING -> {
                return;
            }
            default -> this.audioEngine.playLocal(TEST_TRACKS[this.testTrackIndex]);
        }
        updateControls();
    }

    private void cycleTestTrack() {
        this.testTrackIndex = (this.testTrackIndex + 1) % TEST_TRACKS.length;
        this.audioEngine.stop();
        if (this.formatButton != null) {
            this.formatButton.setMessage(Component.literal(TEST_TRACK_LABELS[this.testTrackIndex]));
        }
        updateControls();
    }

    private void updateControls() {
        if (this.playPauseButton == null || this.stopButton == null) {
            return;
        }
        PlaybackState state = this.audioEngine.getState();
        this.playPauseButton.setMessage(playPauseIcon());
        this.playPauseButton.setTooltip(Tooltip.create(playPauseLabel()));
        this.playPauseButton.active = state != PlaybackState.BUFFERING && state != PlaybackState.RESOLVING;
        this.stopButton.active = state == PlaybackState.PLAYING
                || state == PlaybackState.PAUSED
                || state == PlaybackState.BUFFERING
                || state == PlaybackState.RESOLVING;
    }

    private Component playPauseIcon() {
        return switch (this.audioEngine.getState()) {
            case PLAYING -> Component.literal("⏸");
            case BUFFERING, RESOLVING -> Component.literal("…");
            default -> Component.literal("▶");
        };
    }

    private Component playPauseLabel() {
        return switch (this.audioEngine.getState()) {
            case PLAYING -> Component.translatable("button.cubic-cadence.pause");
            case PAUSED -> Component.translatable("button.cubic-cadence.resume");
            case BUFFERING, RESOLVING -> Component.translatable("button.cubic-cadence.loading");
            default -> Component.translatable("button.cubic-cadence.play");
        };
    }

    private Component stateMessage() {
        String suffix = switch (this.audioEngine.getState()) {
            case BUFFERING, RESOLVING -> "loading";
            case PLAYING -> "playing";
            case PAUSED -> "paused";
            case ENDED -> "ended";
            case ERROR -> "error";
            default -> "idle";
        };
        return Component.translatable("status.cubic-cadence." + suffix);
    }

    private static String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        return "%d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }

    private static final class ProgressSlider extends AbstractSliderButton {
        private static final Identifier BACKGROUND_SPRITE =
                Identifier.withDefaultNamespace("hud/experience_bar_background");
        private static final Identifier PROGRESS_SPRITE =
                Identifier.withDefaultNamespace("hud/experience_bar_progress");
        private static final int BAR_WIDTH = CONTROL_WIDTH;
        private static final int BAR_HEIGHT = 5;
        private static final int WIDGET_HEIGHT = 11;
        private static final int HANDLE_INSET = HANDLE_WIDTH / 2;
        private static final int TIME_GAP = 2;
        private static final int BUTTON_GAP = 6;

        private final AudioEngine audioEngine;
        private boolean dragging;

        private ProgressSlider(int x, int y, AudioEngine audioEngine) {
            super(x, y, BAR_WIDTH, WIDGET_HEIGHT, Component.empty(), 0.0);
            this.audioEngine = audioEngine;
            syncFromEngine();
        }

        @Override
        public void extractWidgetRenderState(
                GuiGraphicsExtractor extractor,
                int mouseX,
                int mouseY,
                float tickDelta
        ) {
            int barY = getY() + (getHeight() - BAR_HEIGHT) / 2;
            extractor.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    BACKGROUND_SPRITE,
                    getX(),
                    barY,
                    BAR_WIDTH,
                    BAR_HEIGHT
            );

            int filledWidth = (int) Math.round(this.value * BAR_WIDTH);
            if (filledWidth > 0) {
                extractor.enableScissor(getX(), barY, getX() + filledWidth, barY + BAR_HEIGHT);
                extractor.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        PROGRESS_SPRITE,
                        getX(),
                        barY,
                        BAR_WIDTH,
                        BAR_HEIGHT
                );
                extractor.disableScissor();
            }

            int handleCenterX = getX() + HANDLE_INSET
                    + (int) Math.round(this.value * (BAR_WIDTH - HANDLE_WIDTH));
            int handleTop = barY - 2;
            int handleColor = !this.active
                    ? 0xFF555555
                    : this.dragging || isHoveredOrFocused() ? 0xFF80FF20 : 0xFF55AA18;
            drawPixelHandle(extractor, handleCenterX, handleTop, handleColor);
            handleCursor(extractor);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.dragging = true;
            super.onClick(event, doubleClick);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            long targetPositionMs = positionFromValue();
            this.dragging = false;
            seekTo(targetPositionMs);
            syncFromEngine();
        }

        @Override
        protected void updateMessage() {
            long durationMs = this.audioEngine == null ? 0L : this.audioEngine.getDurationMs();
            long positionMs = this.dragging
                    ? Math.round(this.value * durationMs)
                    : this.audioEngine == null ? 0L : this.audioEngine.getPositionMs();
            setMessage(Component.translatable(
                    "slider.cubic-cadence.progress",
                    formatTime(positionMs),
                    formatTime(durationMs)
            ));
        }

        @Override
        protected void applyValue() {
            if (!this.dragging) {
                seekTo(positionFromValue());
            }
            updateMessage();
        }

        private Component visibleTimeMessage() {
            long durationMs = this.audioEngine.getDurationMs();
            long positionMs = this.dragging ? positionFromValue() : this.audioEngine.getPositionMs();
            return Component.literal(formatTime(positionMs) + " / " + formatTime(durationMs));
        }

        private long positionFromValue() {
            return Math.round(this.value * this.audioEngine.getDurationMs());
        }

        private void seekTo(long positionMs) {
            if (this.audioEngine.getDurationMs() > 0L) {
                this.audioEngine.seek(positionMs);
            }
        }

        private void syncFromEngine() {
            long durationMs = this.audioEngine.getDurationMs();
            PlaybackState state = this.audioEngine.getState();
            this.active = durationMs > 0L
                    && (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED);
            if (!this.dragging) {
                this.value = durationMs <= 0L
                        ? 0.0
                        : Math.max(0.0, Math.min(1.0, (double) this.audioEngine.getPositionMs() / durationMs));
            }
            updateMessage();
        }

        private static void drawPixelHandle(
                GuiGraphicsExtractor extractor,
                int centerX,
                int top,
                int color
        ) {
            extractor.fill(centerX - 1, top, centerX + 2, top + 1, 0xFF101010);
            extractor.fill(centerX - 2, top + 1, centerX + 3, top + 2, 0xFF101010);
            extractor.fill(centerX - 3, top + 2, centerX + 4, top + 7, 0xFF101010);
            extractor.fill(centerX - 2, top + 7, centerX + 3, top + 8, 0xFF101010);
            extractor.fill(centerX - 1, top + 8, centerX + 2, top + 9, 0xFF101010);

            extractor.fill(centerX - 1, top + 1, centerX + 2, top + 2, color);
            extractor.fill(centerX - 2, top + 2, centerX + 3, top + 7, color);
            extractor.fill(centerX - 1, top + 7, centerX + 2, top + 8, color);
        }
    }

    private static final class VolumeSlider extends AbstractSliderButton {
        private final AudioEngine audioEngine;

        private VolumeSlider(int x, int y, int width, int height, AudioEngine audioEngine) {
            super(x, y, width, height, Component.empty(), ModConfig.getInstance().getVolume());
            this.audioEngine = audioEngine;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    "slider.cubic-cadence.volume",
                    Math.round(this.value * 100.0)
            ));
        }

        @Override
        protected void applyValue() {
            float newVolume = (float) this.value;
            ModConfig.getInstance().setVolume(newVolume);
            this.audioEngine.setVolume(newVolume);
        }
    }

    private static final class VanillaMusicVolumeSlider extends AbstractSliderButton {
        private final OptionInstance<Double> option;
        private final java.util.function.DoubleConsumer onChanged;

        private VanillaMusicVolumeSlider(
                int x,
                int y,
                int width,
                int height,
                OptionInstance<Double> option,
                java.util.function.DoubleConsumer onChanged
        ) {
            super(x, y, width, height, Component.empty(), option.get());
            this.option = option;
            this.onChanged = onChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    "slider.cubic-cadence.vanilla_music_volume",
                    Math.round(this.value * 100.0)
            ));
        }

        @Override
        protected void applyValue() {
            this.option.set(this.value);
            this.onChanged.accept(this.value);
        }

        private void syncFromOption() {
            this.value = this.option.get();
            updateMessage();
        }
    }
}
