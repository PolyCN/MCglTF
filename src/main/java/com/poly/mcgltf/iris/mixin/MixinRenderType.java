package com.poly.mcgltf.iris.mixin;
import com.mojang.blaze3d.vertex.MeshData;
import com.poly.mcgltf.iris.IrisRenderingHook;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RenderType.class)
public abstract class MixinRenderType {
    @Inject(method = "draw", at = @At("RETURN"))
    private void mcgltf$afterDraw(MeshData meshData, CallbackInfo ci) {
        IrisRenderingHook.afterRenderTypeDraw((RenderType)(Object)this);
    }
}
