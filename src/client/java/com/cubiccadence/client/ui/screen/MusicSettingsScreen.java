package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.mixin.CheckboxAccessor;
import com.cubiccadence.client.playback.AudioEngine;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import com.cubiccadence.provider.AudioQuality;

/** Client-only settings reached from the music library gear button. */
public final class MusicSettingsScreen extends Screen {
    private static final int CONTROL_WIDTH = 240;
    private static final int CONTROL_GAP = 8;
    private static final double MIN_AUDIBLE_VOLUME = 0.0001;
    private static double lastNonZeroVanillaMusicVolume = 1.0;

    private final Screen parent;
    private final AudioEngine audioEngine;
    private VanillaMusicVolumeSlider vanillaMusicVolumeSlider;
    private Checkbox disableVanillaMusicCheckbox;
    private Button audioQualityButton;

    public MusicSettingsScreen(Screen parent) {
        super(Component.translatable("screen.cubic-cadence.settings"));
        this.parent = parent;
        this.audioEngine = CubicCadenceClient.getAudioEngine();
    }

    @Override
    protected void init() {
        int left = (this.width - CONTROL_WIDTH) / 2;
        int top = Math.max(46, this.height / 2 - 55);
        double vanillaMusicVolume = getVanillaMusicVolume();
        if (vanillaMusicVolume > MIN_AUDIBLE_VOLUME) {
            lastNonZeroVanillaMusicVolume = vanillaMusicVolume;
        }

        this.addRenderableWidget(new CubicCadenceVolumeSlider(
                left,
                top,
                CONTROL_WIDTH,
                Button.DEFAULT_HEIGHT,
                this.audioEngine
        ));
        this.vanillaMusicVolumeSlider = this.addRenderableWidget(new VanillaMusicVolumeSlider(
                left,
                top + Button.DEFAULT_HEIGHT + CONTROL_GAP,
                CONTROL_WIDTH,
                Button.DEFAULT_HEIGHT,
                getVanillaMusicOption(),
                this::handleVanillaMusicSliderChange
        ));
        this.vanillaMusicVolumeSlider.active = vanillaMusicVolume > MIN_AUDIBLE_VOLUME;
        this.disableVanillaMusicCheckbox = this.addRenderableWidget(
                Checkbox.builder(
                                Component.translatable("checkbox.cubic-cadence.disable_vanilla_music"),
                                this.font
                        )
                        .pos(left, top + (Button.DEFAULT_HEIGHT + CONTROL_GAP) * 2)
                        .maxWidth(CONTROL_WIDTH)
                        .selected(vanillaMusicVolume <= MIN_AUDIBLE_VOLUME)
                        .onValueChange((checkbox, selected) -> handleVanillaMusicToggle(selected))
                        .build()
        );
        this.audioQualityButton = this.addRenderableWidget(
                Button.builder(audioQualityMessage(), button -> cycleAudioQuality())
                        .bounds(
                                left,
                                top + (Button.DEFAULT_HEIGHT + CONTROL_GAP) * 3,
                                CONTROL_WIDTH,
                                Button.DEFAULT_HEIGHT
                        )
                        .build()
        );
        this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.done"), button -> onClose())
                        .bounds(
                                left,
                                top + (Button.DEFAULT_HEIGHT + CONTROL_GAP) * 4,
                                CONTROL_WIDTH,
                                Button.DEFAULT_HEIGHT
                        )
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
        this.minecraft.options.save();
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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

    private void cycleAudioQuality() {
        AudioQuality current = ModConfig.getInstance().getAudioQuality();
        AudioQuality next = switch (current) {
            case LOW -> AudioQuality.STANDARD;
            case STANDARD -> AudioQuality.HIGH;
            case HIGH, LOSSLESS -> AudioQuality.LOW;
        };
        ModConfig.getInstance().setAudioQuality(next);
        this.audioQualityButton.setMessage(audioQualityMessage());
    }

    private Component audioQualityMessage() {
        AudioQuality quality = ModConfig.getInstance().getAudioQuality();
        String suffix = quality.name().toLowerCase();
        return Component.translatable("setting.cubic-cadence.audio_quality", Component.translatable(
                "audio_quality.cubic-cadence." + suffix
        ));
    }

    private static final class CubicCadenceVolumeSlider extends AbstractSliderButton {
        private final AudioEngine audioEngine;

        private CubicCadenceVolumeSlider(int x, int y, int width, int height, AudioEngine audioEngine) {
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
