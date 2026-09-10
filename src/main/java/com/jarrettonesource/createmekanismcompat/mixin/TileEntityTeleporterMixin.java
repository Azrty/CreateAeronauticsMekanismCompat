package com.jarrettonesource.createmekanismcompat.mixin;

import com.jarrettonesource.createmekanismcompat.config.CmcConfig;
import com.jarrettonesource.createmekanismcompat.mounted.ChunkTicketPolicy;
import com.jarrettonesource.createmekanismcompat.mounted.MountedAabb;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContext;
import com.jarrettonesource.createmekanismcompat.mounted.MountedMekanismContextResolver;
import com.jarrettonesource.createmekanismcompat.mounted.MountedTeleporterTargeting;
import com.jarrettonesource.createmekanismcompat.mounted.StaticTeleporterCache;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import mekanism.api.event.MekanismTeleportEvent;
import mekanism.common.content.teleporter.TeleporterFrequency;
import mekanism.common.lib.frequency.FrequencyType;
import mekanism.common.tile.TileEntityTeleporter;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TileEntityTeleporter.class, remap = false)
public abstract class TileEntityTeleporterMixin {
    @Unique
    private static final ThreadLocal<Boolean> cmc$portableTrackingSyncPending = ThreadLocal.withInitial(() -> false);

    @Unique
    @Nullable
    private ChunkPos cmc$lastGlobalChunk;

    @Unique
    private boolean cmc$staticChunkTicketsSuppressed;

    @Inject(method = "getClosest", at = @At("HEAD"), cancellable = true)
    private void cmc$getCachedClosest(TeleporterFrequency frequency, CallbackInfoReturnable<GlobalPos> callback) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get() || frequency == null) {
            return;
        }
        TileEntityTeleporter teleporter = (TileEntityTeleporter) (Object) this;
        if (!(teleporter.getLevel() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }

        MountedMekanismContext mounted = MountedMekanismContextResolver.resolve(teleporter).orElse(null);
        if (mounted != null && !teleporter.getChunkLoader().canOperate()) {
            // Physics teleporters are unsafe without their Anchor keeping the
            // Sable sublevel and projected chunk alive.
            callback.setReturnValue(null);
            return;
        }

        GlobalPos source = mounted == null
                ? teleporter.getTileGlobalPos()
                : GlobalPos.of(mounted.level().dimension(), mounted.globalBlockPos());
        if (mounted == null) {
            StaticTeleporterCache.remember(teleporter, frequency);
        }
        callback.setReturnValue(StaticTeleporterCache.getClosest(level.getServer(), frequency, source));
    }

    @Inject(method = "getToTeleport", at = @At("HEAD"), cancellable = true)
    private void cmc$getMountedEntitiesToTeleport(boolean sameDimension, Level destinationLevel, CallbackInfoReturnable<List<Entity>> callback) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return;
        }
        TileEntityTeleporter teleporter = (TileEntityTeleporter) (Object) this;
        MountedMekanismContextResolver.resolve(teleporter).ifPresent(context -> {
            AABB localBounds = ((TileEntityTeleporterAccessor) this).cmc$getTeleportBounds();
            if (localBounds == null) {
                callback.setReturnValue(List.of());
                return;
            }
            AABB globalBounds = MountedAabb.toGlobal(context, localBounds);
            TileEntityTeleporterAccessor accessor = (TileEntityTeleporterAccessor) this;
            callback.setReturnValue(context.level().getEntitiesOfClass(Entity.class, globalBounds,
                    entity -> sameDimension ? accessor.cmc$canTeleportEntity(entity, null) : accessor.cmc$canTeleportEntity(entity, destinationLevel)));
        });
    }

    @Redirect(
            method = "cleanTeleportCache",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;")
    )
    private <T extends Entity> List<T> cmc$getMountedEntitiesForTeleportCache(Level level, Class<T> entityClass, AABB bounds) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return level.getEntitiesOfClass(entityClass, bounds);
        }
        TileEntityTeleporter teleporter = (TileEntityTeleporter) (Object) this;
        return MountedMekanismContextResolver.resolve(teleporter)
                .map(context -> context.level().getEntitiesOfClass(entityClass, MountedAabb.toGlobal(context, bounds)))
                .orElseGet(() -> level.getEntitiesOfClass(entityClass, bounds));
    }

    @Redirect(
            method = "canTeleport",
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/TileEntityTeleporter;calculateEnergyCost(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/GlobalPos;)J")
    )
    private long cmc$calculateProjectedEnergyCostForReadiness(Entity entity, Level targetWorld, GlobalPos coords) {
        return cmc$calculateProjectedEnergyCost(entity, targetWorld, coords);
    }

    @Redirect(
            method = "teleport",
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/TileEntityTeleporter;calculateEnergyCost(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/GlobalPos;)J")
    )
    private long cmc$calculateProjectedEnergyCostForTeleport(Entity entity, Level targetWorld, GlobalPos coords) {
        return cmc$calculateProjectedEnergyCost(entity, targetWorld, coords);
    }

    @Redirect(
            method = "teleport",
            at = @At(
                    value = "INVOKE",
                    target = "Lmekanism/common/util/WorldUtils;getTileEntity(Ljava/lang/Class;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
                    ordinal = 0
            )
    )
    private BlockEntity cmc$loadCachedStaticDestination(Class<?> tileClass, BlockGetter blockGetter, BlockPos pos) {
        if (CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get() && blockGetter instanceof ServerLevel level && level.getServer() != null) {
            GlobalPos globalPos = GlobalPos.of(level.dimension(), pos);
            if (StaticTeleporterCache.isCached(level.getServer(), globalPos)) {
                return StaticTeleporterCache.resolveForTeleport(level.getServer(), globalPos);
            }
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        BlockEntity tile = WorldUtils.getTileEntity((Class) tileClass, blockGetter, pos);
        return tile;
    }

    @Redirect(
            method = "teleport",
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/TileEntityTeleporter;getTeleporterTargetPos()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos cmc$getProjectedTargetPosForTeleport(TileEntityTeleporter targetTeleporter) {
        return cmc$getProjectedTeleporterTargetPos(targetTeleporter);
    }

    @Redirect(
            method = "teleport",
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/TileEntityTeleporter;teleportEntityTo(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lmekanism/common/tile/TileEntityTeleporter;Lmekanism/api/event/MekanismTeleportEvent$Teleporter;ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/entity/Entity;")
    )
    private Entity cmc$teleportEntityToSableAwareTarget(
            Entity entity,
            Level level,
            TileEntityTeleporter targetTeleporter,
            MekanismTeleportEvent.Teleporter event,
            boolean preserveMotion,
            DimensionTransition.PostDimensionTransition postTransition) {
        MountedMekanismContext mountedTarget = CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()
                ? MountedMekanismContextResolver.resolve(targetTeleporter).orElse(null)
                : null;
        if (mountedTarget != null) {
            cmc$refineMountedEventTarget(targetTeleporter, mountedTarget, event);
        }
        if (CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            cmc$sendClientSableTrackingBeforeTeleport(entity, mountedTarget, event);
        }
        Entity teleported = TileEntityTeleporter.teleportEntityTo(entity, level, targetTeleporter, event, preserveMotion, postTransition);
        if (teleported != null && CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            cmc$syncServerSableTrackingAfterTeleport(teleported, mountedTarget, event);
        }
        return teleported;
    }

    @Inject(
            method = "teleportEntityTo",
            at = @At("HEAD")
    )
    private static void cmc$preparePortableSableTracking(
            Entity entity,
            Level level,
            TileEntityTeleporter targetTeleporter,
            MekanismTeleportEvent.Teleporter event,
            boolean preserveMotion,
            DimensionTransition.PostDimensionTransition postTransition,
            CallbackInfoReturnable<Entity> callback) {
        cmc$portableTrackingSyncPending.set(false);
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return;
        }
        MountedMekanismContext mountedTarget = MountedTeleporterTargeting.resolveMountedTarget(targetTeleporter);
        if (mountedTarget == null) {
            return;
        }
        net.minecraft.world.phys.Vec3 projectedTarget = MountedTeleporterTargeting.getProjectedTeleporterTarget(targetTeleporter, mountedTarget);
        if (event.getTarget().distanceToSqr(projectedTarget) <= 1.0E-6) {
            return;
        }
        cmc$portableTrackingSyncPending.set(true);
        MountedTeleporterTargeting.refineMountedEventTarget(targetTeleporter, mountedTarget, event);
        MountedTeleporterTargeting.sendClientSableTrackingBeforeTeleport(entity, mountedTarget, event);
    }

    @Inject(
            method = "teleportEntityTo",
            at = @At("RETURN")
    )
    private static void cmc$finalizePortableSableTracking(
            Entity entity,
            Level level,
            TileEntityTeleporter targetTeleporter,
            MekanismTeleportEvent.Teleporter event,
            boolean preserveMotion,
            DimensionTransition.PostDimensionTransition postTransition,
            CallbackInfoReturnable<Entity> callback) {
        boolean pending = Boolean.TRUE.equals(cmc$portableTrackingSyncPending.get());
        cmc$portableTrackingSyncPending.remove();
        if (!pending || !CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return;
        }
        Entity teleported = callback.getReturnValue();
        if (teleported == null) {
            return;
        }
        MountedTeleporterTargeting.syncServerSableTrackingAfterTeleport(
                teleported,
                MountedTeleporterTargeting.resolveMountedTarget(targetTeleporter),
                event
        );
    }

    @Inject(method = "getChunkSet", at = @At("HEAD"), cancellable = true)
    private void cmc$getTeleporterChunkSet(CallbackInfoReturnable<Set<ChunkPos>> callback) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return;
        }
        TileEntityTeleporter teleporter = (TileEntityTeleporter) (Object) this;
        MountedMekanismContext mounted = MountedMekanismContextResolver.resolve(teleporter).orElse(null);
        if (mounted == null) {
            // Anchor upgrades intentionally do nothing for normal/static
            // teleporters. Their destination metadata lives in the cache.
            callback.setReturnValue(Collections.emptySet());
        } else {
            callback.setReturnValue(ChunkTicketPolicy.teleporterChunks(mounted, teleporter));
        }
    }

    @Inject(method = "onUpdateServer", at = @At("RETURN"))
    private void cmc$refreshTeleporterCacheAndTickets(CallbackInfoReturnable<Boolean> callback) {
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return;
        }
        TileEntityTeleporter teleporter = (TileEntityTeleporter) (Object) this;
        MountedMekanismContext mounted = MountedMekanismContextResolver.resolve(teleporter).orElse(null);
        if (mounted != null) {
            cmc$staticChunkTicketsSuppressed = false;
            TeleporterFrequency frequency = teleporter.getFrequencyComponent().getFrequency(FrequencyType.TELEPORTER);
            if (frequency != null) {
                StaticTeleporterCache.forget(teleporter, frequency);
            }
            ChunkPos current = new ChunkPos(mounted.globalBlockPos());
            if (!current.equals(cmc$lastGlobalChunk)) {
                cmc$lastGlobalChunk = current;
                teleporter.getChunkLoader().refreshChunkTickets();
            }
            return;
        }

        cmc$lastGlobalChunk = null;
        TeleporterFrequency frequency = teleporter.getFrequencyComponent().getFrequency(FrequencyType.TELEPORTER);
        if (frequency != null) {
            StaticTeleporterCache.remember(teleporter, frequency);
        }
        if (!cmc$staticChunkTicketsSuppressed) {
            // Force one reconciliation so tickets created by an older version
            // are released immediately now that getChunkSet() is empty.
            teleporter.getChunkLoader().refreshChunkTickets();
            cmc$staticChunkTicketsSuppressed = true;
        }
    }

    @Unique
    private static long cmc$calculateProjectedEnergyCost(Entity entity, Level targetWorld, GlobalPos coords) {
        if (CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return MountedTeleporterTargeting.calculateProjectedEnergyCost(entity, targetWorld, coords);
        }
        return TileEntityTeleporter.calculateEnergyCost(entity, targetWorld, coords);
    }

    @Unique
    private static BlockPos cmc$getProjectedTeleporterTargetPos(TileEntityTeleporter targetTeleporter) {
        BlockPos localTarget = targetTeleporter.getTeleporterTargetPos();
        if (!CmcConfig.ENABLE_MOUNTED_TELEPORTER_TARGETS.get()) {
            return localTarget;
        }
        return MountedTeleporterTargeting.getProjectedTeleporterTargetPos(targetTeleporter);
    }

    @Unique
    private static void cmc$refineMountedEventTarget(TileEntityTeleporter targetTeleporter, MountedMekanismContext mountedTarget, MekanismTeleportEvent.Teleporter event) {
        MountedTeleporterTargeting.refineMountedEventTarget(targetTeleporter, mountedTarget, event);
    }

    @Unique
    private static net.minecraft.world.phys.Vec3 cmc$getProjectedTeleporterTarget(TileEntityTeleporter targetTeleporter, MountedMekanismContext mountedTarget) {
        return MountedTeleporterTargeting.getProjectedTeleporterTarget(targetTeleporter, mountedTarget);
    }

    @Unique
    private static net.minecraft.world.phys.Vec3 cmc$bottomCenter(BlockPos pos) {
        return MountedTeleporterTargeting.bottomCenter(pos);
    }

    @Unique
    private static void cmc$sendClientSableTrackingBeforeTeleport(Entity entity, @Nullable MountedMekanismContext mountedTarget, MekanismTeleportEvent.Teleporter event) {
        MountedTeleporterTargeting.sendClientSableTrackingBeforeTeleport(entity, mountedTarget, event);
    }

    @Unique
    private static void cmc$syncServerSableTrackingAfterTeleport(Entity entity, @Nullable MountedMekanismContext mountedTarget, MekanismTeleportEvent.Teleporter event) {
        MountedTeleporterTargeting.syncServerSableTrackingAfterTeleport(entity, mountedTarget, event);
    }
}
