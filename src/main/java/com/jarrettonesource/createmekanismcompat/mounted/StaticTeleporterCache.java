package com.jarrettonesource.createmekanismcompat.mounted;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mekanism.common.content.teleporter.TeleporterFrequency;
import mekanism.common.lib.frequency.FrequencyType;
import mekanism.common.tile.TileEntityTeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.util.datafix.DataFixTypes;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent coordinates for non-mounted Mekanism teleporters.
 *
 * <p>Mekanism intentionally keeps TeleporterFrequency.activeCoords runtime-only.
 * Normally an Anchor upgrade compensates by keeping every teleporter loaded. That
 * is undesirable on large servers because permanently ticking remote chunks also
 * participate in mob spawning. This cache lets normal teleporters leave memory
 * while preserving only the tiny amount of data needed to select them later.</p>
 */
public final class StaticTeleporterCache extends SavedData {
    private static final String DATA_NAME = "create_mekanism_compat_static_teleporters";
    private static final Factory<StaticTeleporterCache> FACTORY = new Factory<>(
            StaticTeleporterCache::new,
            StaticTeleporterCache::load,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<FrequencyKey, LinkedHashSet<GlobalPos>> teleporters = new HashMap<>();

    public static StaticTeleporterCache get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static void remember(TileEntityTeleporter teleporter, TeleporterFrequency frequency) {
        if (!(teleporter.getLevel() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        if (MountedMekanismContextResolver.resolve(teleporter).isPresent()) {
            forgetPosition(level.getServer(), GlobalPos.of(level.dimension(), teleporter.getBlockPos()));
            return;
        }
        get(level.getServer()).rememberInternal(frequencyKey(frequency), GlobalPos.of(level.dimension(), teleporter.getBlockPos()));
    }

    public static void forget(TileEntityTeleporter teleporter, @Nullable TeleporterFrequency frequency) {
        if (!(teleporter.getLevel() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        GlobalPos pos = GlobalPos.of(level.dimension(), teleporter.getBlockPos());
        StaticTeleporterCache cache = get(level.getServer());
        if (frequency == null) {
            cache.forgetPositionInternal(pos);
        } else {
            cache.forgetInternal(frequencyKey(frequency), pos);
        }
    }

    public static void forgetPosition(MinecraftServer server, GlobalPos pos) {
        get(server).forgetPositionInternal(pos);
    }

    public static boolean isCached(MinecraftServer server, GlobalPos pos) {
        return get(server).containsInternal(pos);
    }

    /**
     * Selects a destination without loading any chunks. Loaded live Mekanism
     * coordinates are merged with the persistent static cache. Mounted targets
     * are only eligible while their Anchor chunk loader can operate.
     */
    public static @Nullable GlobalPos getClosest(
            MinecraftServer server,
            TeleporterFrequency frequency,
            GlobalPos source
    ) {
        StaticTeleporterCache cache = get(server);
        FrequencyKey key = frequencyKey(frequency);
        LinkedHashSet<GlobalPos> cachedStatic = cache.teleporters.get(key);
        Set<GlobalPos> staticSnapshot = cachedStatic == null ? Set.of() : Set.copyOf(cachedStatic);

        LinkedHashSet<GlobalPos> candidates = new LinkedHashSet<>();
        candidates.addAll(frequency.getActiveCoords());
        candidates.addAll(staticSnapshot);

        GlobalPos best = null;
        List<GlobalPos> staleStatic = new ArrayList<>();
        for (GlobalPos candidate : candidates) {
            if (candidate.equals(source)) {
                continue;
            }

            ServerLevel targetLevel = server.getLevel(candidate.dimension());
            if (targetLevel == null) {
                if (staticSnapshot.contains(candidate)) {
                    staleStatic.add(candidate);
                }
                continue;
            }

            LevelChunk loadedChunk = targetLevel.getChunkSource().getChunkNow(
                    candidate.pos().getX() >> 4,
                    candidate.pos().getZ() >> 4
            );
            if (loadedChunk != null) {
                BlockEntity blockEntity = loadedChunk.getBlockEntity(candidate.pos());
                if (!(blockEntity instanceof TileEntityTeleporter targetTeleporter)) {
                    if (staticSnapshot.contains(candidate)) {
                        staleStatic.add(candidate);
                    }
                    continue;
                }

                TeleporterFrequency targetFrequency = targetTeleporter.getFrequencyComponent().getFrequency(FrequencyType.TELEPORTER);
                if (targetFrequency == null || !frequencyKey(targetFrequency).equals(key)) {
                    if (staticSnapshot.contains(candidate)) {
                        staleStatic.add(candidate);
                    }
                    continue;
                }

                boolean mounted = MountedMekanismContextResolver.resolve(targetTeleporter).isPresent();
                if (mounted) {
                    // Physics teleporters are deliberately never restored from the
                    // static cache and must have a functioning Anchor upgrade.
                    if (staticSnapshot.contains(candidate)) {
                        staleStatic.add(candidate);
                    }
                    if (!targetTeleporter.getChunkLoader().canOperate()) {
                        continue;
                    }
                } else {
                    cache.rememberInternal(key, candidate);
                }
            } else if (!staticSnapshot.contains(candidate)) {
                // An unloaded physics target cannot be trusted. A legitimate
                // static target is represented by staticSnapshot instead.
                continue;
            }

            if (best == null || isCloser(source, candidate, best)) {
                best = candidate;
            }
        }

        if (!staleStatic.isEmpty()) {
            boolean changed = false;
            LinkedHashSet<GlobalPos> stored = cache.teleporters.get(key);
            if (stored != null) {
                for (GlobalPos stale : staleStatic) {
                    changed |= stored.remove(stale);
                }
                if (stored.isEmpty()) {
                    cache.teleporters.remove(key);
                }
            }
            if (changed) {
                cache.setDirty();
            }
        }
        return best;
    }

    /**
     * Loads a cached static destination exactly when a teleport is being
     * executed. No force ticket is created; once the player arrives normal
     * player chunk loading takes over.
     */
    public static @Nullable TileEntityTeleporter resolveForTeleport(MinecraftServer server, GlobalPos pos) {
        ServerLevel level = server.getLevel(pos.dimension());
        if (level == null) {
            forgetPosition(server, pos);
            return null;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.pos().getX() >> 4, pos.pos().getZ() >> 4);
        if (chunk == null) {
            if (!isCached(server, pos)) {
                return null;
            }
            chunk = level.getChunk(pos.pos().getX() >> 4, pos.pos().getZ() >> 4);
        }

        BlockEntity blockEntity = chunk.getBlockEntity(pos.pos());
        if (!(blockEntity instanceof TileEntityTeleporter teleporter)) {
            forgetPosition(server, pos);
            return null;
        }
        if (MountedMekanismContextResolver.resolve(teleporter).isPresent()) {
            // A physics target must already have been loaded by its Anchor.
            forgetPosition(server, pos);
            return teleporter.getChunkLoader().canOperate() ? teleporter : null;
        }
        return teleporter;
    }

    private void rememberInternal(FrequencyKey key, GlobalPos pos) {
        boolean changed = false;
        for (Map.Entry<FrequencyKey, LinkedHashSet<GlobalPos>> entry : teleporters.entrySet()) {
            if (!entry.getKey().equals(key)) {
                changed |= entry.getValue().remove(pos);
            }
        }
        teleporters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        changed |= teleporters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(pos);
        if (changed) {
            setDirty();
        }
    }

    private void forgetInternal(FrequencyKey key, GlobalPos pos) {
        LinkedHashSet<GlobalPos> positions = teleporters.get(key);
        if (positions != null && positions.remove(pos)) {
            if (positions.isEmpty()) {
                teleporters.remove(key);
            }
            setDirty();
        }
    }

    private void forgetPositionInternal(GlobalPos pos) {
        boolean changed = false;
        for (LinkedHashSet<GlobalPos> positions : teleporters.values()) {
            changed |= positions.remove(pos);
        }
        teleporters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (changed) {
            setDirty();
        }
    }

    private boolean containsInternal(GlobalPos pos) {
        for (Set<GlobalPos> positions : teleporters.values()) {
            if (positions.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCloser(GlobalPos source, GlobalPos candidate, GlobalPos currentBest) {
        boolean candidateSameDimension = source.dimension() == candidate.dimension();
        boolean bestSameDimension = source.dimension() == currentBest.dimension();
        if (candidateSameDimension != bestSameDimension) {
            return candidateSameDimension;
        }
        if (!candidateSameDimension) {
            return false;
        }
        return source.pos().distSqr(candidate.pos()) < source.pos().distSqr(currentBest.pos());
    }

    private static FrequencyKey frequencyKey(TeleporterFrequency frequency) {
        var identity = frequency.getIdentity();
        UUID owner = identity.ownerUUID();
        return new FrequencyKey(
                String.valueOf(identity.key()),
                identity.securityMode().name(),
                owner == null ? "" : owner.toString()
        );
    }

    private static StaticTeleporterCache load(CompoundTag tag, HolderLookup.Provider registries) {
        StaticTeleporterCache cache = new StaticTeleporterCache();
        ListTag list = tag.getList("Teleporters", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimensionId == null) {
                continue;
            }
            FrequencyKey key = new FrequencyKey(
                    entry.getString("Frequency"),
                    entry.getString("Security"),
                    entry.getString("Owner")
            );
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            GlobalPos pos = GlobalPos.of(dimension, new BlockPos(
                    entry.getInt("X"),
                    entry.getInt("Y"),
                    entry.getInt("Z")
            ));
            cache.teleporters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(pos);
        }
        return cache;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<FrequencyKey, LinkedHashSet<GlobalPos>> group : teleporters.entrySet()) {
            FrequencyKey key = group.getKey();
            for (GlobalPos pos : group.getValue()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("Frequency", key.frequency());
                entry.putString("Security", key.security());
                entry.putString("Owner", key.owner());
                entry.putString("Dimension", pos.dimension().location().toString());
                entry.putInt("X", pos.pos().getX());
                entry.putInt("Y", pos.pos().getY());
                entry.putInt("Z", pos.pos().getZ());
                list.add(entry);
            }
        }
        tag.put("Teleporters", list);
        return tag;
    }

    private record FrequencyKey(String frequency, String security, String owner) {
    }
}
