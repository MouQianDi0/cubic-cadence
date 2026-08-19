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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

public class MusicLibraryScreen extends Screen {
    private static final int CONTROL_WIDTH = 220;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_WIDTH = (CONTROL_WIDTH - BUTTON_GAP) / 2;
    private static final double MIN_AUDIBLE_VOLUME = 0.0001;
    private static double lastNonZeroVanillaMusicVolume = 1.0;

    private final AudioEngine audioEngine;
    private Button playPauseButton;
    private Button stopButton;
    private Checkbox disableVanillaMusicCheckbox;
    private VanillaMusicVolumeSlider vanillaMusicVolumeSlider;

    public MusicLibraryScreen() {
        super(Component.literal("Cubic Cadence"));
        this.audioEngine = CubicCadenceClient.getAudioEngine();
    }

    @Override
    protected void init() {
        int left = (this.width - CONTROL_WIDTH) / 2;
        int controlsTop = Math.max(105, this.height / 2 - 30);
        double vanillaMusicVolume = getVanillaMusicVolume();
        if (vanillaMusicVolume > MIN_AUDIBLE_VOLUME) {
            lastNonZeroVanillaMusicVolume = vanillaMusicVolume;
        }

        this.playPauseButton = this.addRenderableWidget(
                Button.builder(playPauseMessage(), button -> handlePlayPause())
                        .bounds(left, controlsTop, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.stopButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.stop"), button -> this.audioEngine.stop())
                        .bounds(left + BUTTON_WIDTH + BUTTON_GAP, controlsTop, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
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
        extractor.centeredText(this.font, stateMessage(), this.width / 2, 66, 0xFFBDBDBD);
        extractor.centeredText(
                this.font,
                Component.literal(formatTime(this.audioEngine.getPositionMs()) + " / "
                        + formatTime(this.audioEngine.getDurationMs())),
                this.width / 2,
                82,
                0xFFBDBDBD
        );
        if (this.audioEngine.getState() == PlaybackState.ERROR && this.audioEngine.getLastError() != null) {
            extractor.centeredText(
                    this.font,
                    Component.translatable("status.cubic-cadence.error"),
                    this.width / 2,
                    96,
                    0xFFFF6B6B
            );
        }
    }

    @Override
    public void tick() {
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
            default -> this.audioEngine.playLocal(CubicCadenceClient.LOCAL_TEST_AUDIO);
        }
        updateControls();
    }

    private void updateControls() {
        if (this.playPauseButton == null || this.stopButton == null) {
            return;
        }
        PlaybackState state = this.audioEngine.getState();
        this.playPauseButton.setMessage(playPauseMessage());
        this.playPauseButton.active = state != PlaybackState.BUFFERING && state != PlaybackState.RESOLVING;
        this.stopButton.active = state == PlaybackState.PLAYING
                || state == PlaybackState.PAUSED
                || state == PlaybackState.BUFFERING
                || state == PlaybackState.RESOLVING;
    }

    private Component playPauseMessage() {
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
