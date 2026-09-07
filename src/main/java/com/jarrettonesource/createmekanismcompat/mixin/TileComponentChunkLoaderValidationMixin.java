package com.jarrettonesource.createmekanismcompat.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import it.unimi.dsi.fastutil.longs.LongSet;
import mekanism.common.tile.component.TileComponentChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TileComponentChunkLoader.ChunkValidationCallback.class, remap = false)
public abstract class TileComponentChunkLoaderValidationMixin {
    @Inject(
            method = "validateTickets(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/common/world/chunk/TicketHelper;Lit/unimi/dsi/fastutil/longs/LongSet;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cmc$preserveTicketWhileSablePlotIsUnloaded(ServerLevel level, ResourceLocation dimension, BlockPos pos,
            TicketHelper helper, LongSet chunks, boolean ticking, CallbackInfo callback) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null) {
            return;
        }
        ChunkPos plotChunk = new ChunkPos(pos);
        if (container.inBounds(plotChunk) && container.getPlot(plotChunk) == null) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "validateTickets(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/common/world/chunk/TicketHelper;Lit/unimi/dsi/fastutil/longs/LongSet;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")
    )
    private BlockEntity cmc$resolveMountedChunkLoaderForValidation(ServerLevel level, BlockPos pos) {
        BlockEntity direct = level.getBlockEntity(pos);
        if (direct != null) {
            return direct;
        }
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null) {
            return null;
        }
        ChunkPos globalPlotChunk = new ChunkPos(pos);
        LevelPlot plot = container.getPlot(globalPlotChunk);
        if (plot == null) {
            return null;
        }
        LevelChunk chunk = plot.getChunk(plot.toLocal(globalPlotChunk));
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }
}
