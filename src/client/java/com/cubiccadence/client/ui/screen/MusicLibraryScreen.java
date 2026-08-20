package com.cubiccadence.client.ui.screen;

import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.auth.AuthManager;
import com.cubiccadence.client.config.ModConfig;
import com.cubiccadence.client.library.MusicLibraryManager;
import com.cubiccadence.client.playback.AudioEngine;
import com.cubiccadence.client.playback.PlayerController;
import com.cubiccadence.client.ui.texture.RemoteTextureCache;
import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.PlaybackState;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.auth.AuthState;
import com.cubiccadence.auth.AuthorizationStatus;
import com.cubiccadence.provider.PlaylistSummaryPage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MusicLibraryScreen extends Screen {
    private static final int CONTROL_WIDTH = 220;
    private static final int BUTTON_GAP = 8;
    private static final int MEDIA_BUTTON_WIDTH = 40;
    private static final int FORMAT_BUTTON_WIDTH = 72;
    private static final int MEDIA_CONTROLS_WIDTH = MEDIA_BUTTON_WIDTH * 2 + FORMAT_BUTTON_WIDTH + BUTTON_GAP * 2;
    private static final int ACCOUNT_TOP = 8;
    private static final int ACCOUNT_HEIGHT = 42;
    private static final int AVATAR_SIZE = 30;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 2;
    private static final int GRID_GAP = 12;
    private static final int ROW_GAP = 12;
    private static final int MAX_COVER_SIZE = 176;
    private static final int PAGE_GAP = 8;
    private static final int LIBRARY_SIDE_MARGIN = 20;
    private static final DateTimeFormatter SYNC_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Identifier[] TEST_TRACKS = {
            CubicCadenceClient.LOCAL_TEST_AUDIO,
            CubicCadenceClient.LOCAL_TEST_AUDIO_MP3
    };
    private static final String[] TEST_TRACK_LABELS = {"WAV", "MP3"};

    private final AudioEngine audioEngine;
    private final PlayerController playerController;
    private final AuthManager authManager;
    private final MusicLibraryManager libraryManager;
    private final RemoteTextureCache textureCache;
    private Button authButton;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button retryButton;
    private Button refreshButton;
    private Button playPauseButton;
    private Button stopButton;
    private Button formatButton;
    private Button settingsButton;
    private ProgressSlider progressSlider;
    private int testTrackIndex;

    public MusicLibraryScreen() {
        super(Component.literal("Cubic Cadence"));
        this.audioEngine = CubicCadenceClient.getAudioEngine();
        this.playerController = CubicCadenceClient.getPlayerController();
        this.authManager = CubicCadenceClient.getAuthManager();
        this.libraryManager = CubicCadenceClient.getLibraryManager();
        this.textureCache = CubicCadenceClient.getRemoteTextureCache();
        this.testTrackIndex = ModConfig.getInstance().getLastTestTrackIndex() % TEST_TRACKS.length;
    }

    @Override
    protected void init() {
        int controlsTop = controlsTop();
        int mediaLeft = (this.width - MEDIA_CONTROLS_WIDTH) / 2;
        LibraryLayout layout = libraryLayout();

        this.authButton = this.addRenderableWidget(
                Button.builder(authButtonMessage(), button -> handleAuthAction())
                        .bounds(Math.max(10, this.width - 80), 18, 70, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.refreshButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.refresh_library"), button -> {
                            this.libraryManager.refresh();
                            updateLibraryControls();
                        })
                        .bounds(Math.max(82, this.width - 76), ACCOUNT_TOP + ACCOUNT_HEIGHT + 3, 66, Button.DEFAULT_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("tooltip.cubic-cadence.refresh_library")))
                        .build()
        );
        this.previousPageButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.previous_page"), button -> {
                            this.libraryManager.loadPage(this.libraryManager.getCurrentPage() - 1);
                            updateLibraryControls();
                        })
                        .bounds(this.width / 2 - 110, layout.pageY(), 70, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.nextPageButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.next_page"), button -> {
                            this.libraryManager.loadPage(this.libraryManager.getCurrentPage() + 1);
                            updateLibraryControls();
                        })
                        .bounds(this.width / 2 + 40, layout.pageY(), 70, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.retryButton = this.addRenderableWidget(
                Button.builder(Component.translatable("button.cubic-cadence.retry"), button -> {
                            this.libraryManager.retry();
                            updateLibraryControls();
                        })
                        .bounds(this.width / 2 - 35, layout.pageY(), 70, Button.DEFAULT_HEIGHT)
                        .build()
        );

        this.progressSlider = this.addRenderableWidget(new ProgressSlider(
                (this.width - ProgressSlider.BAR_WIDTH) / 2,
                controlsTop,
                this.audioEngine
        ));
        this.playPauseButton = this.addRenderableWidget(
                Button.builder(playPauseIcon(), button -> handlePlayPause())
                        .bounds(mediaLeft, controlsTop + 20, MEDIA_BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
                        .tooltip(Tooltip.create(playPauseLabel()))
                        .build()
        );
        this.stopButton = this.addRenderableWidget(
                Button.builder(Component.literal("■"), button -> stopPlayback())
                        .bounds(
                                mediaLeft + MEDIA_BUTTON_WIDTH + BUTTON_GAP,
                                controlsTop + 20,
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
                                controlsTop + 20,
                                FORMAT_BUTTON_WIDTH,
                                Button.DEFAULT_HEIGHT
                        )
                        .tooltip(Tooltip.create(Component.literal("Local test audio format")))
                        .build()
        );
        this.settingsButton = this.addRenderableWidget(
                Button.builder(Component.literal("⚙"), button ->
                                this.minecraft.setScreenAndShow(new MusicSettingsScreen(this)))
                        .bounds(this.width - 30, this.height - 30, 20, Button.DEFAULT_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("button.cubic-cadence.settings")))
                        .build()
        );
        updateControls();
        updateAuthControls();
        updateLibraryControls();
        syncRemoteTextureRetention();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        renderAccountBar(extractor);
        renderLibrary(extractor, mouseX, mouseY);
        extractor.centeredText(
                this.font,
                stateMessage(),
                this.width / 2,
                controlsTop() - 10,
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
        updateAuthControls();
        updateLibraryControls();
        syncRemoteTextureRetention();
    }

    private void renderAccountBar(GuiGraphicsExtractor extractor) {
        int right = Math.max(118, this.width - 8);
        extractor.fill(8, ACCOUNT_TOP, right, ACCOUNT_TOP + ACCOUNT_HEIGHT, 0xB0101010);
        extractor.fill(8, ACCOUNT_TOP, right, ACCOUNT_TOP + 1, 0xFF5A5A5A);
        extractor.fill(8, ACCOUNT_TOP + ACCOUNT_HEIGHT - 1, right, ACCOUNT_TOP + ACCOUNT_HEIGHT, 0xFF303030);

        UserProfile profile = this.libraryManager.getProfile().orElse(null);
        if (profile == null) {
            extractor.text(
                    this.font,
                    accountStatusMessage(),
                    16,
                    ACCOUNT_TOP + (ACCOUNT_HEIGHT - this.font.lineHeight) / 2,
                    this.authManager.getState() == AuthState.ERROR ? 0xFFFF6B6B : 0xFFE0E0E0
            );
            return;
        }

        int avatarX = 14;
        int avatarY = ACCOUNT_TOP + (ACCOUNT_HEIGHT - AVATAR_SIZE) / 2;
        renderRemoteImage(extractor, profile.avatarUrl(), avatarX, avatarY, AVATAR_SIZE);
        int textX = avatarX + AVATAR_SIZE + 8;
        int availableWidth = Math.max(40, this.authButton.getX() - textX - 8);
        Component primary = Component.translatable(
                "label.cubic-cadence.account_primary",
                profile.displayName()
        );
        Component secondary = Component.translatable(
                "label.cubic-cadence.account_secondary",
                profile.userId(),
                levelMessage(profile.level()),
                membershipMessage(profile.membershipTier())
        );
        extractor.text(this.font, fit(primary, availableWidth), textX, ACCOUNT_TOP + 8, 0xFFFFFFFF);
        extractor.text(this.font, fit(secondary, availableWidth), textX, ACCOUNT_TOP + 23, 0xFFBDBDBD);
    }

    private void renderLibrary(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        LibraryLayout layout = libraryLayout();
        extractor.text(
                this.font,
                Component.translatable("label.cubic-cadence.my_playlists"),
                16,
                ACCOUNT_TOP + ACCOUNT_HEIGHT + 5,
                0xFFFFFFFF
        );
        renderLibrarySyncStatus(extractor);

        UserProfile profile = this.libraryManager.getProfile().orElse(null);
        if (profile == null) {
            Component message = this.authManager.getState() == AuthState.SIGNED_IN
                    ? Component.translatable("library.cubic-cadence.loading_profile")
                    : Component.translatable("library.cubic-cadence.sign_in_prompt");
            renderLibraryMessage(extractor, message, layout, 0xFFBDBDBD);
            return;
        }

        PlaylistSummaryPage page = this.libraryManager.getPlaylistPage().orElse(null);
        if (page == null) {
            if (this.libraryManager.getLoadState() == MusicLibraryManager.LoadState.ERROR) {
                renderLibraryMessage(
                        extractor,
                        Component.translatable("library.cubic-cadence.load_failed"),
                        layout,
                        0xFFFF6B6B
                );
            } else {
                renderPlaceholders(extractor, layout);
            }
            return;
        }

        if (page.items().isEmpty()) {
            renderLibraryMessage(
                    extractor,
                    Component.translatable("library.cubic-cadence.empty"),
                    layout,
                    0xFFBDBDBD
            );
        } else {
            renderPlaylistCards(extractor, page.items(), layout, mouseX, mouseY);
        }
        if (this.libraryManager.getLoadState() != MusicLibraryManager.LoadState.ERROR) {
            extractor.centeredText(
                    this.font,
                    Component.translatable(
                            "label.cubic-cadence.page_number",
                            this.libraryManager.getCurrentPage() + 1
                    ),
                    this.width / 2,
                    layout.pageY() + 6,
                    0xFFE0E0E0
            );
        }
    }

    private void renderPlaceholders(GuiGraphicsExtractor extractor, LibraryLayout layout) {
        for (int index = 0; index < MusicLibraryManager.PAGE_SIZE; index++) {
            int column = index % GRID_COLUMNS;
            int row = index / GRID_COLUMNS;
            int x = layout.left() + column * (layout.coverSize() + GRID_GAP);
            int y = layout.gridTop() + row * layout.rowHeight();
            extractor.fill(x, y, x + layout.coverSize(), y + layout.coverSize(), 0xFF252525);
            extractor.fill(x + 3, y + 3, x + layout.coverSize() - 3, y + layout.coverSize() - 3, 0xFF303030);
        }
        extractor.centeredText(
                this.font,
                Component.translatable("library.cubic-cadence.loading_playlists"),
                this.width / 2,
                layout.pageY() + 6,
                0xFFBDBDBD
        );
    }

    private void renderPlaylistCards(
            GuiGraphicsExtractor extractor,
            List<PlaylistSummary> playlists,
            LibraryLayout layout,
            int mouseX,
            int mouseY
    ) {
        int count = Math.min(playlists.size(), GRID_COLUMNS * GRID_ROWS);
        for (int index = 0; index < count; index++) {
            PlaylistSummary playlist = playlists.get(index);
            int column = index % GRID_COLUMNS;
            int row = index / GRID_COLUMNS;
            int x = layout.left() + column * (layout.coverSize() + GRID_GAP);
            int y = layout.gridTop() + row * layout.rowHeight();
            if (isInsidePlaylistCard(mouseX, mouseY, x, y, layout)) {
                extractor.fill(x - 2, y - 2, x + layout.coverSize() + 2, y + layout.coverSize() + 2, 0xFF80FF20);
            }
            renderRemoteImage(extractor, playlist.coverUrl(), x, y, layout.coverSize());
            extractor.centeredText(
                    this.font,
                    fit(playlist.name(), layout.coverSize()),
                    x + layout.coverSize() / 2,
                    y + layout.coverSize() + 3,
                    0xFFFFFFFF
            );
            extractor.centeredText(
                    this.font,
                    ownershipMessage(playlist.ownership()),
                    x + layout.coverSize() / 2,
                    y + layout.coverSize() + 4 + this.font.lineHeight,
                    0xFFBDBDBD
            );
        }
    }

    private void renderLibrarySyncStatus(GuiGraphicsExtractor extractor) {
        Component message = syncStatusMessage();
        if (message.getString().isEmpty()) {
            return;
        }
        int availableWidth = Math.max(40, this.refreshButton.getX() - 126);
        extractor.text(
                this.font,
                fit(message, availableWidth),
                118,
                ACCOUNT_TOP + ACCOUNT_HEIGHT + 9,
                this.libraryManager.hasRefreshWarning() ? 0xFFFFC857 : 0xFFBDBDBD
        );
    }

    private Component syncStatusMessage() {
        return switch (this.libraryManager.getLoadState()) {
            case LOADING_CACHE -> Component.translatable("library.cubic-cadence.loading_cache");
            case LOADING_PROFILE -> Component.translatable("library.cubic-cadence.loading_profile");
            case SYNCING_PLAYLISTS -> this.libraryManager.getSyncTotal()
                    .map(total -> Component.translatable(
                            "library.cubic-cadence.sync_progress_total",
                            this.libraryManager.getSyncLoadedCount(),
                            total
                    ))
                    .orElseGet(() -> Component.translatable(
                            "library.cubic-cadence.sync_progress",
                            this.libraryManager.getSyncLoadedCount()
                    ));
            case REFRESHING -> Component.translatable("library.cubic-cadence.background_refresh");
            case READY -> this.libraryManager.hasRefreshWarning()
                    ? Component.translatable("library.cubic-cadence.refresh_failed_cached")
                    : lastSyncMessage();
            default -> Component.empty();
        };
    }

    private Component lastSyncMessage() {
        long syncedAt = this.libraryManager.getSyncedAtEpochMillis();
        return syncedAt <= 0L
                ? Component.empty()
                : Component.translatable(
                        "library.cubic-cadence.last_sync",
                        SYNC_TIME_FORMAT.format(Instant.ofEpochMilli(syncedAt))
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

    private void renderRemoteImage(GuiGraphicsExtractor extractor, String url, int x, int y, int size) {
        extractor.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF555555);
        this.textureCache.getOrRequest(url).ifPresentOrElse(
                identifier -> extractor.blit(identifier, x, y, x + size, y + size, 0f, 1f, 0f, 1f),
                () -> {
                    extractor.fill(x, y, x + size, y + size, 0xFF252525);
                    extractor.centeredText(this.font, "♪", x + size / 2, y + (size - this.font.lineHeight) / 2, 0xFF8A8A8A);
                }
        );
    }

    private void renderLibraryMessage(
            GuiGraphicsExtractor extractor,
            Component message,
            LibraryLayout layout,
            int color
    ) {
        extractor.centeredText(
                this.font,
                message,
                this.width / 2,
                (layout.gridTop() + layout.gridBottom()) / 2,
                color
        );
    }

    private LibraryLayout libraryLayout() {
        int areaTop = ACCOUNT_TOP + ACCOUNT_HEIGHT + 18;
        int areaBottom = controlsTop() - 18;
        int coverFromWidth = Math.max(
                12,
                (Math.max(80, this.width - LIBRARY_SIDE_MARGIN * 2) - GRID_GAP * (GRID_COLUMNS - 1))
                        / GRID_COLUMNS
        );
        int rowLabelHeight = this.font.lineHeight * 2 + 6;
        int fixedHeight = ROW_GAP + rowLabelHeight * GRID_ROWS + PAGE_GAP + Button.DEFAULT_HEIGHT;
        int coverFromHeight = Math.max(
                12,
                (Math.max(36, areaBottom - areaTop) - fixedHeight) / GRID_ROWS
        );
        int coverSize = Math.min(MAX_COVER_SIZE, Math.min(coverFromWidth, coverFromHeight));
        int gridWidth = coverSize * GRID_COLUMNS + GRID_GAP * (GRID_COLUMNS - 1);
        int rowHeight = coverSize + rowLabelHeight + ROW_GAP;
        int gridHeight = rowHeight * GRID_ROWS - ROW_GAP;
        int totalHeight = gridHeight + PAGE_GAP + Button.DEFAULT_HEIGHT;
        int gridTop = areaTop + Math.max(0, (areaBottom - areaTop - totalHeight) / 2);
        return new LibraryLayout(
                (this.width - gridWidth) / 2,
                gridTop,
                coverSize,
                rowHeight,
                gridTop + gridHeight,
                gridTop + gridHeight + PAGE_GAP
        );
    }

    private Component accountStatusMessage() {
        if (this.authManager.getState() == AuthState.SIGNED_IN) {
            return this.libraryManager.getLoadState() == MusicLibraryManager.LoadState.ERROR
                    ? Component.translatable("library.cubic-cadence.profile_failed")
                    : Component.translatable("library.cubic-cadence.loading_profile");
        }
        return authStatusMessage();
    }

    private Component membershipMessage(MembershipTier membershipTier) {
        String suffix = switch (membershipTier) {
            case UNKNOWN -> "unknown";
            case MUSIC_PACKAGE -> "music_package";
            case BLACK_VINYL_VIP -> "black_vinyl_vip";
            case NON_MEMBER -> "non_member";
        };
        return Component.translatable("membership.cubic-cadence." + suffix);
    }

    private Component levelMessage(int level) {
        return level < 0
                ? Component.translatable("level.cubic-cadence.unknown")
                : Component.translatable("level.cubic-cadence.value", level);
    }

    private Component fit(Component component, int width) {
        return fit(component.getString(), width);
    }

    private Component fit(String value, int width) {
        if (this.font.width(value) <= width) {
            return Component.literal(value);
        }
        int ellipsisWidth = this.font.width("…");
        return Component.literal(this.font.plainSubstrByWidth(value, Math.max(0, width - ellipsisWidth)) + "…");
    }

    private int controlsTop() {
        return Math.max(104, this.height - 55);
    }

    private record LibraryLayout(
            int left,
            int gridTop,
            int coverSize,
            int rowHeight,
            int gridBottom,
            int pageY
    ) {
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        PlaylistSummary playlist = playlistAt(event.x(), event.y());
        if (playlist == null || this.minecraft == null) {
            return false;
        }
        this.minecraft.setScreenAndShow(new PlaylistDetailScreen(this, playlist));
        return true;
    }

    private PlaylistSummary playlistAt(double mouseX, double mouseY) {
        PlaylistSummaryPage page = this.libraryManager.getPlaylistPage().orElse(null);
        if (page == null) {
            return null;
        }
        LibraryLayout layout = libraryLayout();
        int count = Math.min(page.items().size(), GRID_COLUMNS * GRID_ROWS);
        for (int index = 0; index < count; index++) {
            int x = layout.left() + (index % GRID_COLUMNS) * (layout.coverSize() + GRID_GAP);
            int y = layout.gridTop() + (index / GRID_COLUMNS) * layout.rowHeight();
            if (isInsidePlaylistCard(mouseX, mouseY, x, y, layout)) {
                return page.items().get(index);
            }
        }
        return null;
    }

    private boolean isInsidePlaylistCard(double mouseX, double mouseY, int x, int y, LibraryLayout layout) {
        int cardHeight = layout.coverSize() + this.font.lineHeight * 2 + 6;
        return mouseX >= x && mouseX < x + layout.coverSize()
                && mouseY >= y && mouseY < y + cardHeight;
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
        this.textureCache.clear();
        super.onClose();
    }

    private void handleAuthAction() {
        AuthState authState = this.authManager.getState();
        if (authState == AuthState.SIGNED_IN) {
            openLogoutConfirmation();
            return;
        }
        if (authState == AuthState.REFRESHING) {
            return;
        }
        this.minecraft.setScreenAndShow(new LoginQrScreen());
        updateAuthControls();
    }

    private void openLogoutConfirmation() {
        this.minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        this.audioEngine.stop();
                        this.libraryManager.clearPrivateData();
                        CubicCadenceClient.getPlaylistDetailManager().clear();
                        this.textureCache.clear();
                        this.textureCache.clearDiskCache();
                        this.authManager.logout();
                    }
                    this.minecraft.setScreenAndShow(this);
                    updateAuthControls();
                },
                Component.translatable("confirm.cubic-cadence.logout_title"),
                Component.translatable("confirm.cubic-cadence.logout_message"),
                Component.translatable("confirm.cubic-cadence.logout_confirm"),
                Component.translatable("confirm.cubic-cadence.logout_cancel")
        ));
    }

    private void updateAuthControls() {
        if (this.authButton == null) {
            return;
        }
        AuthState authState = this.authManager.getState();
        this.authButton.setMessage(authButtonMessage());
        this.authButton.active = authState != AuthState.REFRESHING;
        this.authManager.getLastError().ifPresentOrElse(
                error -> this.authButton.setTooltip(Tooltip.create(Component.literal(error))),
                () -> this.authButton.setTooltip(null)
        );
    }

    private void updateLibraryControls() {
        if (this.previousPageButton == null || this.nextPageButton == null || this.retryButton == null
                || this.refreshButton == null) {
            return;
        }
        boolean signedInWithProfile = this.authManager.getState() == AuthState.SIGNED_IN
                && this.libraryManager.getProfile().isPresent();
        boolean failed = this.authManager.getState() == AuthState.SIGNED_IN
                && this.libraryManager.getLoadState() == MusicLibraryManager.LoadState.ERROR;
        this.previousPageButton.visible = signedInWithProfile && !failed;
        this.previousPageButton.active = this.libraryManager.hasPreviousPage();
        this.nextPageButton.visible = signedInWithProfile && !failed;
        this.nextPageButton.active = this.libraryManager.hasNextPage();
        this.retryButton.visible = failed;
        this.retryButton.active = failed;
        this.refreshButton.visible = this.authManager.getState() == AuthState.SIGNED_IN;
        this.refreshButton.active = this.refreshButton.visible && !this.libraryManager.isRefreshing();
    }

    private void syncRemoteTextureRetention() {
        List<String> urls = new ArrayList<>();
        this.libraryManager.getProfile().map(UserProfile::avatarUrl).ifPresent(urls::add);
        this.libraryManager.getPlaylistPage().ifPresent(page -> page.items().stream()
                .map(PlaylistSummary::coverUrl)
                .forEach(urls::add));
        this.textureCache.retainOnly(urls);
    }

    private Component authButtonMessage() {
        return switch (this.authManager.getState()) {
            case SIGNED_IN -> Component.translatable("button.cubic-cadence.logout");
            case AUTHORIZING -> Component.translatable("button.cubic-cadence.view_qr");
            case REFRESHING -> Component.translatable("button.cubic-cadence.refreshing");
            default -> Component.translatable("button.cubic-cadence.login");
        };
    }

    private Component authStatusMessage() {
        return switch (this.authManager.getState()) {
            case SIGNED_OUT -> Component.translatable("auth.cubic-cadence.signed_out");
            case AUTHORIZING -> this.authManager.getLastStatus() == AuthorizationStatus.SCANNED
                    ? Component.translatable("auth.cubic-cadence.scanned")
                    : Component.translatable("auth.cubic-cadence.authorizing_home");
            case SIGNED_IN -> Component.translatable("auth.cubic-cadence.signed_in");
            case REFRESHING -> Component.translatable("auth.cubic-cadence.refreshing");
            case EXPIRED -> Component.translatable("auth.cubic-cadence.expired");
            case ERROR -> Component.translatable("auth.cubic-cadence.error");
        };
    }

    private void handlePlayPause() {
        if (this.playerController.getCurrentTrack() != null) {
            switch (this.playerController.getState()) {
                case PLAYING -> this.playerController.pause();
                case PAUSED -> this.playerController.resume();
                default -> {
                    return;
                }
            }
            updateControls();
            return;
        }
        switch (this.audioEngine.getState()) {
            case PLAYING -> this.audioEngine.pause();
            case PAUSED -> this.audioEngine.resume();
            case BUFFERING, RESOLVING -> {
                return;
            }
            default -> this.audioEngine.playLocal(TEST_TRACKS[this.testTrackIndex]);
        }
        updateControls();
        updateAuthControls();
    }

    private void cycleTestTrack() {
        this.testTrackIndex = (this.testTrackIndex + 1) % TEST_TRACKS.length;
        ModConfig.getInstance().setLastTestTrackIndex(this.testTrackIndex);
        stopPlayback();
        if (this.formatButton != null) {
            this.formatButton.setMessage(Component.literal(TEST_TRACK_LABELS[this.testTrackIndex]));
        }
        updateControls();
    }

    private void stopPlayback() {
        if (this.playerController.getCurrentTrack() != null) {
            this.playerController.stop();
        } else {
            this.audioEngine.stop();
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
        if (this.formatButton != null) {
            this.formatButton.active = this.playerController.getCurrentTrack() == null;
        }
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
            this.active = this.audioEngine.isSeekSupported()
                    && durationMs > 0L
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

}
