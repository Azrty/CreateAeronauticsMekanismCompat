package com.jarrettonesource.createmekanismcompat.mounted.miner.scan;

import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContext;
import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedMiningTarget;
import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedScanBounds;
import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedScanSectionJob;
import com.jarrettonesource.createmekanismcompat.mounted.miner.MountedScanState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class SweepingTrailScanPlanner {
    private static final int TIME_CHECK_INTERVAL = 256;

    public List<MountedMiningTarget> nextBatch(MountedMekanismContext context, TileEntityDigitalMiner miner,
            MountedScanState state, int budget, int targetLimit, long deadlineNanos) {
        MountedScanBounds currentBounds = MountedScanBounds.current(context, miner);
        List<MountedMiningTarget> targets = new ArrayList<>(Math.max(0, Math.min(budget, targetLimit)));
        if (budget <= 0 || targetLimit <= 0 || currentBounds.isEmpty()
                || miner.getDiameter() <= 0 || miner.getMaxY() < miner.getMinY()) {
            state.recordScanStats(0, 0, 0, 0, 0, 0);
            return targets;
        }

        ScanStats stats = new ScanStats();
        boolean startedGeneration = state.startScanGenerationIfNeeded(currentBounds);
        if (startedGeneration) {
            List<MountedScanBounds> deltaRegions = deltaRegions(currentBounds, state.completedScanBounds());
            BlockPos center = BlockPos.containing(context.globalCenter());
            for (MountedScanBounds region : deltaRegions) {
                state.enqueueSectionJobs(sectionJobs(context, miner, region, center, stats));
            }
            if (!state.hasScanBacklog()) {
                state.completeScanGeneration();
                state.recordScanStats(0, 0, stats.queuedSectionJobs, 0, stats.skippedEmptySections, stats.skippedPaletteSections);
                return targets;
            }
        } else if (!state.hasScanGeneration()) {
            state.recordScanStats(0, 0, 0, 0, 0, 0);
            return targets;
        }
        MountedScanBounds scanBounds = state.scanGenerationBounds();
        if (scanBounds == null) {
            state.recordScanStats(0, 0, 0, 0, 0, 0);
            return targets;
        }

        while (stats.visitedPositions < budget && targets.size() < targetLimit && System.nanoTime() < deadlineNanos) {
            MountedScanSectionJob job = state.activeSectionJob();
            if (job == null) {
                state.completeScanGeneration();
                break;
            }

            state.rememberTicketChunk(job.chunkPos());
            LevelChunk chunk = context.level().getChunkSource().getChunkNow(job.chunkX(), job.chunkZ());
            if (chunk == null) {
                stats.skippedUnloadedSections++;
                state.requestTicketRefresh();
                break;
            }
            int remainingBudget = budget - stats.visitedPositions;
            int remainingJob = job.size() - state.sectionCursor();
            int toInspect = Math.min(remainingBudget, remainingJob);
            int inspected = 0;
            for (; inspected < toInspect && targets.size() < targetLimit; inspected++) {
                if ((stats.visitedPositions & (TIME_CHECK_INTERVAL - 1)) == 0 && System.nanoTime() >= deadlineNanos) {
                    break;
                }
                int cursor = state.sectionCursor() + inspected;
                BlockPos pos = job.posAt(cursor);
                stats.visitedPositions++;
                if (!scanBounds.contains(pos)) {
                    continue;
                }
                BlockState blockState = WorldUtils.getBlockStateIfLoaded((BlockGetter) context.level(), pos);
                if (blockState == null) {
                    state.requestTicketRefresh();
                    stats.skippedUnloadedSections++;
                    break;
                }
                MountedMiningTarget target = MountedTargetRules.resolve(context, miner, pos, blockState);
                if (target != null && !state.hasQueuedTarget(target.pos())) {
                    targets.add(target);
                }
            }
            state.advanceSectionCursor(inspected);
            if (state.sectionCursor() >= job.size()) {
                state.completeActiveSectionJob();
            }
        }

        if (!state.hasScanBacklog() && state.hasScanGeneration()) {
            state.completeScanGeneration();
        }
        state.recordScanStats(stats.visitedPositions, targets.size(), stats.queuedSectionJobs,
                stats.skippedUnloadedSections, stats.skippedEmptySections, stats.skippedPaletteSections);
        return targets;
    }

    private List<MountedScanBounds> deltaRegions(MountedScanBounds current, MountedScanBounds previous) {
        if (previous == null || previous.isEmpty()) {
            return List.of(current);
        }
        MountedScanBounds overlap = current.intersection(previous);
        if (overlap.isEmpty()) {
            return List.of(current);
        }

        List<MountedScanBounds> regions = new ArrayList<>(6);
        addIfNonEmpty(regions, new MountedScanBounds(
                current.minX(), overlap.minX() - 1,
                current.minY(), current.maxY(), current.minZ(), current.maxZ()));
        addIfNonEmpty(regions, new MountedScanBounds(
                overlap.maxX() + 1, current.maxX(),
                current.minY(), current.maxY(), current.minZ(), current.maxZ()));

        addIfNonEmpty(regions, new MountedScanBounds(
                overlap.minX(), overlap.maxX(),
                current.minY(), overlap.minY() - 1, current.minZ(), current.maxZ()));
        addIfNonEmpty(regions, new MountedScanBounds(
                overlap.minX(), overlap.maxX(),
                overlap.maxY() + 1, current.maxY(), current.minZ(), current.maxZ()));

        addIfNonEmpty(regions, new MountedScanBounds(
                overlap.minX(), overlap.maxX(), overlap.minY(), overlap.maxY(),
                current.minZ(), overlap.minZ() - 1));
        addIfNonEmpty(regions, new MountedScanBounds(
                overlap.minX(), overlap.maxX(), overlap.minY(), overlap.maxY(),
                overlap.maxZ() + 1, current.maxZ()));
        return regions;
    }

    private static void addIfNonEmpty(List<MountedScanBounds> regions, MountedScanBounds bounds) {
        if (!bounds.isEmpty()) {
            regions.add(bounds);
        }
    }

    private List<MountedScanSectionJob> sectionJobs(MountedMekanismContext context, TileEntityDigitalMiner miner,
            MountedScanBounds scanBounds, BlockPos center, ScanStats stats) {
        if (scanBounds.isEmpty()) {
            return List.of();
        }
        int minX = scanBounds.minX();
        int maxX = scanBounds.maxX();
        int minY = scanBounds.minY();
        int maxY = scanBounds.maxY();
        int minZ = scanBounds.minZ();
        int maxZ = scanBounds.maxZ();

        List<ChunkPos> chunks = chunksInRange(minX, maxX, minZ, maxZ, center);
        List<Integer> sectionYs = sectionYsInRange(minY, maxY);
        List<MountedScanSectionJob> jobs = new ArrayList<>();
        ServerLevel level = context.level();
        for (int sectionY : sectionYs) {
            int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
            if (sectionIndex < 0) {
                continue;
            }
            for (ChunkPos chunkPos : chunks) {
                int sectionMinX = chunkPos.getMinBlockX();
                int sectionMaxX = chunkPos.getMaxBlockX();
                int sectionMinY = sectionY << 4;
                int sectionMaxY = sectionMinY + 15;
                int sectionMinZ = chunkPos.getMinBlockZ();
                int sectionMaxZ = chunkPos.getMaxBlockZ();
                MountedScanSectionJob job = new MountedScanSectionJob(
                        chunkPos.x,
                        chunkPos.z,
                        sectionY,
                        Math.max(minX, sectionMinX) - sectionMinX,
                        Math.min(maxX, sectionMaxX) - sectionMinX,
                        Math.max(minY, sectionMinY) - sectionMinY,
                        Math.min(maxY, sectionMaxY) - sectionMinY,
                        Math.max(minZ, sectionMinZ) - sectionMinZ,
                        Math.min(maxZ, sectionMaxZ) - sectionMinZ
                );

                jobs.add(job);
            }
        }
        stats.queuedSectionJobs += jobs.size();
        return jobs;
    }

    private List<ChunkPos> chunksInRange(int minX, int maxX, int minZ, int maxZ, BlockPos center) {
        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        List<ChunkPos> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        chunks.sort(Comparator
                .comparingInt((ChunkPos chunk) -> Math.abs(chunk.x - centerChunkX) + Math.abs(chunk.z - centerChunkZ))
                .thenComparingInt(chunk -> Math.abs(chunk.x - centerChunkX))
                .thenComparingInt(chunk -> Math.abs(chunk.z - centerChunkZ)));
        return chunks;
    }

    private List<Integer> sectionYsInRange(int minY, int maxY) {
        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        List<Integer> sectionYs = new ArrayList<>();
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            sectionYs.add(sectionY);
        }
        return sectionYs;
    }

    private static final class ScanStats {
        private int visitedPositions;
        private int queuedSectionJobs;
        private int skippedUnloadedSections;
        private int skippedEmptySections;
        private int skippedPaletteSections;
    }
}
