package com.jarrettonesource.createmekanismcompat.mixin.client;

import com.jarrettonesource.createmekanismcompat.client.CmcClientSableTracking;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftTeleportRetryMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void cmc$retryPendingMountedTeleport(CallbackInfo callback) {
        CmcClientSableTracking.retryPending();
    }
}
