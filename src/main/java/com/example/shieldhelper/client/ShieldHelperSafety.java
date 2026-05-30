package com.example.shieldhelper.client;

import com.example.shieldhelper.config.ShieldHelperConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

@Environment(EnvType.CLIENT)
public final class ShieldHelperSafety {
    private ShieldHelperSafety() {
    }

    public static boolean isActionAllowed(Minecraft client, ShieldHelperConfig config) {
        if (client == null || client.level == null) {
            return false;
        }

        if (isSingleplayerOrLan(client)) {
            return true;
        }

        if (ShieldHelperConfig.SAFETY_ALL_WORLDS.equals(config.serverSafetyMode)) {
            return true;
        }

        return ShieldHelperConfig.SAFETY_TRUSTED_SERVERS.equals(config.serverSafetyMode)
                && config.isTrustedServer(getCurrentServerAddress(client));
    }

    public static boolean isSingleplayerOrLan(Minecraft client) {
        if (client == null) {
            return false;
        }

        ServerData serverData = client.getCurrentServer();
        return client.isLocalServer() || serverData != null && serverData.isLan();
    }

    public static boolean canTrustCurrentServer(Minecraft client) {
        return !isSingleplayerOrLan(client) && !getCurrentServerAddress(client).isEmpty();
    }

    public static String getCurrentServerAddress(Minecraft client) {
        if (client == null) {
            return "";
        }

        ServerData serverData = client.getCurrentServer();
        if (serverData == null || serverData.ip == null) {
            return "";
        }

        return ShieldHelperConfig.normalizeServerAddress(serverData.ip);
    }
}
