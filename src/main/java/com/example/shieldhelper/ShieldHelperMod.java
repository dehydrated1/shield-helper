package com.example.shieldhelper;

import com.example.shieldhelper.config.ShieldHelperConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShieldHelperMod implements ModInitializer {

    public static final String MOD_ID = "shield-helper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ShieldHelperConfig.load();
        LOGGER.info("ShieldHelper initialized!");
    }
}
