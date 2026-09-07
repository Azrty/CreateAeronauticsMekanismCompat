package com.jarrettonesource.createmekanismcompat.mixin.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import mekanism.client.gui.GuiDimensionalStabilizer;
import mekanism.common.tile.machine.TileEntityDimensionalStabilizer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GuiDimensionalStabilizer.class, remap = false)
public abstract class GuiDimensionalStabilizerMixin {
    @Redirect(
        method = "addGuiElements",
        at = @At(
            value = "INVOKE",
            target = "Lmekanism/common/tile/machine/TileEntityDimensionalStabilizer;getBlockPos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos cmc$useMountedWorldPosition(TileEntityDimensionalStabilizer tile) {
        if (tile.getLevel() == null) {
            return tile.getBlockPos();
        }

        BlockPos localPos = tile.getBlockPos();
        SubLevel subLevel = Sable.HELPER.getContaining(tile.getLevel(), localPos);
        if (subLevel == null || subLevel.isRemoved()) {
            return localPos;
        }

        Vec3 global = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(localPos));
        return BlockPos.containing(global);
    }
}
