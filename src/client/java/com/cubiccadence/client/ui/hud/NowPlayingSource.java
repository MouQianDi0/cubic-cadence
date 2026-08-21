package com.cubiccadence.client.ui.hud;

import java.util.Optional;

/** Platform-neutral data source consumed by the reusable HUD renderer. */
@FunctionalInterface
public interface NowPlayingSource {
    Optional<NowPlayingSnapshot> snapshot();
}
