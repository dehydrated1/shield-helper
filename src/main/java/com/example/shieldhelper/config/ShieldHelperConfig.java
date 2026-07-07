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
    public static final double MIN_DISABLE_DISTANCE_BLOCKS = 2.0D;
    public static final double MAX_DISABLE_DISTANCE_BLOCKS = 4.0D;
    public static final double DEFAULT_DISABLE_DISTANCE_BLOCKS = 3.0D;
    private static final int CURRENT_CONFIG_VERSION = 23;
    private static final int DEFAULT_STUN_WEB_DELAY_MILLIS = 100;
    private static final int DEFAULT_SWITCH_BACK_DELAY_MILLIS = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
    public double disableDistanceBlocks = DEFAULT_DISABLE_DISTANCE_BLOCKS;
    public int swapDelayMillis = 0;
    public int firstAttackDelayMillis = 0;
    public int stunningMinDelayMillis = 50;
    public int stunningMaxDelayMillis = 50;
    public int stunWebDelayMillis = DEFAULT_STUN_WEB_DELAY_MILLIS;
    public int switchBackDelayMillis = DEFAULT_SWITCH_BACK_DELAY_MILLIS;
    public boolean blatantMode = false;
    public int missPercentage = 0;

    ShieldHelperConfig() {
    }

    public static ShieldHelperConfig get() {
        if (instance == null) {
            load();
        }

        return instance;
    }

    public static void load() {
        Path configPath = configPath();
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
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
            Path configPath = configPath();
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
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
        disableDistanceBlocks = DEFAULT_DISABLE_DISTANCE_BLOCKS;
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

    public void adjustDisableDistanceTenths(int tenthsDelta) {
        int distanceTenths = (int) Math.round(disableDistanceBlocks * 10.0D) + tenthsDelta;
        int minTenths = (int) Math.round(MIN_DISABLE_DISTANCE_BLOCKS * 10.0D);
        int maxTenths = (int) Math.round(MAX_DISABLE_DISTANCE_BLOCKS * 10.0D);
        distanceTenths = Math.max(minTenths, Math.min(maxTenths, distanceTenths));

        disableDistanceBlocks = distanceTenths / 10.0D;
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

        return serverAddress.trim().toLowerCase(Locale.ROOT);
    }

    private static ShieldHelperConfig getWithoutLoading() {
        return instance;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(ShieldHelperMod.MOD_ID + ".json");
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

        if (configVersion < 22) {
            disableDistanceBlocks = DEFAULT_DISABLE_DISTANCE_BLOCKS;
        }

        if (configVersion < 23) {
            disableDistanceBlocks = roundToTenths(disableDistanceBlocks);
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

        if (!Double.isFinite(disableDistanceBlocks)) {
            disableDistanceBlocks = DEFAULT_DISABLE_DISTANCE_BLOCKS;
        } else if (disableDistanceBlocks < MIN_DISABLE_DISTANCE_BLOCKS) {
            disableDistanceBlocks = MIN_DISABLE_DISTANCE_BLOCKS;
        } else if (disableDistanceBlocks > MAX_DISABLE_DISTANCE_BLOCKS) {
            disableDistanceBlocks = MAX_DISABLE_DISTANCE_BLOCKS;
        } else {
            disableDistanceBlocks = roundToTenths(disableDistanceBlocks);
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

    private static double roundToTenths(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }
}
