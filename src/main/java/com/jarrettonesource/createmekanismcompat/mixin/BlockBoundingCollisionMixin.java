package com.jarrettonesource.createmekanismcompat.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.common.block.BlockBounding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockBounding.class, remap = false)
public abstract class BlockBoundingCollisionMixin {
    @Unique
    private static final double CMC_SHAPE_EPSILON = 1.0E-7D;

    @Unique
    private static final VoxelShape CMC_BLOCK_BOUNDS = Shapes.block();

    @Unique
    private static final Map<VoxelShape, VoxelShape> CMC_CLIPPED_SHAPES = new ConcurrentHashMap<>();

    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void cmc$clipProxyCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> callback) {
        if (!(level instanceof SubLevel)) {
            return;
        }

        VoxelShape shape = callback.getReturnValue();
        if (shape == null || shape.isEmpty()) {
            return;
        }

        VoxelShape cached = CMC_CLIPPED_SHAPES.get(shape);
        if (cached == null) {
            cached = cmc$extendsOutsideBlock(shape)
                    ? Shapes.join(shape, CMC_BLOCK_BOUNDS, BooleanOp.AND).optimize()
                    : shape;
            if (CMC_CLIPPED_SHAPES.size() > 512) {
                CMC_CLIPPED_SHAPES.clear();
            }
            CMC_CLIPPED_SHAPES.put(shape, cached);
        }
        if (cached != shape) {
            callback.setReturnValue(cached);
        }
    }

    @Unique
    private static boolean cmc$extendsOutsideBlock(VoxelShape shape) {
        AABB bounds = shape.bounds();
        return bounds.minX < -CMC_SHAPE_EPSILON
                || bounds.minY < -CMC_SHAPE_EPSILON
                || bounds.minZ < -CMC_SHAPE_EPSILON
                || bounds.maxX > 1.0D + CMC_SHAPE_EPSILON
                || bounds.maxY > 1.0D + CMC_SHAPE_EPSILON
                || bounds.maxZ > 1.0D + CMC_SHAPE_EPSILON;
    }
}
