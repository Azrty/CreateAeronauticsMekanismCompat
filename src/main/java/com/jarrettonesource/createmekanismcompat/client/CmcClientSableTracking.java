package com.jarrettonesource.createmekanismcompat.client;

import com.jarrettonesource.createmekanismcompat.network.MekanismTeleportSableStatePayload;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class CmcClientSableTracking {
    private static volatile MekanismTeleportSableStatePayload pending;

    private CmcClientSableTracking() {}

    public static void applyTeleportState(MekanismTeleportSableStatePayload payload) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            pending = payload.insideSubLevel() ? payload : null;
            return;
        }

        if (payload.insideSubLevel()) {
            SubLevel subLevel = resolveSubLevel(player, payload);
            if (subLevel == null || subLevel.isRemoved()) {
                // The target ship can arrive on the client a few packets after Mekanism's teleport packet.
                // Keep the sublevel id pending instead of clearing it permanently.
                pending = payload;
                unpin(player);
                if (player instanceof EntityMovementExtension movement) {
                    movement.sable$setTrackingSubLevel(null);
                    movement.sable$setLastTrackingSubLevelID(payload.subLevelId());
                }
                EntitySubLevelUtil.setOldPosNoMovement(player);
                return;
            }
            pending = null;
            applyResolved(player, payload, subLevel);
        } else {
            pending = null;
            clear(player);
        }
        EntitySubLevelUtil.setOldPosNoMovement(player);
    }

    /** Retry a teleport whose target Sable sublevel had not reached the client yet. */
    public static void retryPending() {
        MekanismTeleportSableStatePayload payload = pending;
        if (payload == null || !payload.insideSubLevel()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        SubLevel subLevel = resolveSubLevel(player, payload);
        if (subLevel == null || subLevel.isRemoved()) return;
        pending = null;
        applyResolved(player, payload, subLevel);
        EntitySubLevelUtil.setOldPosNoMovement(player);
    }

    private static void applyResolved(LocalPlayer player, MekanismTeleportSableStatePayload payload, SubLevel subLevel) {
        // Never assign plotPosition here: Sable treats it as a hard per-tick position lock.
        unpin(player);
        if (player instanceof EntityMovementExtension movement) {
            movement.sable$setTrackingSubLevel(subLevel);
            movement.sable$setLastTrackingSubLevelID(payload.subLevelId());
        }
    }

    private static SubLevel resolveSubLevel(LocalPlayer player, MekanismTeleportSableStatePayload payload) {
        SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        return container == null ? null : container.getSubLevel(payload.subLevelId());
    }

    private static void unpin(LocalPlayer player) {
        if (player instanceof EntityStickExtension stick) {
            stick.sable$setPlotPosition(null);
        }
    }

    private static void clear(LocalPlayer player) {
        unpin(player);
        if (player instanceof EntityMovementExtension movement) {
            movement.sable$setTrackingSubLevel(null);
            movement.sable$setLastTrackingSubLevelID(null);
        }
    }
}
