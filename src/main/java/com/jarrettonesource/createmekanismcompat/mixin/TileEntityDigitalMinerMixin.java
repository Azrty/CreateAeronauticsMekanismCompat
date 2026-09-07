package com.jarrettonesource.createmekanismcompat.mixin;

import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedDigitalMinerCoordinateBridge;
import java.util.Set;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TileEntityDigitalMiner.class, remap = false)
public abstract class TileEntityDigitalMinerMixin {
    @Redirect(method = "start", at = @At(value = "INVOKE", target = "Lmekanism/common/tile/machine/TileEntityDigitalMiner;getStartingPos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos cmc$mountedStartPosition(TileEntityDigitalMiner miner) {
        return MountedDigitalMinerCoordinateBridge.captureStartingPos(miner);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void cmc$clearMountedSnapshot(CallbackInfo callback) {
        MountedDigitalMinerCoordinateBridge.clear((TileEntityDigitalMiner) (Object) this);
    }

    @Inject(
            method = "onUpdateServer",
            at = @At(value = "INVOKE", target = "Lmekanism/common/inventory/slot/EnergyInventorySlot;fillContainerOrConvert()V", shift = At.Shift.AFTER)
    )
    private void cmc$maintainMountedVanillaSearch(CallbackInfoReturnable<Boolean> callback) {
        MountedDigitalMinerCoordinateBridge.serverTick((TileEntityDigitalMiner) (Object) this);
    }

    @Redirect(method = "tryMineBlock", at = @At(value = "INVOKE", target = "Lmekanism/common/tile/machine/TileEntityDigitalMiner;getStartingPos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos cmc$mountedMiningStartPosition(TileEntityDigitalMiner miner) {
        return MountedDigitalMinerCoordinateBridge.startingPos(miner);
    }

    @Inject(method = "getChunkSet", at = @At("HEAD"), cancellable = true)
    private void cmc$getMountedChunkSet(CallbackInfoReturnable<Set<ChunkPos>> callback) {
        MountedDigitalMinerCoordinateBridge.mountedChunkSet((TileEntityDigitalMiner) (Object) this)
                .ifPresent(callback::setReturnValue);
    }
}
