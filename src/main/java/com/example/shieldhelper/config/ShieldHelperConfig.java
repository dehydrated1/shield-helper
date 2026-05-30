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
    public static final String PROFILE_DIASMP = "diasmp";
    public static final String PROFILE_SMP = "smp";
    public static final String SAFETY_TRUSTED_SERVERS = "trusted_servers";
    public static final String SAFETY_ALL_WORLDS = "all_worlds";
    public static final int DEFAULT_TOGGLE_KEY_CODE = 86;
    public static final String DEFAULT_TOGGLE_KEY_NAME = "key.keyboard.v";

    private static final int CURRENT_CONFIG_VERSION = 16;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(ShieldHelperMod.MOD_ID + ".json");

    private static ShieldHelperConfig instance;

    public enum StunWebMode {
        OFF,
        ON
    }

    public int configVersion = CURRENT_CONFIG_VERSION;
    public String combatProfile = PROFILE_DIASMP;
    public boolean enabled = true;
    public boolean hotkeyToggleEnabled = true;
    public boolean statusNotifications = false;
    public int toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
    public String toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
    public String serverSafetyMode = SAFETY_TRUSTED_SERVERS;
    public List<String> trustedServers = new ArrayList<>();
    public boolean switchBackAfterAttack = true;
    public boolean stunning = false;
    public StunWebMode stunWeb = StunWebMode.OFF;
    public boolean requireAttackCooldown = true;
    public int minimumAttackStrengthPercent = 90;
    public int attackCooldownTicks = 8;
    public int swapDelayMillis = 0;
    public int firstAttackDelayMillis = 0;
    public int stunningMinDelayMillis = 30;
    public int stunningMaxDelayMillis = 50;
    public int stunWebDelayMillis = 75;
    public int switchBackDelayMillis = 0;
    public long shieldDisableAttempts = 0L;
    public long shieldDisableSuccesses = 0L;
    public double maxAttackRange = 3.0;
    public boolean pingCompensation = true;
    public boolean postAttackGuard = false;
    public int postAttackGuardTicks = 4;
    public boolean blatantMode = false;
    public int missChancePercent = 0;
    public int shieldMissChancePercent = 0;

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
        combatProfile = PROFILE_DIASMP;
        enabled = true;
        hotkeyToggleEnabled = true;
        statusNotifications = false;
        toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
        toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        trustedServers = new ArrayList<>();
        switchBackAfterAttack = true;
        stunning = false;
        stunWeb = StunWebMode.OFF;
        requireAttackCooldown = true;
        minimumAttackStrengthPercent = 90;
        attackCooldownTicks = 8;
        swapDelayMillis = 0;
        firstAttackDelayMillis = 0;
        stunningMinDelayMillis = 30;
        stunningMaxDelayMillis = 50;
        stunWebDelayMillis = 75;
        switchBackDelayMillis = 0;
        maxAttackRange = 3.0;
        pingCompensation = true;
        postAttackGuard = false;
        postAttackGuardTicks = 4;
        blatantMode = false;
        missChancePercent = 0;
        shieldMissChancePercent = 0;
        resetSuccessStats();
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

    public void cycleCombatProfile() {
        combatProfile = PROFILE_SMP.equals(combatProfile) ? PROFILE_DIASMP : PROFILE_SMP;
    }


    public void cycleServerSafetyMode() {
        serverSafetyMode = SAFETY_TRUSTED_SERVERS.equals(serverSafetyMode) ? SAFETY_ALL_WORLDS : SAFETY_TRUSTED_SERVERS;
    }

    public void cycleMaxAttackRange() {
        maxAttackRange += 0.1;
        if (maxAttackRange > 3.05) {
            maxAttackRange = 2.0;
        }
        clamp();
    }

    public void cyclePostAttackGuardTicks() {
        postAttackGuardTicks += 1;
        if (postAttackGuardTicks > 20) {
            postAttackGuardTicks = 1;
        }
        clamp();
    }

    public void cycleBlatantMode() {
        blatantMode = !blatantMode;
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

    public void recordShieldDisableResult(boolean success) {
        if (shieldDisableAttempts == Long.MAX_VALUE) {
            resetSuccessStats();
        }

        shieldDisableAttempts++;
        if (success) {
            shieldDisableSuccesses++;
        }

        clamp();
    }

    public void resetSuccessStats() {
        shieldDisableAttempts = 0L;
        shieldDisableSuccesses = 0L;
    }

    public int getShieldDisableSuccessPercent() {
        if (shieldDisableAttempts <= 0L) {
            return 0;
        }

        return (int) Math.round(shieldDisableSuccesses * 100.0D / shieldDisableAttempts);
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
        if (configVersion < 3) {
            stunning = false;
        }

        if (configVersion < 4) {
            swapDelayMillis = 0;
            firstAttackDelayMillis = 0;
            stunningMinDelayMillis = 30;
            stunningMaxDelayMillis = 50;
            switchBackDelayMillis = 0;
        }

        if (configVersion < 5 || combatProfile == null) {
            combatProfile = PROFILE_DIASMP;
        }

        if (configVersion < 6) {
            hotkeyToggleEnabled = true;
            toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
            toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        }

        if (configVersion < 7) {
            trustedServers = new ArrayList<>();
        }

        if (configVersion < 8) {
            serverSafetyMode = SAFETY_ALL_WORLDS;
        }

        if (configVersion < 9) {
            shieldDisableAttempts = 0L;
            shieldDisableSuccesses = 0L;
        }

        if (configVersion < 10) {
            serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        }

        if (configVersion < 11) {
            statusNotifications = false;
        }

        if (configVersion < 12) {
            stunWeb = StunWebMode.OFF;
            stunWebDelayMillis = 75;
        }

        if (configVersion < 13) {
            // removed stunWebAimAssist
        }



        configVersion = CURRENT_CONFIG_VERSION;
    }

    private void clamp() {


        if (stunWeb == null) {
            stunWeb = StunWebMode.OFF;
        }

        if (!PROFILE_SMP.equals(combatProfile) && !PROFILE_DIASMP.equals(combatProfile)) {
            combatProfile = PROFILE_DIASMP;
        }

        if (toggleKeyCode < 0) {
            toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;
        }

        if (toggleKeyName == null || toggleKeyName.isBlank()) {
            toggleKeyName = DEFAULT_TOGGLE_KEY_NAME;
        }

        if (!SAFETY_TRUSTED_SERVERS.equals(serverSafetyMode) && !SAFETY_ALL_WORLDS.equals(serverSafetyMode)) {
            serverSafetyMode = SAFETY_TRUSTED_SERVERS;
        }

        if (shieldDisableAttempts < 0L) {
            shieldDisableAttempts = 0L;
        }

        if (shieldDisableSuccesses < 0L) {
            shieldDisableSuccesses = 0L;
        }

        if (shieldDisableSuccesses > shieldDisableAttempts) {
            shieldDisableSuccesses = shieldDisableAttempts;
        }

        if (!stunning) {
            stunWeb = StunWebMode.OFF;
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

        if (maxAttackRange < 2.0) {
            maxAttackRange = 2.0;
        } else if (maxAttackRange > 3.0) {
            maxAttackRange = 3.0;
        }

        if (postAttackGuardTicks < 1) {
            postAttackGuardTicks = 1;
        } else if (postAttackGuardTicks > 20) {
            postAttackGuardTicks = 20;
        }

        if (missChancePercent < 0) {
            missChancePercent = 0;
        } else if (missChancePercent > 50) {
            missChancePercent = 50;
        }

        if (shieldMissChancePercent < 0) {
            shieldMissChancePercent = 0;
        } else if (shieldMissChancePercent > 50) {
            shieldMissChancePercent = 50;
        }
    }

    public void cycleMissChance() {
        missChancePercent += 5;
        if (missChancePercent > 50) {
            missChancePercent = 0;
        }
        clamp();
    }

    public void cycleShieldMissChance() {
        shieldMissChancePercent += 5;
        if (shieldMissChancePercent > 50) {
            shieldMissChancePercent = 0;
        }
        clamp();
    }

    private static int clampDelay(int delayMillis) {
        if (delayMillis < 0) {
            return 0;
        }

        return Math.min(delayMillis, 250);
    }
}
