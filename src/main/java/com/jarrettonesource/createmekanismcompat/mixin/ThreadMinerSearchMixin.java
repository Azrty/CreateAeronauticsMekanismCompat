package com.jarrettonesource.createmekanismcompat.mixin;

import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedDigitalMinerCoordinateBridge;
import mekanism.common.content.miner.ThreadMinerSearch;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ThreadMinerSearch.class, remap = false)
public abstract class ThreadMinerSearchMixin {
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lmekanism/common/tile/machine/TileEntityDigitalMiner;getStartingPos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos cmc$mountedStartingPos(TileEntityDigitalMiner miner) {
        return MountedDigitalMinerCoordinateBridge.startingPos(miner);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lmekanism/common/tile/machine/TileEntityDigitalMiner;getBlockPos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos cmc$mountedMinerPos(TileEntityDigitalMiner miner) {
        return MountedDigitalMinerCoordinateBridge.minerPos(miner);
    }
}
