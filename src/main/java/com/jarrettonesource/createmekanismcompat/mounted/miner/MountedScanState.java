package com.jarrettonesource.createmekanismcompat.mounted.miner;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class MountedScanState {
    private final ArrayDeque<BlockPos> pendingAnchors = new ArrayDeque<>();
    private final ArrayDeque<MountedScanSectionJob> pendingSectionJobs = new ArrayDeque<>();
    private final HashSet<MountedScanSectionJob> pendingSectionJobSet = new HashSet<>();
    private final LinkedHashSet<ChunkPos> recentTicketChunks = new LinkedHashSet<>();
    private final LinkedHashMap<BlockPos, MountedMiningTarget> queuedTargets = new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPos> lastAppliedTicketChunks = new LinkedHashSet<>();

    @Nullable private Vec3 lastGlobalCenter;
    @Nullable private ChunkPos lastTicketCenterChunk;
    @Nullable private MountedScanSectionJob activeSectionJob;
    @Nullable private MountedScanBounds lastPrunedBounds;
    @Nullable private MountedScanBounds completedScanBounds;
    @Nullable private MountedScanBounds scanGenerationBounds;

    private int sectionCursor;
    private int lastVisitedPositions;
    private int lastCandidatePositions;
    private int lastQueuedSectionJobs;
    private int lastSkippedUnloadedSections;
    private int lastSkippedEmptySections;
    private int lastSkippedPaletteSections;

    private boolean hasScanSignature;
    private long scanSignature;
    private int lastSyncedQueueCount = Integer.MIN_VALUE;
    private boolean ticketRefreshRequested = true;
    private boolean ticketSetCheckRequested = true;
    private boolean minerChunkLoaded = true;

    public void reset() {
        pendingAnchors.clear();
        clearSectionBacklog();
        recentTicketChunks.clear();
        queuedTargets.clear();
        lastAppliedTicketChunks.clear();
        lastGlobalCenter = null;
        lastTicketCenterChunk = null;
        lastPrunedBounds = null;
        completedScanBounds = null;
        scanGenerationBounds = null;
        hasScanSignature = false;
        scanSignature = 0L;
        lastSyncedQueueCount = Integer.MIN_VALUE;
        ticketRefreshRequested = true;
        ticketSetCheckRequested = true;
        minerChunkLoaded = true;
        recordScanStats(0, 0, 0, 0, 0, 0);
    }

    public boolean updateScanSignature(long signature) {
        if (hasScanSignature && scanSignature == signature) return false;
        hasScanSignature = true;
        scanSignature = signature;
        invalidateScanAndTargets();
        return true;
    }

    private void invalidateScanAndTargets() {
        pendingAnchors.clear();
        clearSectionBacklog();
        recentTicketChunks.clear();
        queuedTargets.clear();
        lastPrunedBounds = null;
        completedScanBounds = null;
        scanGenerationBounds = null;
        lastGlobalCenter = null;
        lastTicketCenterChunk = null;
        ticketRefreshRequested = true;
        ticketSetCheckRequested = true;
        recordScanStats(0, 0, 0, 0, 0, 0);
    }

    public void invalidateForMinerChunkUnload() {
        invalidateScanAndTargets();
        minerChunkLoaded = false;
    }

    public boolean updateMinerChunkLoaded(boolean loaded) {
        boolean changed = minerChunkLoaded != loaded;
        minerChunkLoaded = loaded;
        return changed;
    }

    public boolean minerChunkLoaded() {
        return minerChunkLoaded;
    }

    public int pruneOutside(MountedScanBounds bounds, int radius, int diameter) {
        if (bounds.equals(lastPrunedBounds)) return 0;
        lastPrunedBounds = bounds;

        if (bounds.isEmpty()) {
            int removed = pendingAnchors.size() + pendingSectionJobs.size() + recentTicketChunks.size() + queuedTargets.size();
            if (activeSectionJob != null) removed++;
            pendingAnchors.clear();
            clearSectionBacklog();
            recentTicketChunks.clear();
            queuedTargets.clear();
            completedScanBounds = null;
            scanGenerationBounds = null;
            return removed;
        }

        int removed = 0;
        int before = pendingAnchors.size();
        pendingAnchors.removeIf(anchor -> !bounds.intersectsHorizontalScan(anchor, radius, diameter));
        removed += before - pendingAnchors.size();
        before = pendingSectionJobs.size();
        pendingSectionJobs.removeIf(job -> !bounds.intersects(job));
        removed += before - pendingSectionJobs.size();
        before = recentTicketChunks.size();
        recentTicketChunks.removeIf(chunk -> !bounds.intersects(chunk));
        removed += before - recentTicketChunks.size();
        before = queuedTargets.size();
        queuedTargets.entrySet().removeIf(entry -> !bounds.contains(entry.getKey()));
        removed += before - queuedTargets.size();

        if (activeSectionJob != null && !bounds.intersects(activeSectionJob)) {
            pendingSectionJobSet.remove(activeSectionJob);
            activeSectionJob = null;
            sectionCursor = 0;
            removed++;
        }
        rebuildSectionJobSet();
        return removed;
    }

    public void updateAnchors(Vec3 currentGlobalCenter, int maxSamples) {
        if (lastGlobalCenter == null) {
            enqueueAnchor(BlockPos.containing(currentGlobalCenter));
            lastGlobalCenter = currentGlobalCenter;
            return;
        }
        BlockPos previous = BlockPos.containing(lastGlobalCenter);
        BlockPos current = BlockPos.containing(currentGlobalCenter);
        lastGlobalCenter = currentGlobalCenter;
        if (previous.getX() == current.getX() && previous.getZ() == current.getZ()) return;

        int dx = current.getX() - previous.getX();
        int dz = current.getZ() - previous.getZ();
        int distance = Math.max(Math.abs(dx), Math.abs(dz));
        int samples = Math.max(1, Math.min(maxSamples, distance));
        for (int i = 1; i <= samples; i++) {
            double fraction = i / (double) samples;
            int x = previous.getX() + (int) Math.round(dx * fraction);
            int z = previous.getZ() + (int) Math.round(dz * fraction);
            enqueueAnchor(new BlockPos(x, current.getY(), z));
        }
    }

    private void enqueueAnchor(BlockPos anchor) {
        if (!pendingAnchors.contains(anchor)) pendingAnchors.add(anchor);
        while (pendingAnchors.size() > 64) pendingAnchors.removeFirst();
    }

    @Nullable public BlockPos pollAnchor() { return pendingAnchors.pollFirst(); }

    public void enqueueSectionJobs(List<MountedScanSectionJob> jobs) {
        for (MountedScanSectionJob job : jobs) {
            if (pendingSectionJobSet.add(job)) pendingSectionJobs.addLast(job);
        }
    }

    @Nullable
    public MountedScanSectionJob activeSectionJob() {
        if (activeSectionJob == null) {
            activeSectionJob = pendingSectionJobs.pollFirst();
            sectionCursor = 0;
        }
        return activeSectionJob;
    }

    public int sectionCursor() { return sectionCursor; }
    public void advanceSectionCursor(int amount) { sectionCursor += amount; }

    public void completeActiveSectionJob() {
        if (activeSectionJob != null) pendingSectionJobSet.remove(activeSectionJob);
        activeSectionJob = null;
        sectionCursor = 0;
    }

    public boolean hasScanBacklog() {
        return !pendingAnchors.isEmpty() || activeSectionJob != null || !pendingSectionJobs.isEmpty();
    }

    private void clearSectionBacklog() {
        pendingSectionJobs.clear();
        pendingSectionJobSet.clear();
        activeSectionJob = null;
        sectionCursor = 0;
    }

    private void rebuildSectionJobSet() {
        pendingSectionJobSet.clear();
        if (activeSectionJob != null) pendingSectionJobSet.add(activeSectionJob);
        pendingSectionJobSet.addAll(pendingSectionJobs);
    }

    public boolean startScanGenerationIfNeeded(MountedScanBounds currentBounds) {
        if (scanGenerationBounds != null) {
            if (scanGenerationBounds.equals(currentBounds)) return false;
            clearSectionBacklog();
            scanGenerationBounds = null;
        }
        if (completedScanBounds != null && completedScanBounds.equals(currentBounds)) return false;
        scanGenerationBounds = currentBounds;
        ticketSetCheckRequested = true;
        return true;
    }

    @Nullable public MountedScanBounds completedScanBounds() { return completedScanBounds; }
    @Nullable public MountedScanBounds scanGenerationBounds() { return scanGenerationBounds; }
    public boolean hasScanGeneration() { return scanGenerationBounds != null; }

    public void completeScanGeneration() {
        if (scanGenerationBounds != null) completedScanBounds = scanGenerationBounds;
        scanGenerationBounds = null;
        clearSectionBacklog();
    }

    public void rememberTicketChunk(ChunkPos chunk) {
        recentTicketChunks.remove(chunk);
        recentTicketChunks.add(chunk);
        while (recentTicketChunks.size() > 81) {
            ChunkPos oldest = recentTicketChunks.iterator().next();
            recentTicketChunks.remove(oldest);
        }
    }

    public Set<ChunkPos> recentTicketChunks() { return Collections.unmodifiableSet(recentTicketChunks); }

    public boolean updateTicketCenterChunk(ChunkPos chunk) {
        if (chunk.equals(lastTicketCenterChunk)) return false;
        lastTicketCenterChunk = chunk;
        return true;
    }

    public void requestTicketRefresh() {
        ticketRefreshRequested = true;
        ticketSetCheckRequested = true;
    }

    public boolean ticketsNeedEvaluation() { return ticketRefreshRequested || ticketSetCheckRequested; }
    public boolean shouldRefreshTickets(Set<ChunkPos> desired) { return ticketRefreshRequested || !lastAppliedTicketChunks.equals(desired); }

    public void markTicketsEvaluated(Set<ChunkPos> desired) {
        lastAppliedTicketChunks.clear();
        lastAppliedTicketChunks.addAll(desired);
        ticketRefreshRequested = false;
        ticketSetCheckRequested = false;
    }

    public void enqueueTargets(List<MountedMiningTarget> targets, int maxTargets) {
        for (MountedMiningTarget target : targets) {
            if (!queuedTargets.containsKey(target.pos()) && queuedTargets.size() >= maxTargets) return;
            queuedTargets.put(target.pos(), target);
        }
    }

    public boolean hasQueuedTarget(BlockPos pos) { return queuedTargets.containsKey(pos); }

    @Nullable
    public MountedMiningTarget peekTarget() {
        Iterator<Map.Entry<BlockPos, MountedMiningTarget>> iterator = queuedTargets.entrySet().iterator();
        return iterator.hasNext() ? iterator.next().getValue() : null;
    }

    public void removeTarget(BlockPos pos) { queuedTargets.remove(pos); }
    public int queuedTargetCount() { return queuedTargets.size(); }
    public int lastSyncedQueueCount() { return lastSyncedQueueCount; }
    public void markQueueCountSynced(int count) { lastSyncedQueueCount = count; }

    public void recordScanStats(int visitedPositions, int candidatePositions, int queuedSectionJobs, int skippedUnloadedSections, int skippedEmptySections, int skippedPaletteSections) {
        lastVisitedPositions = visitedPositions;
        lastCandidatePositions = candidatePositions;
        lastQueuedSectionJobs = queuedSectionJobs;
        lastSkippedUnloadedSections = skippedUnloadedSections;
        lastSkippedEmptySections = skippedEmptySections;
        lastSkippedPaletteSections = skippedPaletteSections;
    }

    public int lastVisitedPositions() { return lastVisitedPositions; }
    public int lastCandidatePositions() { return lastCandidatePositions; }
    public int lastQueuedSectionJobs() { return lastQueuedSectionJobs; }
    public int lastSkippedUnloadedSections() { return lastSkippedUnloadedSections; }
    public int lastSkippedEmptySections() { return lastSkippedEmptySections; }
    public int lastSkippedPaletteSections() { return lastSkippedPaletteSections; }
}
