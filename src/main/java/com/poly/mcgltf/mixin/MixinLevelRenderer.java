package com.poly.mcgltf.mixin;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.poly.mcgltf.GltfRenderState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void mcgltf$captureProjectionMatrix(
        GraphicsResourceAllocator graphicsResourceAllocator,
        DeltaTracker deltaTracker,
        boolean bl,
        Camera camera,
        Matrix4f cameraRotation,
        Matrix4f projectionMatrix,
        Matrix4f cullingMatrix,
        GpuBufferSlice fogBuffer,
        Vector4f fogColor,
        boolean bl2,
        CallbackInfo ci
    ) {
        GltfRenderState.INSTANCE.captureProjectionMatrix(projectionMatrix);
    }
}
