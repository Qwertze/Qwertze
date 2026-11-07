package com.porter.qwerte.mixin;

import com.porter.qwerte.utils.SnapshotSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

/**
 * Vanilla 1.21.11 resolves a name-only player head (a ResolvableProfile.Dynamic) on a worker thread, and
 * LocalPlayerResolver looks the name up in the tab list through getPlayerInfoIgnoreCase, which walks the
 * plain HashMap that the render thread changes on every player info packet. The resulting
 * ConcurrentModificationException fails the future, the render thread rethrows it when it reads the
 * future while drawing the head, and Minecraft.run() ends the game ("Unreported exception thrown!").
 * Off the render thread the lookup now walks a copy, retried when the copy itself races. The render
 * thread keeps the vanilla code (nothing changes the map under it). 1.21.4 has neither the resolver nor
 * this method, so this mixin exists only in the 1.21.11 build.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Shadow
    @Final
    private Map<UUID, PlayerInfo> playerInfoMap;

    @Inject(method = "getPlayerInfoIgnoreCase", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$safeLookup(String name, CallbackInfoReturnable<PlayerInfo> cir) {
        if (Minecraft.getInstance().isSameThread()) return;
        cir.setReturnValue(SnapshotSearch.find(playerInfoMap.values(), info -> info.getProfile().name().equalsIgnoreCase(name)));
    }
}
