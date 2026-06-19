package com.example.shieldhelper.client;

import com.example.shieldhelper.config.ShieldHelperConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

@Environment(EnvType.CLIENT)
public final class ShieldHelperSafety {
    private ShieldHelperSafety() {
    }

    public static boolean isActionAllowed(Minecraft client, ShieldHelperConfig config) {
        if (client == null || client.level == null) {
            return false;
        }

        return isSingleplayer(client)
                || ShieldHelperConfig.SAFETY_TRUSTED_SERVERS.equals(config.serverSafetyMode)
                && config.isTrustedServer(getCurrentServerAddress(client));
    }

    public static boolean isSingleplayer(Minecraft client) {
        if (client == null) {
            return false;
        }

        IntegratedServer server = client.getSingleplayerServer();
        return client.isSingleplayer() && server != null && !server.isPublished();
    }

    public static boolean canTrustCurrentServer(Minecraft client) {
        return !isSingleplayer(client) && !getCurrentServerAddress(client).isEmpty();
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
