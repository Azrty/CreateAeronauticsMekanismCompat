package com.jarrettonesource.createmekanismcompat.mixin;

import mekanism.common.tile.machine.TileEntityDigitalMiner;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TileEntityDigitalMiner.class, remap = false)
public interface TileEntityDigitalMinerTargetAccessor {
    @Accessor("targetChunk")
    @Nullable ChunkPos cmc$getTargetChunk();
}
