package com.jarrettonesource.createmekanismcompat.mounted.miner;

import com.jarrettonesource.createmekanismcompat.config.CmcConfig;
import com.jarrettonesource.createmekanismcompat.mixin.TileEntityDigitalMinerAccessor;
import com.jarrettonesource.createmekanismcompat.mounted.ChunkTicketPolicy;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContext;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContextResolver;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.common.content.miner.ThreadMinerSearch.State;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import net.minecraft.world.level.ChunkPos;

public final class MountedDigitalMinerControllers {
    private static final MountedDigitalMinerController CONTROLLER = new MountedDigitalMinerController();
    private static final Map<MountedMinerKey, MountedScanState> STATES = new ConcurrentHashMap<>();

    private MountedDigitalMinerControllers() {
    }

    public static boolean prepareMountedStart(TileEntityDigitalMiner miner) {
        MountedMekanismContext mounted = mountedContext(miner).orElse(null);
        if (mounted == null) {
            return false;
        }
        TileEntityDigitalMinerAccessor access = (TileEntityDigitalMinerAccessor) miner;
        access.cmc$setRunning(true);
        miner.searcher.state = State.SEARCHING;

        MountedScanState state = stateFor(mounted);
        state.reset();
        state.updateScanSignature(scanSignature(miner));
        syncOreMap(miner, state);
        refreshTicketsIfNeeded(miner, mounted, state);
        miner.setChanged();
        return true;
    }

    /**
     * Called every server tick by the existing mixin, but v5 keeps this path
     * O(1) while targets remain. The expensive scanner is entered only after
     * the target queue becomes empty, or after an explicit invalidation.
     */
    public static boolean scanMounted(TileEntityDigitalMiner miner) {
        MountedMekanismContext mounted = mountedContext(miner).orElse(null);
        if (mounted == null) {
            return false;
        }

        MountedMinerKey key = MountedMinerKey.from(mounted);
        if (!miner.isRunning()) {
            STATES.remove(key);
            if (miner.getToMine() != 0) {
                clearOreMap(miner);
            }
            return true;
        }
        if (!canEverMine(miner)) {
            STATES.remove(key);
            miner.searcher.state = State.FINISHED;
            if (miner.getToMine() != 0) {
                clearOreMap(miner);
            }
            return true;
        }

        MountedScanState state = STATES.computeIfAbsent(key, ignored -> new MountedScanState());
        int queueBefore = state.queuedTargetCount();

        // Explicit configuration changes are correctness invalidations. They
        // clear stale targets, which naturally makes the queue empty and allows
        // one fresh scan; this is not a timed/periodic refresh.
        state.updateScanSignature(scanSignature(miner));

        ChunkPos currentGlobalChunk = new ChunkPos(mounted.globalBlockPos());
        boolean projectedChunkLoaded = mounted.level().getChunkSource().getChunkNow(
                currentGlobalChunk.x, currentGlobalChunk.z) != null;
        if (!projectedChunkLoaded) {
            if (state.minerChunkLoaded()) {
                state.invalidateForMinerChunkUnload();
            }
            miner.searcher.state = State.SEARCHING;
            syncOreMap(miner, state);
            refreshTicketsIfNeeded(miner, mounted, state);
            if (queueBefore != state.queuedTargetCount()) {
                miner.setChanged();
            }
            return true;
        }
        state.updateMinerChunkLoaded(true);

        // Controller.scan prunes stale out-of-bounds targets first. If any
        // valid mineral remains afterward it immediately returns without scan.
        CONTROLLER.scan(mounted, miner, state);
        // Reuse Mekanism's already-container-synced search state instead of a
        // client heuristic. SEARCHING is only exposed while there is genuinely
        // unfinished mounted scan work and no target ready to mine.
        miner.searcher.state = state.queuedTargetCount() == 0 && state.hasScanGeneration()
                ? State.SEARCHING
                : State.FINISHED;
        syncOreMap(miner, state);
        refreshTicketsIfNeeded(miner, mounted, state);

        if (state.queuedTargetCount() != queueBefore) {
            miner.setChanged();
        }
        return true;
    }

    public static boolean mineMounted(TileEntityDigitalMiner miner) {
        MountedMekanismContext mounted = mountedContext(miner).orElse(null);
        if (mounted == null) {
            return false;
        }
        if (!canEverMine(miner)) {
            STATES.remove(MountedMinerKey.from(mounted));
            miner.searcher.state = State.FINISHED;
            if (miner.getToMine() != 0) {
                clearOreMap(miner);
            }
            return true;
        }
        MountedScanState state = stateFor(mounted);
        CONTROLLER.mine(mounted, miner, state);
        syncOreMap(miner, state);
        // O(1) unless mine() detected that the cached target chunk unloaded.
        // In that case we refresh tickets once and keep the target intact.
        refreshTicketsIfNeeded(miner, mounted, state);
        return true;
    }

    public static Optional<Set<ChunkPos>> mountedChunkSet(TileEntityDigitalMiner miner) {
        MountedMekanismContext mounted = mountedContext(miner).orElse(null);
        if (mounted == null) {
            return Optional.empty();
        }
        if (!miner.isRunning()) {
            return Optional.of(Set.of(new ChunkPos(mounted.globalBlockPos())));
        }
        MountedScanState state = stateFor(mounted);
        return Optional.of(ChunkTicketPolicy.digitalMinerChunks(mounted, miner, state));
    }

    public static void reset(TileEntityDigitalMiner miner) {
        MountedMekanismContextResolver.resolve(miner)
                .map(MountedMinerKey::from)
                .ifPresent(STATES::remove);
    }

    private static Optional<MountedMekanismContext> mountedContext(TileEntityDigitalMiner miner) {
        if (!CmcConfig.ENABLE_MOUNTED_DIGITAL_MINER.get()) {
            return Optional.empty();
        }
        return MountedMekanismContextResolver.resolve(miner);
    }

    private static boolean canEverMine(TileEntityDigitalMiner miner) {
        return miner.getInverse() || miner.getFilterManager().hasEnabledFilters();
    }

    private static MountedScanState stateFor(MountedMekanismContext context) {
        return STATES.computeIfAbsent(MountedMinerKey.from(context), key -> new MountedScanState());
    }

    /**
     * Small fingerprint checked every tick so changing filters/radius/Y/inverse
     * never leaves a stale cache active. Filter lists are normally tiny, making
     * this vastly cheaper than a world scan.
     */
    private static long scanSignature(TileEntityDigitalMiner miner) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, miner.getRadius());
        h = mix(h, miner.getMinY());
        h = mix(h, miner.getMaxY());
        h = mix(h, miner.getInverse() ? 1 : 0);
        h = mix(h, miner.getInverseRequiresReplacement() ? 1 : 0);
        h = mix(h, System.identityHashCode(miner.getInverseReplaceTarget()));
        h = mix(h, miner.getFilterManager().getFilters().hashCode());
        return h;
    }

    private static long mix(long hash, int value) {
        return (hash ^ (value & 0xffffffffL)) * 0x100000001b3L;
    }

    private static void refreshTicketsIfNeeded(TileEntityDigitalMiner miner, MountedMekanismContext mounted, MountedScanState state) {
        if (!state.ticketsNeedEvaluation()) {
            return;
        }
        Set<ChunkPos> desired = ChunkTicketPolicy.digitalMinerChunks(mounted, miner, state);
        if (state.shouldRefreshTickets(desired)) {
            miner.getChunkLoader().refreshChunkTickets();
        }
        state.markTicketsEvaluated(desired);
    }

    private static void installSentinelOreMap(TileEntityDigitalMiner miner, int toMineCount) {
        Long2ObjectOpenHashMap<BitSet> oresToMine = new Long2ObjectOpenHashMap<>();
        BitSet bitSet = new BitSet();
        bitSet.set(0);
        oresToMine.put(ChunkPos.asLong(miner.getBlockPos()), bitSet);
        TileEntityDigitalMinerAccessor access = (TileEntityDigitalMinerAccessor) miner;
        access.cmc$setOresToMine(oresToMine);
        access.cmc$setCachedToMine(toMineCount);
    }

    private static void clearOreMap(TileEntityDigitalMiner miner) {
        TileEntityDigitalMinerAccessor access = (TileEntityDigitalMinerAccessor) miner;
        access.cmc$setOresToMine(new Long2ObjectOpenHashMap<>());
        access.cmc$setCachedToMine(0);
    }

    /**
     * Do not allocate a new sentinel map for every mined block. The map only
     * changes on the 0 <-> nonzero transition; intermediate counts update the
     * integer tracker in-place.
     */
    private static void syncOreMap(TileEntityDigitalMiner miner, MountedScanState state) {
        int count = state.queuedTargetCount();
        int previous = state.lastSyncedQueueCount();
        if (count == previous) {
            return;
        }
        TileEntityDigitalMinerAccessor access = (TileEntityDigitalMinerAccessor) miner;
        if (count <= 0) {
            if (previous != 0) {
                clearOreMap(miner);
            }
        } else if (previous <= 0 || previous == Integer.MIN_VALUE) {
            installSentinelOreMap(miner, count);
        } else {
            access.cmc$setCachedToMine(count);
        }
        state.markQueueCountSynced(count);
    }
}
