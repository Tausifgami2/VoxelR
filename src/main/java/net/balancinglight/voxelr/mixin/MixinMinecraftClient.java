package net.balancinglight.voxelr.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.balancinglight.voxelr.VoxelRClient;
import net.balancinglight.voxelr.VoxelRMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FIX: Class was named MixinMinecraft but file is MixinMinecraftClient.java.
 * Renamed class to match filename.
 * Mojang name: Minecraft (was Minecraft in Yarn).
 * All injects are require=0 for cross-version safety.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftClient {

    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void VoxelR_onStop(CallbackInfo ci) {
        VoxelRMod.LOGGER.info("[VoxelR] Game stopping — destroying renderer.");
        if (VoxelRClient.getRenderer() != null)    VoxelRClient.getRenderer().destroy();
        if (VoxelRClient.getVoxelWorld() != null)  VoxelRClient.getVoxelWorld().shutdown();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V",
            at = @At("HEAD"), require = 0)
    private void VoxelR_onDisconnect(Screen screen, CallbackInfo ci) {
        if (VoxelRClient.getVoxelWorld() != null)  VoxelRClient.getVoxelWorld().onWorldUnload();
    }

    @Inject(method = "disconnect()V", at = @At("HEAD"), require = 0)
    private void VoxelR_onDisconnectNoArg(CallbackInfo ci) {
        if (VoxelRClient.getVoxelWorld() != null)  VoxelRClient.getVoxelWorld().onWorldUnload();
    }
}
