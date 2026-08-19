package com.cubiccadence.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("soundBuffers")
    SoundBufferLibrary cubicCadence$getSoundBuffers();

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> cubicCadence$getInstanceToChannel();
}
