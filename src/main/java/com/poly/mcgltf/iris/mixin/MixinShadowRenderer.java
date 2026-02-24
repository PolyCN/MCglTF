package com.poly.mcgltf.iris.mixin;
import com.poly.mcgltf.shadow.ShadowProjectionSystem;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class MixinShadowRenderer {
    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void mcgltf$afterRenderShadows(LevelRendererAccessor levelRenderer, Camera camera, CameraRenderState renderState, CallbackInfo ci) {
        ShadowProjectionSystem.INSTANCE.renderPendingShadows();
    }
}

