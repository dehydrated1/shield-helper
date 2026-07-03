package com.example.shieldhelper.mixin;

import com.example.shieldhelper.client.SilentRotation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSilentRotationMixin {
    @Unique
    private SilentRotation.Snapshot shieldhelper$aiStepSnapshot;
    @Unique
    private SilentRotation.Snapshot shieldhelper$sendPositionSnapshot;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void shieldhelper$aiStepHead(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (SilentRotation.active() && !SilentRotation.appliedTo(player)) {
            this.shieldhelper$aiStepSnapshot = SilentRotation.apply(player);
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void shieldhelper$aiStepTail(CallbackInfo ci) {
        if (this.shieldhelper$aiStepSnapshot != null) {
            this.shieldhelper$aiStepSnapshot.restore((LocalPlayer) (Object) this);
            this.shieldhelper$aiStepSnapshot = null;
        }
    }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void shieldhelper$sendPositionHead(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (SilentRotation.active() && !SilentRotation.appliedTo(player)) {
            this.shieldhelper$sendPositionSnapshot = SilentRotation.apply(player);
        }
    }

    @Inject(method = "sendPosition", at = @At("TAIL"))
    private void shieldhelper$sendPositionTail(CallbackInfo ci) {
        if (this.shieldhelper$sendPositionSnapshot != null) {
            this.shieldhelper$sendPositionSnapshot.restore((LocalPlayer) (Object) this);
            this.shieldhelper$sendPositionSnapshot = null;
        }
    }
}
