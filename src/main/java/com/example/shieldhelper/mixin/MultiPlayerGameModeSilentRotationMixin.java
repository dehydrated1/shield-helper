package com.example.shieldhelper.mixin;

import com.example.shieldhelper.client.SilentRotation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeSilentRotationMixin {
    @Unique
    private SilentRotation.Snapshot shieldhelper$useItemOnSnapshot;

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void shieldhelper$useItemOnHead(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        this.shieldhelper$useItemOnSnapshot = null;
        if (SilentRotation.active() && !SilentRotation.appliedTo(player)) {
            this.shieldhelper$useItemOnSnapshot = SilentRotation.apply(player);
        }
    }

    @Inject(method = "useItemOn", at = @At("TAIL"))
    private void shieldhelper$useItemOnTail(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (this.shieldhelper$useItemOnSnapshot != null) {
            this.shieldhelper$useItemOnSnapshot.restore(player);
            this.shieldhelper$useItemOnSnapshot = null;
        }
    }
}
