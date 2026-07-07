package com.example.shieldhelper.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;

@Environment(EnvType.CLIENT)
public final class SilentRotation {
    private static volatile boolean active;
    private static volatile float yaw;
    private static volatile float pitch;
    private static volatile Object holder;

    private SilentRotation() {
    }

    public static void set(float yaw, float pitch, Object holder) {
        SilentRotation.yaw = yaw;
        SilentRotation.pitch = pitch;
        SilentRotation.holder = holder;
        active = true;
    }

    public static void stop(Object holder) {
        if (SilentRotation.holder == holder) {
            active = false;
            SilentRotation.holder = null;
        }
    }

    public static void clear() {
        active = false;
        holder = null;
    }

    public static boolean active() {
        return active;
    }

    public static boolean appliedTo(LocalPlayer player) {
        return active && player != null && Float.compare(player.getYRot(), yaw) == 0 && Float.compare(player.getXRot(), pitch) == 0;
    }

    public static Snapshot apply(LocalPlayer player) {
        Snapshot snapshot = new Snapshot(
                player.getYRot(),
                player.getXRot(),
                player.yRotO,
                player.xRotO,
                player.yBodyRot,
                player.yBodyRotO,
                player.yHeadRot,
                player.yHeadRotO
        );
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
        player.yHeadRot = yaw;
        player.yHeadRotO = yaw;
        return snapshot;
    }

    public record Snapshot(float yaw, float pitch, float previousYaw, float previousPitch, float bodyYaw,
                           float previousBodyYaw, float headYaw, float previousHeadYaw) {
        public void restore(LocalPlayer player) {
            player.setYRot(this.yaw);
            player.setXRot(this.pitch);
            player.yRotO = this.previousYaw;
            player.xRotO = this.previousPitch;
            player.yBodyRot = this.bodyYaw;
            player.yBodyRotO = this.previousBodyYaw;
            player.yHeadRot = this.headYaw;
            player.yHeadRotO = this.previousHeadYaw;
        }
    }
}
