package com.poly.mcgltf.mixin;
import com.poly.mcgltf.GltfRenderState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void mcgltf$onRenderLevelStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        GltfRenderState.INSTANCE.setFrameRendering(true);
    }
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void mcgltf$onRenderLevelEnd(DeltaTracker deltaTracker, CallbackInfo ci) {
        GltfRenderState.INSTANCE.setFrameRendering(false);
    }
}
