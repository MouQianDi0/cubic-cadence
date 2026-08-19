package com.cubiccadence.client.playback;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * A non-positional music track that uses Minecraft's sound engine without
 * registering itself as the vanilla MusicManager's current track.
 */
final class LocalMusicSoundInstance extends AbstractTickableSoundInstance {
    private final Sound injectedSound;

    LocalMusicSoundInstance(Identifier eventId, Identifier bufferId, float volume) {
        super(
                SoundEvent.createVariableRangeEvent(eventId),
                SoundSource.MASTER,
                SoundInstance.createUnseededRandom()
        );
        this.injectedSound = new Sound(
                bufferId,
                ConstantFloat.of(1.0f),
                ConstantFloat.of(1.0f),
                1,
                Sound.Type.FILE,
                false,
                false,
                16
        );
        this.sound = this.injectedSound;
        this.volume = clampVolume(volume);
        this.pitch = 1.0f;
        this.looping = false;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        this.sound = this.injectedSound;
        WeighedSoundEvents event = new WeighedSoundEvents(this.identifier, null);
        event.addSound(this.injectedSound);
        return event;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        // Volume changes are read by SoundEngine on every tick.
    }

    void setTrackVolume(float volume) {
        this.volume = clampVolume(volume);
    }

    void requestStop() {
        this.stop();
    }

    Identifier bufferPath() {
        return this.injectedSound.getPath();
    }

    private static float clampVolume(float volume) {
        return Math.max(0.0f, Math.min(1.0f, volume));
    }
}
