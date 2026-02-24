package com.poly.mcgltf.mixin;
import com.poly.mcgltf.collision.OBB;
import com.poly.mcgltf.collision.OBBCollisionSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class MixinEntity {
    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void mcgltf$adjustBoundingBoxForOBB(CallbackInfoReturnable<AABB> cir) {
        if (!OBBCollisionSystem.INSTANCE.isEnabled()) return;
        Entity self = (Entity) (Object) this;
        OBB obb = OBBCollisionSystem.INSTANCE.getEntityOBB(self);
        if (obb == null) return;
        AABB original = cir.getReturnValue();
        float cx = obb.getCenter().x();
        float cy = obb.getCenter().y();
        float cz = obb.getCenter().z();
        float ex = obb.getHalfExtents().x();
        float ey = obb.getHalfExtents().y();
        float ez = obb.getHalfExtents().z();
        float maxExtent = Math.max(ex, Math.max(ey, ez));
        double obbMinX = cx - maxExtent;
        double obbMinY = cy - maxExtent;
        double obbMinZ = cz - maxExtent;
        double obbMaxX = cx + maxExtent;
        double obbMaxY = cy + maxExtent;
        double obbMaxZ = cz + maxExtent;
        double newMinX = Math.min(original.minX, obbMinX);
        double newMinY = Math.min(original.minY, obbMinY);
        double newMinZ = Math.min(original.minZ, obbMinZ);
        double newMaxX = Math.max(original.maxX, obbMaxX);
        double newMaxY = Math.max(original.maxY, obbMaxY);
        double newMaxZ = Math.max(original.maxZ, obbMaxZ);
        if (newMinX != original.minX || newMinY != original.minY || newMinZ != original.minZ ||
            newMaxX != original.maxX || newMaxY != original.maxY || newMaxZ != original.maxZ) {
            cir.setReturnValue(new AABB(newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ));
        }
    }
}
