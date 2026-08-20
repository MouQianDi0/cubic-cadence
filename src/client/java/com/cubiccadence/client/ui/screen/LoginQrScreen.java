package com.cubiccadence.client.ui.screen;

import com.cubiccadence.auth.AuthState;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationStatus;
import com.cubiccadence.client.CubicCadenceClient;
import com.cubiccadence.client.auth.AuthManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Dedicated login screen that renders the NetEase authorization URL as a QR
 * code inside the game window, polls the gateway and returns to the music
 * library once authorized.
 */
public class LoginQrScreen extends Screen {
    private static final int QR_SCALE = 4;
    private static final int QR_QUIET_ZONE = 8;
    private static final int TOP_RESERVE = 46;
    private static final int BOTTOM_RESERVE = 40;

    private final AuthManager authManager;
    private volatile BitMatrix qrMatrix;
    private volatile String encodedContent;
    private Button backButton;
    private Button regenerateButton;
    private int loadingTicks;
    private boolean navigated;

    public LoginQrScreen() {
        super(Component.translatable("screen.cubic-cadence.login_qr"));
        this.authManager = CubicCadenceClient.getAuthManager();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.backButton = this.addRenderableWidget(
                Button.builder(
                                Component.translatable("button.cubic-cadence.back"),
                                button -> returnToLibrary()
                        )
                        .bounds(centerX - 108, this.height - 36, 104, Button.DEFAULT_HEIGHT)
                        .build()
        );
        this.regenerateButton = this.addRenderableWidget(
                Button.builder(
                                Component.translatable("button.cubic-cadence.regenerate_qr"),
                                button -> regenerate()
                        )
                        .bounds(centerX + 4, this.height - 36, 104, Button.DEFAULT_HEIGHT)
                        .build()
        );
        ensureAuthorization();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        extractor.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);

        BitMatrix matrix = this.qrMatrix;
        if (matrix == null) {
            String dots = ".".repeat((this.loadingTicks / 20) % 4);
            Component loading = Component.translatable("auth.cubic-cadence.qr_loading").copy().append(dots);
            extractor.centeredText(this.font, loading, this.width / 2, verticalCenter(1), 0xFFBDBDBD);
        } else {
            int matrixSize = matrix.getWidth();
            int pixelSize = matrixSize * QR_SCALE;
            int left = (this.width - pixelSize) / 2;
            int top = verticalCenter(pixelSize);
            extractor.fill(
                    left - QR_QUIET_ZONE,
                    top - QR_QUIET_ZONE,
                    left + pixelSize + QR_QUIET_ZONE,
                    top + pixelSize + QR_QUIET_ZONE,
                    0xFFFFFFFF
            );
            for (int y = 0; y < matrixSize; y++) {
                for (int x = 0; x < matrixSize; x++) {
                    if (matrix.get(x, y)) {
                        extractor.fill(
                                left + x * QR_SCALE,
                                top + y * QR_SCALE,
                                left + x * QR_SCALE + QR_SCALE,
                                top + y * QR_SCALE + QR_SCALE,
                                0xFF000000
                        );
                    }
                }
            }
        }

        extractor.centeredText(
                this.font,
                statusMessage(),
                this.width / 2,
                18 + 24,
                statusColor()
        );
    }

    @Override
    public void tick() {
        this.loadingTicks++;
        if (this.authManager.getState() == AuthState.SIGNED_IN) {
            returnToLibrary();
            return;
        }
        this.authManager.getChallenge().ifPresent(this::encodeIfNeeded);
        AuthState state = this.authManager.getState();
        this.regenerateButton.active = state == AuthState.EXPIRED || state == AuthState.ERROR;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        returnToLibrary();
        super.onClose();
    }

    private int verticalCenter(int blockHeight) {
        int available = Math.max(0, this.height - TOP_RESERVE - BOTTOM_RESERVE - blockHeight);
        return TOP_RESERVE + available / 2;
    }

    private void returnToLibrary() {
        if (this.navigated || this.minecraft == null) {
            return;
        }
        this.navigated = true;
        this.minecraft.setScreenAndShow(new MusicLibraryScreen());
    }

    private void ensureAuthorization() {
        AuthorizationChallenge existing = this.authManager.getChallenge().orElse(null);
        if (existing != null) {
            encodeIfNeeded(existing);
            return;
        }
        this.authManager.beginLogin()
                .thenRun(() -> this.authManager.getChallenge().ifPresent(this::encodeIfNeeded))
                .exceptionally(throwable -> {
                    CubicCadenceClient.LOGGER.warn("Cubic Cadence could not start NetEase login");
                    return null;
                });
    }

    private void regenerate() {
        this.qrMatrix = null;
        this.encodedContent = null;
        this.authManager.beginLogin()
                .thenRun(() -> this.authManager.getChallenge().ifPresent(this::encodeIfNeeded))
                .exceptionally(throwable -> {
                    CubicCadenceClient.LOGGER.warn("Cubic Cadence could not regenerate the NetEase QR code");
                    return null;
                });
    }

    private void encodeIfNeeded(AuthorizationChallenge challenge) {
        String content = challenge.qrCodeContent();
        if (content == null || content.isBlank() || content.equals(this.encodedContent)) {
            return;
        }
        this.encodedContent = content;
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, 1
            );
            this.qrMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception exception) {
            this.qrMatrix = null;
            CubicCadenceClient.LOGGER.warn("Cubic Cadence could not encode the NetEase QR code");
        }
    }

    private Component statusMessage() {
        if (this.authManager.getState() == AuthState.AUTHORIZING
                && this.authManager.getLastStatus() == AuthorizationStatus.SCANNED) {
            return Component.translatable("auth.cubic-cadence.scanned");
        }
        return switch (this.authManager.getState()) {
            case SIGNED_OUT -> Component.translatable("auth.cubic-cadence.signed_out");
            case AUTHORIZING -> Component.translatable("auth.cubic-cadence.authorizing");
            case SIGNED_IN -> Component.translatable("auth.cubic-cadence.signed_in");
            case REFRESHING -> Component.translatable("auth.cubic-cadence.refreshing");
            case EXPIRED -> Component.translatable("auth.cubic-cadence.expired");
            case ERROR -> Component.translatable("auth.cubic-cadence.error");
        };
    }

    private int statusColor() {
        if (this.authManager.getState() == AuthState.SIGNED_IN) {
            return 0xFF80FF80;
        }
        if (this.authManager.getState() == AuthState.EXPIRED || this.authManager.getState() == AuthState.ERROR) {
            return 0xFFFF6B6B;
        }
        if (this.authManager.getLastStatus() == AuthorizationStatus.SCANNED) {
            return 0xFFFFD166;
        }
        return 0xFFBDBDBD;
    }
}
