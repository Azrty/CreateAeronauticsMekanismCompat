package com.jarrettonesource.createmekanismcompat.mounted;

import com.jarrettonesource.createmekanismcompat.config.CmcConfig;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import mekanism.common.tile.TileEntityTeleporter;
import mekanism.common.tile.machine.TileEntityDimensionalStabilizer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class MountedChunkLoaderTickets {
    private static final Map<TileEntityTeleporter, ChunkPos> LAST_TELEPORTER_CHUNKS = new WeakHashMap<>();
    private static final Map<ServerSubLevelContainer, Integer> DISCOVERY_COOLDOWN = new WeakHashMap<>();
    private static final int DISCOVERY_INTERVAL_TICKS = 20;

    private static final SubLevelLoadingTicketType<BlockPos> SABLE_MEKANISM_LOADER_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath("create_mekanism_compat", "mekanism_chunk_loader"),
                    BlockPos.CODEC
            );

    private MountedChunkLoaderTickets() {}

    public static void bootstrap() {
    }

    public static void refresh(ServerSubLevelContainer container) {
        Integer cooldown = DISCOVERY_COOLDOWN.get(container);
        if (cooldown != null && cooldown > 0) {
            DISCOVERY_COOLDOWN.put(container, cooldown - 1);
            return;
        }
        DISCOVERY_COOLDOWN.put(container, DISCOVERY_INTERVAL_TICKS - 1);
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel != null && !subLevel.isRemoved()) {
                refresh(subLevel);
            }
        }
    }

    private static void refresh(ServerSubLevel subLevel) {
        Set<BlockPos> activeSableTicketKeys = new HashSet<>();
        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            if (holder == null || holder.getChunk() == null) continue;
            for (BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                if (blockEntity instanceof TileEntityDimensionalStabilizer stabilizer) {
                    MountedDimensionalStabilizerTickets.refreshIfChanged(stabilizer);
                    if (stabilizer.getChunkLoader().canOperate()) {
                        activeSableTicketKeys.add(stabilizer.getBlockPos());
                    }
                } else if (blockEntity instanceof TileEntityTeleporter teleporter) {
                    refreshTeleporter(teleporter);
                    if (teleporter.getChunkLoader().canOperate()) {
                        activeSableTicketKeys.add(teleporter.getBlockPos());
                    }
                }
            }
        }
        syncSableForceLoadTickets(subLevel, activeSableTicketKeys);
    }

    private static void syncSableForceLoadTickets(ServerSubLevel subLevel, Set<BlockPos> activeKeys) {
        SubLevelContainer base = SubLevelContainer.getContainer((Level) subLevel.getLevel());
        if (!(base instanceof ServerSubLevelContainer container)) {
            return;
        }

        Set<SubLevelLoadingTicket<?>> existing = container.collectForceLoadTickets().get(subLevel);
        if (existing != null && !existing.isEmpty()) {
            for (SubLevelLoadingTicket<?> ticket : List.copyOf(existing)) {
                if (SABLE_MEKANISM_LOADER_TICKET.equals(ticket.type())
                        && ticket.key() instanceof BlockPos key
                        && !activeKeys.contains(key)) {
                    container.removeForceLoadTicket(subLevel, SABLE_MEKANISM_LOADER_TICKET, key);
                }
            }
        }
        for (BlockPos key : activeKeys) {
            container.addForceLoadTicket(subLevel, SABLE_MEKANISM_LOADER_TICKET, key);
        }
    }

    private static void refreshTeleporter(TileEntityTeleporter teleporter) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) return;
        MountedMekanismContextResolver.resolve(teleporter).ifPresentOrElse(context -> {
            ChunkPos current = new ChunkPos(context.globalBlockPos());
            if (!current.equals(LAST_TELEPORTER_CHUNKS.get(teleporter))) {
                LAST_TELEPORTER_CHUNKS.put(teleporter, current);
                teleporter.getChunkLoader().refreshChunkTickets();
            }
        }, () -> LAST_TELEPORTER_CHUNKS.remove(teleporter));
    }
}
