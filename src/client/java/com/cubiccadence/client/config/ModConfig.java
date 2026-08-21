package com.cubiccadence.client.config;

import com.cubiccadence.provider.AudioQuality;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists Cubic Cadence client options to {@code config/cubic-cadence.json}
 * inside Fabric's config directory. Options survive both closing and reopening
 * the music screen and fully restarting the game.
 */
public class ModConfig {
    private static final String CONFIG_FILE_NAME = "cubic-cadence.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ModConfig INSTANCE;

    private float volume = 1.0f;
    private boolean hudEnabled = true;
    private boolean hudShowCover = true;
    private boolean hudShowTitle = true;
    private boolean hudShowArtist = true;
    private boolean hudShowProgress = true;
    private boolean hudShowLyrics = true;
    private AudioQuality audioQuality = AudioQuality.STANDARD;
    private int lastTestTrackIndex;
    private String apiEnhancedBaseUrl = "https://cub.cubiccadence.top/";

    private ModConfig() {
    }

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        save();
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
        save();
    }

    public boolean isHudShowCover() {
        return hudShowCover;
    }

    public void setHudShowCover(boolean hudShowCover) {
        this.hudShowCover = hudShowCover;
        save();
    }

    public boolean isHudShowTitle() {
        return hudShowTitle;
    }

    public void setHudShowTitle(boolean hudShowTitle) {
        this.hudShowTitle = hudShowTitle;
        save();
    }

    public boolean isHudShowArtist() {
        return hudShowArtist;
    }

    public void setHudShowArtist(boolean hudShowArtist) {
        this.hudShowArtist = hudShowArtist;
        save();
    }

    public boolean isHudShowProgress() {
        return hudShowProgress;
    }

    public void setHudShowProgress(boolean hudShowProgress) {
        this.hudShowProgress = hudShowProgress;
        save();
    }

    public boolean isHudShowLyrics() {
        return hudShowLyrics;
    }

    public void setHudShowLyrics(boolean hudShowLyrics) {
        this.hudShowLyrics = hudShowLyrics;
        save();
    }

    public AudioQuality getAudioQuality() {
        return audioQuality;
    }

    public void setAudioQuality(AudioQuality quality) {
        this.audioQuality = quality == null ? AudioQuality.STANDARD : quality;
        save();
    }

    public int getLastTestTrackIndex() {
        return lastTestTrackIndex;
    }

    public void setLastTestTrackIndex(int index) {
        this.lastTestTrackIndex = Math.max(0, index);
        save();
    }

    public String getApiEnhancedBaseUrl() {
        return apiEnhancedBaseUrl == null ? "" : apiEnhancedBaseUrl.trim();
    }

    public void setApiEnhancedBaseUrl(String apiEnhancedBaseUrl) {
        this.apiEnhancedBaseUrl = apiEnhancedBaseUrl == null ? "" : apiEnhancedBaseUrl.trim();
        save();
    }

    public void load() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (root.has("volume")) {
                this.volume = Math.max(0.0f, Math.min(1.0f, root.get("volume").getAsFloat()));
            }
            if (root.has("hudEnabled")) {
                this.hudEnabled = root.get("hudEnabled").getAsBoolean();
            }
            if (root.has("hudShowCover")) {
                this.hudShowCover = root.get("hudShowCover").getAsBoolean();
            }
            if (root.has("hudShowTitle")) {
                this.hudShowTitle = root.get("hudShowTitle").getAsBoolean();
            }
            if (root.has("hudShowArtist")) {
                this.hudShowArtist = root.get("hudShowArtist").getAsBoolean();
            }
            if (root.has("hudShowProgress")) {
                this.hudShowProgress = root.get("hudShowProgress").getAsBoolean();
            }
            if (root.has("hudShowLyrics")) {
                this.hudShowLyrics = root.get("hudShowLyrics").getAsBoolean();
            }
            if (root.has("audioQuality")) {
                this.audioQuality = parseAudioQuality(root.get("audioQuality").getAsString());
            }
            if (root.has("lastTestTrackIndex")) {
                this.lastTestTrackIndex = Math.max(0, root.get("lastTestTrackIndex").getAsInt());
            }
            if (root.has("apiEnhancedBaseUrl")) {
                this.apiEnhancedBaseUrl = root.get("apiEnhancedBaseUrl").getAsString().trim();
            }
        } catch (Exception ignored) {
            // A missing or malformed config should never prevent the game from starting.
        }
    }

    public void save() {
        Path file = configPath();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort; configuration is not critical to playback.
        }
    }

    private static AudioQuality parseAudioQuality(String value) {
        try {
            return AudioQuality.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AudioQuality.STANDARD;
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }
}
