package com.cubiccadence.client.config;

import com.cubiccadence.provider.AudioQuality;

public class ModConfig {
    private static ModConfig INSTANCE;

    private float volume = 1.0f;
    private boolean hudEnabled = true;
    private AudioQuality audioQuality = AudioQuality.STANDARD;

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
        }
        return INSTANCE;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
    }

    public AudioQuality getAudioQuality() {
        return audioQuality;
    }

    public void setAudioQuality(AudioQuality quality) {
        this.audioQuality = quality;
    }

    public void load() {
        // TODO: read config/cubic-cadence/config.json
    }

    public void save() {
        // TODO: write config/cubic-cadence/config.json
    }
}
