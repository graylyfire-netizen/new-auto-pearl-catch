package me.alpha432.oyvey.mixin.entity;

import me.alpha432.oyvey.event.Stage;
import me.alpha432.oyvey.event.impl.entity.player.PreTickEvent;
import me.alpha432.oyvey.event.impl.entity.player.TickEvent;
import me.alpha432.oyvey.event.impl.entity.player.UpdateWalkingPlayerEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.alpha432.oyvey.util.traits.Util.EVENT_BUS;

@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {
    /**
     * Start of the player tick: before sendPosition(), so packets sent from here precede this tick's movement packet
     * exactly like the ones vanilla sends while handling input.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void preTickHook(CallbackInfo ci) {
        EVENT_BUS.post(new PreTickEvent());
    }

    /** End of the player tick: AFTER sendPosition(). Do not send interaction packets from here. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void tickHook(CallbackInfo ci) {
        EVENT_BUS.post(new TickEvent());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.AFTER))
    private void tickHook2(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.PRE));
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;sendPosition()V", shift = At.Shift.AFTER))
    private void tickHook3(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.POST));
    }
}
