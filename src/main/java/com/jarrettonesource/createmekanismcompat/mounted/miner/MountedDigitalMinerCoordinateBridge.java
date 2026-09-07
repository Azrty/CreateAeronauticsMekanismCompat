package com.jarrettonesource.createmekanismcompat.mounted.miner;

import com.jarrettonesource.createmekanismcompat.mixin.TileEntityDigitalMinerTargetAccessor;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContext;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContextResolver;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.common.content.miner.ThreadMinerSearch.State;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** Keeps Mekanism's vanilla Digital Miner logic, but snapshots Sable's projected coordinates. */
public final class MountedDigitalMinerCoordinateBridge {
    private static final ConcurrentHashMap<TileEntityDigitalMiner, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private MountedDigitalMinerCoordinateBridge() {}

    public static BlockPos captureStartingPos(TileEntityDigitalMiner miner) {
        MountedMekanismContext context = MountedMekanismContextResolver.resolve(miner).orElse(null);
        if (context == null) {
            SNAPSHOTS.remove(miner);
            return vanillaStartingPos(miner);
        }
        BlockPos center = context.globalBlockPos();
        Snapshot snapshot = new Snapshot(center, startingPos(center, miner));
        SNAPSHOTS.put(miner, snapshot);
        miner.getChunkLoader().refreshChunkTickets();
        return snapshot.startingPos;
    }

    public static BlockPos startingPos(TileEntityDigitalMiner miner) {
        Snapshot snapshot = SNAPSHOTS.get(miner);
        return snapshot == null ? vanillaStartingPos(miner) : snapshot.startingPos;
    }

    public static BlockPos minerPos(TileEntityDigitalMiner miner) {
        Snapshot snapshot = SNAPSHOTS.get(miner);
        return snapshot == null ? miner.getBlockPos() : snapshot.center;
    }

    public static void clear(TileEntityDigitalMiner miner) {
        SNAPSHOTS.remove(miner);
    }

    public static void serverTick(TileEntityDigitalMiner miner) {
        MountedMekanismContext context = MountedMekanismContextResolver.resolve(miner).orElse(null);
        Snapshot snapshot = SNAPSHOTS.get(miner);

        if (context == null) {
            if (snapshot != null) {
                if (miner.isRunning()) restart(miner);
                else miner.reset();
            }
            return;
        }

        if (snapshot == null) {
            if (miner.isRunning()) restart(miner);
            else if (miner.searcher.state != State.IDLE || miner.getToMine() != 0) miner.reset();
            return;
        }

        BlockPos currentCenter = context.globalBlockPos();
        ChunkPos currentChunk = new ChunkPos(currentCenter);
        boolean loaded = context.level().getChunkSource().getChunkNow(currentChunk.x, currentChunk.z) != null;
        if (!loaded) {
            snapshot.sawProjectedChunkUnload = true;
            return;
        }
        if (snapshot.sawProjectedChunkUnload) {
            snapshot.sawProjectedChunkUnload = false;
            if (miner.isRunning()) restart(miner);
            return;
        }

        if (miner.searcher.state == State.SEARCHING || miner.searcher.state == State.IDLE) return;

        int remaining = miner.getToMine();
        if (remaining > 0) {
            snapshot.hadTargets = true;
            snapshot.zeroRecorded = false;
            return;
        }
        if (!miner.isRunning()) return;

        if (snapshot.hadTargets) {
            restart(miner); // exactly one new vanilla scan after the cached list is exhausted
            return;
        }
        if (!snapshot.zeroRecorded) {
            snapshot.zeroRecorded = true;
            snapshot.exhaustedCenter = snapshot.center;
            return;
        }
        if (!currentCenter.equals(snapshot.exhaustedCenter)) restart(miner);
    }

    /** Mirrors vanilla getChunkSet(), centered on the immutable scan snapshot. */
    public static Optional<Set<ChunkPos>> mountedChunkSet(TileEntityDigitalMiner miner) {
        MountedMekanismContext context = MountedMekanismContextResolver.resolve(miner).orElse(null);
        if (context == null) return Optional.empty();
        Snapshot snapshot = SNAPSHOTS.get(miner);
        BlockPos center = snapshot == null ? context.globalBlockPos() : snapshot.center;
        ChunkPos self = new ChunkPos(center);
        ChunkPos target = ((TileEntityDigitalMinerTargetAccessor) miner).cmc$getTargetChunk();
        if (target == null || !withinRadius(center, miner.getRadius(), target)) {
            return Optional.of(Collections.singleton(self));
        }
        return Optional.of(self.equals(target) ? Set.of(self) : Set.of(self, target));
    }

    private static boolean withinRadius(BlockPos center, int radius, ChunkPos target) {
        int minX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        return target.x >= minX && target.x <= maxX && target.z >= minZ && target.z <= maxZ;
    }

    private static void restart(TileEntityDigitalMiner miner) {
        miner.reset();
        miner.start();
        miner.getChunkLoader().refreshChunkTickets();
    }

    private static BlockPos vanillaStartingPos(TileEntityDigitalMiner miner) {
        return startingPos(miner.getBlockPos(), miner);
    }

    private static BlockPos startingPos(BlockPos center, TileEntityDigitalMiner miner) {
        return new BlockPos(center.getX() - miner.getRadius(), miner.getMinY(), center.getZ() - miner.getRadius());
    }

    private static final class Snapshot {
        final BlockPos center;
        final BlockPos startingPos;
        boolean hadTargets;
        boolean zeroRecorded;
        BlockPos exhaustedCenter;
        boolean sawProjectedChunkUnload;

        Snapshot(BlockPos center, BlockPos startingPos) {
            this.center = center;
            this.startingPos = startingPos;
            this.exhaustedCenter = center;
        }
    }
}
