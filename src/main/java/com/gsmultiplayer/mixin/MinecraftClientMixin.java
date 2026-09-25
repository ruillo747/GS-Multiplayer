package com.gsmultiplayer.mixin;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.room.RoomManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves the room when the local world/server goes away (disconnect, world switch). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "disconnect", at = @At("TAIL"))
    private void gsmultiplayer$onDisconnect(Screen screen, CallbackInfo ci) {
        GsMultiplayerClient mod = GsMultiplayerClient.get();
        if (mod == null) {
            return;
        }
        RoomManager rooms = mod.rooms();
        if (rooms != null && rooms.getPhase() != RoomManager.Phase.IDLE) {
            rooms.leaveRoom();
        }
    }
}
