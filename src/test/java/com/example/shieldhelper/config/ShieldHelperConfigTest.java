package com.example.shieldhelper.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShieldHelperConfigTest {
    @Test
    void disableDistanceAdjustsByTenthsAndClamps() {
        ShieldHelperConfig config = new ShieldHelperConfig();

        config.disableDistanceBlocks = 3.0D;
        config.adjustDisableDistanceTenths(1);
        assertEquals(3.1D, config.disableDistanceBlocks);

        config.adjustDisableDistanceTenths(-20);
        assertEquals(ShieldHelperConfig.MIN_DISABLE_DISTANCE_BLOCKS, config.disableDistanceBlocks);

        config.adjustDisableDistanceTenths(30);
        assertEquals(ShieldHelperConfig.MAX_DISABLE_DISTANCE_BLOCKS, config.disableDistanceBlocks);
    }

    @Test
    void trustedServersMatchPortsExactly() {
        ShieldHelperConfig config = new ShieldHelperConfig();

        config.toggleTrustedServer("Example.com:25565");

        assertTrue(config.isTrustedServer("example.com:25565"));
        assertFalse(config.isTrustedServer("example.com:25566"));
        assertFalse(config.isTrustedServer("example.com"));
    }

    @Test
    void trustedServersAreNormalizedAndDeduplicated() {
        ShieldHelperConfig config = new ShieldHelperConfig();

        config.toggleTrustedServer(" Example.COM:25565 ");
        config.toggleTrustedServer("example.com:25565");

        assertFalse(config.isTrustedServer("example.com:25565"));
        assertEquals(0, config.trustedServers.size());
    }
}
