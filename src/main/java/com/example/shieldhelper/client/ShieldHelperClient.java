package com.example.shieldhelper.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class ShieldHelperClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ShieldHelperRuntime.register();
    }
}
