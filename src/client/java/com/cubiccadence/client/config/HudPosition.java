package com.cubiccadence.client.config;

/** Anchor used to place the scaled HUD inside a screen or preview viewport. */
public enum HudPosition {
    TOP_LEFT(Horizontal.LEFT, Vertical.TOP),
    TOP_CENTER(Horizontal.CENTER, Vertical.TOP),
    TOP_RIGHT(Horizontal.RIGHT, Vertical.TOP),
    CENTER_LEFT(Horizontal.LEFT, Vertical.CENTER),
    CENTER(Horizontal.CENTER, Vertical.CENTER),
    CENTER_RIGHT(Horizontal.RIGHT, Vertical.CENTER),
    BOTTOM_LEFT(Horizontal.LEFT, Vertical.BOTTOM),
    BOTTOM_CENTER(Horizontal.CENTER, Vertical.BOTTOM),
    BOTTOM_RIGHT(Horizontal.RIGHT, Vertical.BOTTOM);

    private final Horizontal horizontal;
    private final Vertical vertical;

    HudPosition(Horizontal horizontal, Vertical vertical) {
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public int x(int viewportWidth, int hudWidth, int offset, int margin) {
        int anchored = switch (horizontal) {
            case LEFT -> margin;
            case CENTER -> (viewportWidth - hudWidth) / 2;
            case RIGHT -> viewportWidth - hudWidth - margin;
        };
        return clamp(anchored + offset, 0, Math.max(0, viewportWidth - hudWidth));
    }

    public int y(int viewportHeight, int hudHeight, int offset, int margin) {
        int anchored = switch (vertical) {
            case TOP -> margin;
            case CENTER -> (viewportHeight - hudHeight) / 2;
            case BOTTOM -> viewportHeight - hudHeight - margin;
        };
        return clamp(anchored + offset, 0, Math.max(0, viewportHeight - hudHeight));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum Horizontal {
        LEFT,
        CENTER,
        RIGHT
    }

    private enum Vertical {
        TOP,
        CENTER,
        BOTTOM
    }
}
