package com.example.shieldhelper.config;

import com.example.shieldhelper.ShieldHelperMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ShieldHelperConfig {
    public static final String SAFETY_TRUSTED_SERVERS = "trusted_servers";
    public static final int DEFAULT_TOGGLE_KEY_CODE = 86;
    public static final String DEFAULT_TOGGLE_KEY_NAME = "key.keyboard.v";
    private static final int CURRENT_CONFIG_VERSION = 21;
    private static final int DEFAULT_STUN_WEB_DELAY_MILLIS = 100;
    private static final int DEFAULT_SWITCH_BACK_DELAY_MILLIS = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(ShieldHelperMod.MOD_ID + ".json");

    private static ShieldHelperConfig instance;

    public int configVersion = CURRENT_CONFIG_VERSION;
    public boolean enabled = true;
    public boolean hotkeyToggleEnabled = true;
    public boolean statusNotifications = false;
    public int toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
    public String toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
    public String serverSafetyMode = SAFETY_TRUSTED_SERVERS;
    public List<String> trustedServers = new ArrayList<>();
    public boolean switchBackAfterAttack = true;
    public boolean stunning = false;
    public boolean stunWeb = false;
    public boolean requireAttackCooldown = true;
    public int minimumAttackStrengthPercent = 90;
    public int attackCooldownTicks = 8;
    public int swapDelayMillis = 0;
    public int firstAttackDelayMillis = 0;
    public int stunningMinDelayMillis = 50;
    public int stunningMaxDelayMillis = 50;
    public int stunWebDelayMillis = DEFAULT_STUN_WEB_DELAY_MILLIS;
    public int switchBackDelayMillis = DEFAULT_SWITCH_BACK_DELAY_MILLIS;
    public boolean blatantMode = false;
    public int missPercentage = 0;

    private ShieldHelperConfig() {
    }

    public static ShieldHelperConfig get() {
        if (instance == null) {
            load();
        }

        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                instance = GSON.fromJson(reader, ShieldHelperConfig.class);
            } catch (IOException | RuntimeException exception) {
                ShieldHelperMod.LOGGER.warn("Failed to load Shield Helper config. Using defaults.", exception);
            }
        }

        if (instance == null) {
            instance = new ShieldHelperConfig();
        }

        instance.migrate();
        instance.clamp();
        save();
    }

    public static void save() {
        ShieldHelperConfig config = getWithoutLoading();
        if (config == null) {
            return;
        }

        config.clamp();

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            ShieldHelperMod.LOGGER.warn("Failed to save Shield Helper config.", exception);
        }
    }

    public void reset() {
        configVersion = CURRENT_CONFIG_VERSION;
        enabled = true;
        hotkeyToggleEnabled = true;
        statusNotifications = false;
        toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
        toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        trustedServers = new ArrayList<>();
        switchBackAfterAttack = true;
        stunning = false;
        stunWeb = false;
        requireAttackCooldown = true;
        minimumAttackStrengthPercent = 90;
        attackCooldownTicks = 8;
        swapDelayMillis = 0;
        firstAttackDelayMillis = 0;
        stunningMinDelayMillis = 50;
        stunningMaxDelayMillis = 50;
        stunWebDelayMillis = DEFAULT_STUN_WEB_DELAY_MILLIS;
        switchBackDelayMillis = DEFAULT_SWITCH_BACK_DELAY_MILLIS;
        blatantMode = false;
        missPercentage = 0;
    }

    public void cycleBlatantMode() {
        blatantMode = !blatantMode;
        clamp();
    }

    public void cycleMissPercentage() {
        missPercentage += 5;
        if (missPercentage > 100) {
            missPercentage = 0;
        }

        clamp();
    }

    public void cycleMinimumAttackStrength() {
        minimumAttackStrengthPercent += 5;
        if (minimumAttackStrengthPercent > 100) {
            minimumAttackStrengthPercent = 50;
        }

        clamp();
    }

    public void cycleAttackCooldownTicks() {
        attackCooldownTicks += 1;
        if (attackCooldownTicks > 20) {
            attackCooldownTicks = 0;
        }

        clamp();
    }

    public boolean isTrustedServer(String serverAddress) {
        String normalizedAddress = normalizeServerAddress(serverAddress);
        if (normalizedAddress.isEmpty()) {
            return false;
        }

        for (String trustedServer : trustedServers) {
            if (normalizedAddress.equals(normalizeServerAddress(trustedServer))) {
                return true;
            }
        }

        return false;
    }

    public void toggleTrustedServer(String serverAddress) {
        String normalizedAddress = normalizeServerAddress(serverAddress);
        if (normalizedAddress.isEmpty()) {
            return;
        }

        if (isTrustedServer(normalizedAddress)) {
            trustedServers.removeIf(trustedServer -> normalizedAddress.equals(normalizeServerAddress(trustedServer)));
        } else {
            trustedServers.add(normalizedAddress);
        }

        clamp();
    }

    public static String normalizeServerAddress(String serverAddress) {
        if (serverAddress == null) {
            return "";
        }

        String address = serverAddress.trim().toLowerCase(Locale.ROOT);
        int colonIndex = address.indexOf(':');
        if (colonIndex != -1) {
            address = address.substring(0, colonIndex);
        }
        return address;
    }

    private static ShieldHelperConfig getWithoutLoading() {
        return instance;
    }

    private void migrate() {
        if (configVersion < 6) {
            hotkeyToggleEnabled = true;
            toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
            toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        }

        if (configVersion < 7) {
            trustedServers = new ArrayList<>();
        }

        if (configVersion < 10) {
            serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        }

        if (configVersion < 11) {
            statusNotifications = false;
        }

        if (configVersion < 19) {
            if (stunningMinDelayMillis == 30 && stunningMaxDelayMillis == 50) {
                stunningMinDelayMillis = 50;
                stunningMaxDelayMillis = 50;
            }

            if (switchBackDelayMillis == 0) {
                switchBackDelayMillis = DEFAULT_SWITCH_BACK_DELAY_MILLIS;
            }
        }

        if (configVersion < 20) {
            stunWeb = false;
            stunWebDelayMillis = DEFAULT_STUN_WEB_DELAY_MILLIS;
        }

        if (configVersion < 21) {
            blatantMode = false;
            missPercentage = 0;
        }

        configVersion = CURRENT_CONFIG_VERSION;
    }

    private void clamp() {
        if (toggleKeyCode < 0) {
            toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
        }

        if (toggleKeyName == null || toggleKeyName.isBlank()) {
            toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        }

        if (!SAFETY_TRUSTED_SERVERS.equals(serverSafetyMode)) {
            serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        }

        LinkedHashSet<String> normalizedTrustedServers = new LinkedHashSet<>();
        if (trustedServers != null) {
            for (String trustedServer : trustedServers) {
                String normalizedServer = normalizeServerAddress(trustedServer);
                if (!normalizedServer.isEmpty()) {
                    normalizedTrustedServers.add(normalizedServer);
                }
            }
        }
        trustedServers = new ArrayList<>(normalizedTrustedServers);

        if (minimumAttackStrengthPercent < 50) {
            minimumAttackStrengthPercent = 50;
        } else if (minimumAttackStrengthPercent > 100) {
            minimumAttackStrengthPercent = 100;
        }

        if (attackCooldownTicks < 0) {
            attackCooldownTicks = 0;
        } else if (attackCooldownTicks > 20) {
            attackCooldownTicks = 20;
        }

        swapDelayMillis = clampDelay(swapDelayMillis);
        firstAttackDelayMillis = clampDelay(firstAttackDelayMillis);
        stunningMinDelayMillis = clampDelay(stunningMinDelayMillis);
        stunningMaxDelayMillis = clampDelay(stunningMaxDelayMillis);
        stunWebDelayMillis = clampDelay(stunWebDelayMillis);
        switchBackDelayMillis = clampDelay(switchBackDelayMillis);

        if (stunningMaxDelayMillis < stunningMinDelayMillis) {
            stunningMaxDelayMillis = stunningMinDelayMillis;
        }

        if (!stunning) {
            stunWeb = false;
        }

        if (missPercentage < 0) {
            missPercentage = 0;
        } else if (missPercentage > 100) {
            missPercentage = 100;
        }
    }

    private static int clampDelay(int delayMillis) {
        if (delayMillis < 0) {
            return 0;
        }

        return Math.min(delayMillis, 250);
    }
}
