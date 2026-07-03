package com.example.shieldhelper.client;

import com.example.shieldhelper.config.ShieldHelperConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class ShieldHelperRuntime {
    public static int triggerCooldownTicks;
    private static boolean toggleKeyWasDown;

    private ShieldHelperRuntime() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ShieldHelperRuntime::tick);
    }

    private static void tick(Minecraft client) {
        if (triggerCooldownTicks > 0) {
            triggerCooldownTicks--;
        }

        ShieldHelperConfig config = ShieldHelperConfig.get();
        handleMacroToggleHotkey(client, config);
    }

    private static void handleMacroToggleHotkey(Minecraft client, ShieldHelperConfig config) {
        if (!config.hotkeyToggleEnabled || client == null || client.screen != null || client.getWindow() == null) {
            toggleKeyWasDown = false;
            return;
        }

        boolean toggleKeyDown = InputConstants.isKeyDown(client.getWindow(), config.toggleKeyCode);
        if (toggleKeyDown && !toggleKeyWasDown) {
            config.enabled = !config.enabled;
            ShieldHelperConfig.save();
            sendMacroStatusMessage(client, config);
        }

        toggleKeyWasDown = toggleKeyDown;
    }

    private static void sendMacroStatusMessage(Minecraft client, ShieldHelperConfig config) {
        if (!config.statusNotifications || client.player == null) {
            return;
        }

        if (!config.enabled) {
            client.player.displayClientMessage(Component.translatable("shield-helper.message.macro_off"), true);
            return;
        }

        client.player.displayClientMessage(Component.translatable(ShieldHelperSafety.isActionAllowed(client, config)
                ? "shield-helper.message.macro_on"
                : "shield-helper.message.macro_blocked"), true);
    }
}
