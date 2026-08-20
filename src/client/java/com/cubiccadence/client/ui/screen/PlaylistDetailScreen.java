package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.library.PlaylistDetailManager;
import com.cubiccadence.client.playback.PlayerController;
import com.cubiccadence.client.playback.PlaybackQueue;
import com.cubiccadence.client.ui.texture.RemoteTextureCache;
import com.cubiccadence.model.Artist;
import com.cubiccadence.model.Availability;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.PlaybackMode;
import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.Track;
import com.cubiccadence.provider.PlaylistPage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** On-demand playlist metadata and online playback view. */
public final class PlaylistDetailScreen extends Screen {
    private static final int HEADER_HEIGHT = 82;
    private static final int FOOTER_HEIGHT = 84;
    private static final int HEADER_COVER_SIZE = 56;
    private static final int ROW_HEIGHT = 42;
    private static final int TRACK_COVER_SIZE = 32;

    private final Screen parent;
    private final PlaylistSummary playlist;
    private final PlaylistDetailManager detailManager;
    private final RemoteTextureCache textureCache;
    private final PlayerController playerController;
    private TrackList trackList;
    private PlaylistPage displayedPage;
    private Button previousButton;
    private Button nextButton;
    private Button retryButton;
    private Button previousTrackButton;
    private Button playPauseButton;
    private Button nextTrackButton;
    private Button playbackModeButton;

    public PlaylistDetailScreen(Screen parent, PlaylistSummary playlist) {
        super(Component.translatable("screen.cubic-cadence.playlist_detail"));
        this.parent = parent;
        this.playlist = playlist;
        this.detailManager = CubicCadenceClient.getPlaylistDetailManager();
        this.textureCache = CubicCadenceClient.getRemoteTextureCache();
        this.playerController = CubicCadenceClient.getPlayerController();
        this.detailManager.open(playlist);
    }

    @Override
    protected void init() {
        int listTop = HEADER_HEIGHT;
        int listHeight = Math.max(40, this.height - HEADER_HEIGHT - FOOTER_HEIGHT);
        this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.back"), button -> onClose())
                        .bounds(Math.max(10, this.width - 82), 18, 70, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.trackList = this.addRenderableWidget(new TrackList(
                this.width - 24,
                listHeight,
                12,
                listTop
        ));
        int playbackY = this.height - 53;
        this.previousTrackButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.previous_track"), button ->
                                this.playerController.previous())
                        .bounds(this.width / 2 - 146, playbackY, 54, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.playPauseButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.play"), button -> togglePlayback())
                        .bounds(this.width / 2 - 88, playbackY, 54, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.nextTrackButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.next_track"), button ->
                                this.playerController.next())
                        .bounds(this.width / 2 - 30, playbackY, 54, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.playbackModeButton = this.addRenderableWidget(
                Button.builder(modeMessage(), button -> cyclePlaybackMode())
                        .bounds(this.width / 2 + 28, playbackY, 118, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.previousButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.previous_page"), button ->
                                this.detailManager.loadPage(this.detailManager.getCurrentPage() - 1))
                        .bounds(this.width / 2 - 112, this.height - 27, 72, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.nextButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.next_page"), button ->
                                this.detailManager.loadPage(this.detailManager.getCurrentPage() + 1))
                        .bounds(this.width / 2 + 40, this.height - 27, 72, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.retryButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.retry"), button ->
                                this.detailManager.retry())
                        .bounds(this.width / 2 - 36, this.height - 27, 72, Button.DEFAULT_HEIGHT)
                        .build()
        );
        syncPage();
        updateControls();
        syncTextureRetention();
    }

    @Override
    public void tick() {
        syncPage();
        updateControls();
        syncTextureRetention();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        renderHeader(extractor);
        renderStateMessage(extractor);
        renderNowPlaying(extractor);
        if (this.detailManager.getLoadState() != PlaylistDetailManager.LoadState.ERROR) {
            extractor.centeredText(
                    this.font,
                    Component.translatable(
                            "label.cubic-cadence.page_number",
                            this.detailManager.getCurrentPage() + 1
                    ),
                    this.width / 2,
                    this.height - 21,
                    0xFFE0E0E0
            );
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CubicCadenceClient.openLibraryKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.textureCache.clear();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderHeader(GuiGraphicsExtractor extractor) {
        extractor.fill(8, 8, this.width - 8, HEADER_HEIGHT - 6, 0xB0101010);
        renderRemoteImage(extractor, playlist.coverUrl(), 16, 16, HEADER_COVER_SIZE);
        int textX = 16 + HEADER_COVER_SIZE + 10;
        int availableWidth = Math.max(40, this.width - textX - 92);
        extractor.text(this.font, fit(playlist.name(), availableWidth), textX, 19, 0xFFFFFFFF);
        extractor.text(
                this.font,
                Component.translatable("playlist.cubic-cadence.track_count", playlist.trackCount()),
                textX,
                37,
                0xFFBDBDBD
        );
        extractor.text(this.font, ownershipMessage(playlist.ownership()), textX, 54, 0xFFBDBDBD);
        extractor.text(
                this.font,
                Component.translatable("playlist.cubic-cadence.detail_read_only"),
                Math.max(textX, this.width - 150),
                54,
                0xFF8A8A8A
        );
    }

    private void renderStateMessage(GuiGraphicsExtractor extractor) {
        PlaylistDetailManager.LoadState state = this.detailManager.getLoadState();
        Component message = switch (state) {
            case LOADING -> Component.translatable("playlist.cubic-cadence.loading_tracks");
            case ERROR -> Component.translatable("playlist.cubic-cadence.tracks_failed");
            case READY -> this.displayedPage != null && this.displayedPage.tracks().isEmpty()
                    ? Component.translatable("playlist.cubic-cadence.empty_tracks")
                    : Component.empty();
            case IDLE -> Component.translatable("playlist.cubic-cadence.session_unavailable");
        };
        if (!message.getString().isEmpty()) {
            extractor.centeredText(
                    this.font,
                    message,
                    this.width / 2,
                    HEADER_HEIGHT + Math.max(8, (this.height - HEADER_HEIGHT - FOOTER_HEIGHT) / 2),
                    state == PlaylistDetailManager.LoadState.ERROR ? 0xFFFF6B6B : 0xFFBDBDBD
            );
        }
    }

    private void syncPage() {
        PlaylistPage page = this.detailManager.getTrackPage().orElse(null);
        if (page == this.displayedPage || this.trackList == null) {
            return;
        }
        this.displayedPage = page;
        this.trackList.setTracks(page == null ? List.of() : page.tracks());
    }

    private void updateControls() {
        if (this.previousButton == null || this.nextButton == null || this.retryButton == null) {
            return;
        }
        boolean failed = this.detailManager.getLoadState() == PlaylistDetailManager.LoadState.ERROR;
        this.previousButton.visible = !failed;
        this.previousButton.active = this.detailManager.hasPreviousPage();
        this.nextButton.visible = !failed;
        this.nextButton.active = this.detailManager.hasNextPage();
        this.retryButton.visible = failed;
        this.retryButton.active = failed;
        Track current = this.playerController.getCurrentTrack();
        boolean hasCurrent = current != null;
        this.previousTrackButton.active = hasCurrent;
        PlaybackState playbackState = this.playerController.getState();
        this.playPauseButton.active = hasCurrent
                && (playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.PAUSED);
        this.nextTrackButton.active = hasCurrent;
        this.playPauseButton.setMessage(Component.translatable(
                playbackState == PlaybackState.PLAYING
                        ? "button.cubic-cadence.pause"
                        : "button.cubic-cadence.play"
        ));
        this.playbackModeButton.setMessage(modeMessage());
    }

    private void playTrack(Track track) {
        if (this.displayedPage == null || !PlaybackQueue.canAttempt(track)) {
            return;
        }
        int selectedIndex = this.displayedPage.tracks().indexOf(track);
        this.playerController.playQueue(this.displayedPage.tracks(), Math.max(0, selectedIndex));
    }

    private void togglePlayback() {
        PlaybackState state = this.playerController.getState();
        if (state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
                || state == PlaybackState.RESOLVING) {
            this.playerController.pause();
        } else if (state == PlaybackState.PAUSED) {
            this.playerController.resume();
        }
    }

    private void cyclePlaybackMode() {
        PlaybackMode[] modes = PlaybackMode.values();
        PlaybackMode current = this.playerController.getPlaybackMode();
        this.playerController.setPlaybackMode(modes[(current.ordinal() + 1) % modes.length]);
    }

    private Component modeMessage() {
        String suffix = this.playerController.getPlaybackMode().name().toLowerCase();
        return Component.translatable("playback_mode.cubic-cadence." + suffix);
    }

    private void renderNowPlaying(GuiGraphicsExtractor extractor) {
        Track track = this.playerController.getCurrentTrack();
        if (track == null) {
            return;
        }
        int coverY = this.height - 80;
        renderRemoteImage(extractor, track.coverUrl(), 12, coverY, 36);
        String status = playbackStatusMessage().getString();
        int textX = 56;
        int textWidth = Math.max(70, this.width / 2 - textX - 150);
        extractor.text(this.font, fit(track.title(), textWidth), textX, coverY, 0xFFFFFFFF);
        String secondary = Component.translatable(
                "playlist.cubic-cadence.track_secondary",
                artists(track),
                track.albumName().isBlank() ? "-" : track.albumName()
        ).getString();
        extractor.text(this.font, fit(secondary, textWidth), textX, coverY + 13, 0xFFBDBDBD);
        String progress = "%s / %s · %s".formatted(
                formatDuration(this.playerController.getPositionMs()),
                formatDuration(this.playerController.getDurationMs()),
                status
        );
        extractor.text(this.font, fit(progress, textWidth), textX, coverY + 26, 0xFF9ED7A8);
    }

    private Component playbackStatusMessage() {
        if (this.playerController.isTrial()) {
            return Component.translatable("playback.cubic-cadence.trial");
        }
        if (this.playerController.getState() == PlaybackState.ERROR) {
            return this.playerController.getLastError()
                    .map(Component::translatable)
                    .orElseGet(() -> Component.translatable("playback_state.cubic-cadence.error"));
        }
        String suffix = this.playerController.getState().name().toLowerCase();
        return Component.translatable("playback_state.cubic-cadence." + suffix);
    }

    private void syncTextureRetention() {
        List<String> urls = new ArrayList<>();
        urls.add(this.playlist.coverUrl());
        if (this.displayedPage != null) {
            this.displayedPage.tracks().stream().map(Track::coverUrl).forEach(urls::add);
        }
        Track current = this.playerController.getCurrentTrack();
        if (current != null) {
            urls.add(current.coverUrl());
        }
        this.textureCache.retainOnly(urls);
    }

    private void renderRemoteImage(GuiGraphicsExtractor extractor, String url, int x, int y, int size) {
        extractor.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF555555);
        this.textureCache.getOrRequest(url).ifPresentOrElse(
                identifier -> extractor.blit(identifier, x, y, x + size, y + size, 0f, 1f, 0f, 1f),
                () -> {
                    extractor.fill(x, y, x + size, y + size, 0xFF252525);
                    extractor.centeredText(
                            this.font,
                            "♪",
                            x + size / 2,
                            y + (size - this.font.lineHeight) / 2,
                            0xFF8A8A8A
                    );
                }
        );
    }

    private Component ownershipMessage(PlaylistOwnership ownership) {
        String suffix = switch (ownership) {
            case CREATED -> "created";
            case COLLECTED -> "collected";
            case SPECIAL -> "favorite";
        };
        return Component.translatable("playlist.cubic-cadence.ownership." + suffix);
    }

    private Component availabilityMessage(Availability availability) {
        String suffix = switch (availability) {
            case PLAYABLE -> "playable";
            case UNAVAILABLE -> "unavailable";
            case COPYRIGHT_RESTRICTED -> "copyright_restricted";
            case MEMBERSHIP_REQUIRED -> "membership_required";
            case REGION_RESTRICTED -> "region_restricted";
            case QUALITY_UNAVAILABLE -> "quality_unavailable";
            case UNKNOWN -> "unknown";
        };
        return Component.translatable("availability.cubic-cadence." + suffix);
    }

    private static int availabilityColor(Availability availability) {
        return switch (availability) {
            case PLAYABLE -> 0xFF72D572;
            case MEMBERSHIP_REQUIRED -> 0xFFFFC857;
            case UNKNOWN -> 0xFFBDBDBD;
            default -> 0xFFFF6B6B;
        };
    }

    private Component fit(String value, int width) {
        if (this.font.width(value) <= width) {
            return Component.literal(value);
        }
        int ellipsisWidth = this.font.width("…");
        return Component.literal(this.font.plainSubstrByWidth(value, Math.max(0, width - ellipsisWidth)) + "…");
    }

    private static String artists(Track track) {
        String value = track.artists().stream().map(Artist::name).collect(Collectors.joining(" / "));
        return value.isBlank() ? "-" : value;
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return "%d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }

    private final class TrackList extends ObjectSelectionList<TrackEntry> {
        private TrackList(int width, int height, int x, int y) {
            super(PlaylistDetailScreen.this.minecraft, width, height, y, ROW_HEIGHT);
            updateSizeAndPosition(width, height, x, y);
            this.centerListVertically = false;
        }

        private void setTracks(List<Track> tracks) {
            clearEntries();
            tracks.stream().map(TrackEntry::new).forEach(this::addEntry);
            setScrollAmount(0.0);
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, getWidth() - 16);
        }
    }

    private final class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
        private final Track track;

        private TrackEntry(Track track) {
            this.track = track;
        }

        @Override
        public void extractContent(
                GuiGraphicsExtractor extractor,
                int mouseX,
                int mouseY,
                boolean hovered,
                float tickDelta
        ) {
            int x = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            extractor.fill(x, y, x + width, y + getContentHeight(), hovered ? 0x90353535 : 0x70202020);
            if (track.equals(playerController.getCurrentTrack())) {
                extractor.fill(x, y, x + 3, y + getContentHeight(), 0xFF55DD88);
            }
            renderRemoteImage(extractor, track.coverUrl(), x + 4, y + 4, TRACK_COVER_SIZE);

            int textX = x + TRACK_COVER_SIZE + 12;
            int statusWidth = Math.min(112, Math.max(72, width / 5));
            int durationWidth = 42;
            int textWidth = Math.max(30, width - (textX - x) - statusWidth - durationWidth - 16);
            extractor.text(PlaylistDetailScreen.this.font, fit(track.title(), textWidth), textX, y + 7, 0xFFFFFFFF);
            String secondary = Component.translatable(
                    "playlist.cubic-cadence.track_secondary",
                    artists(track),
                    track.albumName().isBlank() ? "-" : track.albumName()
            ).getString();
            extractor.text(
                    PlaylistDetailScreen.this.font,
                    fit(secondary, textWidth),
                    textX,
                    y + 23,
                    0xFFBDBDBD
            );
            extractor.text(
                    PlaylistDetailScreen.this.font,
                    Component.literal(formatDuration(track.durationMs())),
                    x + width - statusWidth - durationWidth - 8,
                    y + 15,
                    0xFFBDBDBD
            );
            Component availability = availabilityMessage(track.availability());
            extractor.text(
                    PlaylistDetailScreen.this.font,
                    fit(availability.getString(), statusWidth),
                    x + width - statusWidth - 4,
                    y + 15,
                    availabilityColor(track.availability())
            );
        }

        @Override
        public Component getNarration() {
            return Component.translatable(
                    "narration.cubic-cadence.track",
                    track.title(),
                    artists(track),
                    track.albumName(),
                    formatDuration(track.durationMs()),
                    availabilityMessage(track.availability())
            );
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0) {
                playTrack(track);
                return true;
            }
            return false;
        }
    }
}
