package com.example.shieldhelper.client;

import com.example.shieldhelper.ShieldHelperMod;
import com.example.shieldhelper.config.ShieldHelperConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@Environment(EnvType.CLIENT)
public final class ShieldDisableTrigger {
    private static final int SUCCESS_CHECK_DELAY_MILLIS = 100;
    private static final int WEB_LANDING_PREDICTION_TICKS = 16;
    private static final int WEB_LANDING_GROUND_SEARCH_BLOCKS = 8;
    private static final double WEB_PREDICTION_GRAVITY = 0.08D;
    private static final double WEB_PREDICTION_VERTICAL_DRAG = 0.98D;
    private static final double WEB_PREDICTION_HORIZONTAL_DRAG = 0.91D;
    private static final double WEB_MAX_ROTATION_DELTA_DEGREES = 90.0D;
    private static final double WEB_BETWEEN_TARGET_TOLERANCE_BLOCKS = 0.75D;
    private static final double WEB_MAX_LINE_OFFSET_BLOCKS = 1.25D;
    private static final int MIN_VISIBLE_WEB_SWITCH_MILLIS = 50;
    private static final Direction[] WEB_PLACEMENT_DIRECTIONS = {
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.DOWN
    };

    private static int triggerCooldownTicks;
    private static boolean sequenceInProgress;
    private static long currentSequenceId;
    private static boolean isMacroAttacking;
    private static boolean toggleKeyWasDown;

    public static boolean isSequenceInProgress() {
        return sequenceInProgress;
    }

    private static int getPingAdjustedMillis(Minecraft client, int baseDelayMillis) {
        int delay = baseDelayMillis;
        if (ShieldHelperConfig.get().pingCompensation && client.getConnection() != null && client.player != null) {
            net.minecraft.client.multiplayer.PlayerInfo playerInfo = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (playerInfo != null) {
                delay += (playerInfo.getLatency() / 2);
            }
        }
        return delay;
    }

    private static int getStunWebDelayMillis(int baseDelayMillis) {
        return Math.max(0, baseDelayMillis);
    }

    private static void triggerPostAttackGuard(Minecraft client, int guardTicks) {
        LocalPlayer player = client.player;
        if (player == null || !player.getOffhandItem().is(Items.SHIELD)) {
            return;
        }

        if (client.options != null && client.options.keyUse != null) {
            client.options.keyUse.setDown(true);
            scheduleAsync(client, guardTicks * 50, () -> {
                if (client.options != null && client.options.keyUse != null) {
                    boolean isPhysicallyDown = isKeyMappingPhysicallyDown(client, client.options.keyUse);
                    client.options.keyUse.setDown(isPhysicallyDown);
                }
            });
        }
    }

    private static boolean isKeyMappingPhysicallyDown(Minecraft client, net.minecraft.client.KeyMapping keyMapping) {
        if (client == null || keyMapping == null) {
            return false;
        }
        try {
            java.lang.reflect.Field keyField = net.minecraft.client.KeyMapping.class.getDeclaredField("key");
            keyField.setAccessible(true);
            InputConstants.Key key = (InputConstants.Key) keyField.get(keyMapping);
            long windowHandle = client.getWindow().handle();
            if (key.getType() == InputConstants.Type.KEYSYM) {
                return InputConstants.isKeyDown(client.getWindow(), key.getValue());
            } else if (key.getType() == InputConstants.Type.MOUSE) {
                return org.lwjgl.glfw.GLFW.glfwGetMouseButton(windowHandle, key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            }
        } catch (Exception exception) {
            ShieldHelperMod.LOGGER.warn("Failed to check physical key state via reflection.", exception);
        }
        return false;
    }

    private ShieldDisableTrigger() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ShieldDisableTrigger::tick);
        ClientPreAttackCallback.EVENT.register(ShieldDisableTrigger::onPreAttack);
        AttackEntityCallback.EVENT.register(ShieldDisableTrigger::onAttackEntity);
    }

    private static void tick(Minecraft client) {
        if (triggerCooldownTicks > 0) {
            triggerCooldownTicks--;
        }

        ShieldHelperConfig config = ShieldHelperConfig.get();
        handleMacroToggleHotkey(client, config);
    }

    private static boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount) {
        if (sequenceInProgress) {
            playLocalMainHandSwing(player);
            return true;
        }

        // Trigger on any attack attempt (click or hold) if the target is blocking
        return tryStartSequence(client, player, getCrosshairEntity(client));
    }

    private static InteractionResult onAttackEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        Minecraft client = Minecraft.getInstance();
        if (!(player instanceof LocalPlayer localPlayer) || player != client.player || level != client.level) {
            return InteractionResult.PASS;
        }

        if (isMacroAttacking || sequenceInProgress) {
            return InteractionResult.PASS;
        }

        return tryStartSequence(client, localPlayer, entity) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private static boolean tryStartSequence(Minecraft client, LocalPlayer player, Entity target) {
        ShieldHelperConfig config = ShieldHelperConfig.get();
        if (!config.enabled || sequenceInProgress || triggerCooldownTicks > 0) {
            return false;
        }

        if (config.missPercentage > 0 && ThreadLocalRandom.current().nextInt(100) < config.missPercentage) {
            triggerCooldownTicks = Math.max(2, config.attackCooldownTicks); // Set short cooldown on missed triggers
            return false;
        }

        if (!ShieldHelperSafety.isActionAllowed(client, config)) {
            return false;
        }

        if (client.screen != null || player == null || client.level == null || client.gameMode == null) {
            return false;
        }

        if (!client.gameMode.canHurtPlayer()) {
            return false;
        }

        if (isShieldBlocking(player)) {
            return false;
        }

        if (!(target instanceof Player targetPlayer) || targetPlayer == player || !targetPlayer.isAlive() || !targetPlayer.isAttackable() || !targetPlayer.isPickable()) {
            return false;
        }

        if (!isReachableEntity(player, targetPlayer)) {
            return false;
        }

        if (!isShieldEffectivelyBlocking(player, targetPlayer)) {
            return false;
        }

        if (config.requireAttackCooldown && player.getAttackStrengthScale(0.0F) * 100.0F < config.minimumAttackStrengthPercent) {
            return false;
        }

        int axeSlot = findHotbarAxe(player.getInventory());
        if (axeSlot == Inventory.NOT_FOUND_INDEX) {
            return false;
        }

        int previousSlot = player.getInventory().getSelectedSlot();
        TriggerSettings settings = TriggerSettings.from(config);
        
        currentSequenceId++;
        long seqId = currentSequenceId;
        sequenceInProgress = true;
        playLocalMainHandSwing(player);
        
        int swapDelay = getPingAdjustedMillis(client, config.swapDelayMillis);
        if (swapDelay <= 0) {
            runSequenceStep(seqId, () -> swapToAxe(client, targetPlayer, previousSlot, axeSlot, settings, seqId));
        } else {
            scheduleSequenceStep(client, swapDelay, seqId, () -> swapToAxe(client, targetPlayer, previousSlot, axeSlot, settings, seqId));
        }
        return true;
    }

    private static void handleMacroToggleHotkey(Minecraft client, ShieldHelperConfig config) {
        if (!config.hotkeyToggleEnabled || client.screen != null) {
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

    private static void swapToAxe(Minecraft client, Player target, int previousSlot, int axeSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinue(client, target, true) || player == null || !isAxeInSlot(player.getInventory(), axeSlot)) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        boolean swapped = false;
        if (player.getInventory().getSelectedSlot() != axeSlot) {
            if (!selectSlot(player, axeSlot)) {
                finishSequence(client, previousSlot, axeSlot, settings, seqId);
                return;
            }
            swapped = true;
        }

        int firstAttackDelay = getPingAdjustedMillis(client, settings.firstAttackDelayMillis());
        if (swapped) {
            firstAttackDelay = Math.max(20, firstAttackDelay); // Safe sub-tick buffer after swap
        }
        
        if (firstAttackDelay <= 0) {
            runSequenceStep(seqId, () -> performFirstAttack(client, target, previousSlot, axeSlot, settings, seqId));
        } else {
            scheduleSequenceStep(client, firstAttackDelay, seqId, () -> performFirstAttack(client, target, previousSlot, axeSlot, settings, seqId));
        }
    }

    private static void performFirstAttack(Minecraft client, Player target, int previousSlot, int axeSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinue(client, target, true) || player == null || !isAxeInSlot(player.getInventory(), axeSlot)) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        if (player.getInventory().getSelectedSlot() != axeSlot && !selectSlot(player, axeSlot)) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        boolean attacked = false;
        isMacroAttacking = true;
        try {
            attacked = performNormalAttack(client, player, target, axeSlot, true);
        } finally {
            isMacroAttacking = false;
        }
        if (!attacked) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }
        
        triggerCooldownTicks = settings.attackCooldownTicks();
        scheduleAsync(client, SUCCESS_CHECK_DELAY_MILLIS, () -> recordDisableResult(client, target));

        if (settings.stunning()) {
            int secondClickDelay = getSecondClickDelayMillis(client, settings);
            if (secondClickDelay <= 0) {
                runSequenceStep(seqId, () -> performStunClick(client, target, previousSlot, axeSlot, settings, seqId));
            } else {
                scheduleSequenceStep(client, secondClickDelay, seqId, () -> performStunClick(client, target, previousSlot, axeSlot, settings, seqId));
            }
        } else {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
        }
    }

    private static int getSecondClickDelayMillis(Minecraft client, TriggerSettings settings) {
        int stunDelayMillis = ThreadLocalRandom.current().nextInt(settings.stunningMinDelayMillis(), settings.stunningMaxDelayMillis() + 1);
        return getPingAdjustedMillis(client, stunDelayMillis);
    }

    private static void recordDisableResult(Minecraft client, Player target) {
        if (client.level == null || target == null) {
            return;
        }

        if (target.level() != client.level && !target.isRemoved()) {
            return;
        }

        boolean success = target.isRemoved()
                || !target.isAlive()
                || !isShieldBlocking(target);
        ShieldHelperConfig.get().recordShieldDisableResult(success);
        ShieldHelperConfig.save();
    }

    private static void performStunClick(Minecraft client, Player target, int previousSlot, int axeSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;

        LocalPlayer player = client.player;
        int stunSlot = getStunSlot(player, previousSlot, axeSlot, settings);
        if (!canContinue(client, target, false) || player == null || stunSlot == Inventory.NOT_FOUND_INDEX) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        executeStunAttack(client, target, previousSlot, axeSlot, stunSlot, settings, seqId);
    }

    private static void executeStunAttack(Minecraft client, Player target, int previousSlot, int axeSlot, int stunSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinue(client, target, false) || player == null) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        if (player.getInventory().getSelectedSlot() != stunSlot && !selectSlot(player, stunSlot)) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        boolean attacked = false;
        isMacroAttacking = true;
        try {
            attacked = performNormalAttack(client, player, target, stunSlot, false);
        } finally {
            isMacroAttacking = false;
        }
        if (!attacked) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        if (settings.stunWeb() != ShieldHelperConfig.StunWebMode.OFF) {
            int webDelay = getStunWebDelayMillis(settings.stunWebDelayMillis());
            scheduleSequenceStep(client, webDelay, seqId, () -> performStunWeb(client, target, previousSlot, axeSlot, settings, seqId));
        } else {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
        }
    }

    private static void performStunWeb(Minecraft client, Player target, int previousSlot, int restoreSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        int webSlot = player == null ? Inventory.NOT_FOUND_INDEX : findHotbarCobweb(player.getInventory());
        if (!canContinueForWeb(client, target) || player == null || webSlot == Inventory.NOT_FOUND_INDEX || !isCobwebInSlot(player.getInventory(), webSlot)) {
            finishSequence(client, previousSlot, restoreSlot, settings, seqId);
            return;
        }

        BlockHitResult hitResult = findBestWebPlacementHit(client, target, settings);
        if (hitResult == null) {
            finishSequence(client, previousSlot, restoreSlot, settings, seqId);
            return;
        }

        executeWebPlacement(client, target, hitResult, previousSlot, restoreSlot, webSlot, settings, seqId);
    }

    private static void executeWebPlacement(Minecraft client, Player target, BlockHitResult hitResult, int previousSlot, int restoreSlot, int webSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinueForWeb(client, target) || player == null || !isCobwebInSlot(player.getInventory(), webSlot)) {
            finishSequence(client, previousSlot, restoreSlot, settings, false, false, seqId);
            return;
        }

        if (!isReachableBlockHit(player, hitResult)) {
            finishSequence(client, previousSlot, restoreSlot, settings, false, false, seqId);
            return;
        }

        if (player.getInventory().getSelectedSlot() != webSlot) {
            if (!selectSlot(player, webSlot)) {
                finishSequence(client, previousSlot, restoreSlot, settings, false, false, seqId);
                return;
            }
        }

        if (!performValidatedWebPlacement(client, player, target, hitResult, webSlot)) {
            finishSequence(client, previousSlot, restoreSlot, settings, false, false, seqId);
            return;
        }

        int restoreAfterWebSlot = settings.switchBackAfterAttack() ? previousSlot : restoreSlot;
        if (player.getInventory().getSelectedSlot() == restoreAfterWebSlot) {
            completeSequence(seqId);
            return;
        }

        int restoreDelay = getVisibleWebRestoreDelayMillis(client, settings);
        scheduleSequenceStep(client, restoreDelay, seqId, () -> finishWebPlacement(client, webSlot, restoreAfterWebSlot, seqId));
    }

    private static int getVisibleWebRestoreDelayMillis(Minecraft client, TriggerSettings settings) {
        int restoreDelay = Math.max(MIN_VISIBLE_WEB_SWITCH_MILLIS, settings.switchBackDelayMillis());
        return getPingAdjustedMillis(client, restoreDelay);
    }

    private static void finishWebPlacement(Minecraft client, int webSlot, int restoreAfterWebSlot, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;

        LocalPlayer player = client.player;
        if (player != null && player.getInventory().getSelectedSlot() == webSlot) {
            selectSlot(player, restoreAfterWebSlot);
        }

        completeSequence(seqId);
    }

    private static void completeSequence(long seqId) {
        if (seqId == currentSequenceId) {
            sequenceInProgress = false;
        }
    }

    private static void finishSequence(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, long seqId) {
        finishSequence(client, previousSlot, axeSlot, settings, false, false, seqId);
    }

    private static void finishSequence(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, boolean forceSwitchBack, long seqId) {
        finishSequence(client, previousSlot, axeSlot, settings, forceSwitchBack, false, seqId);
    }

    private static void finishSequence(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, boolean forceSwitchBack, boolean thirdActionUsed, long seqId) {
        if (seqId != currentSequenceId) return;

        try {
            if ((settings.switchBackAfterAttack() || forceSwitchBack) && previousSlot != axeSlot) {
                int switchBackDelay = getPingAdjustedMillis(client, settings.switchBackDelayMillis());
                if (switchBackDelay <= 0) {
                    try {
                        restoreAndMaybeGuard(client, previousSlot, axeSlot, settings, thirdActionUsed);
                    } finally {
                        completeSequence(seqId);
                    }
                } else {
                    scheduleSequenceStep(client, switchBackDelay, seqId, () -> {
                        try {
                            restoreAndMaybeGuard(client, previousSlot, axeSlot, settings, thirdActionUsed);
                        } finally {
                            completeSequence(seqId);
                        }
                    });
                }
            } else {
                completeSequence(seqId);
                if (!thirdActionUsed && settings.postAttackGuard()) {
                    triggerPostAttackGuard(client, settings.postAttackGuardTicks());
                }
            }
        } catch (Exception e) {
            abortSequence(seqId, e);
        }
    }

    private static void restoreAndMaybeGuard(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, boolean thirdActionUsed) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        if (player.getInventory().getSelectedSlot() == axeSlot) {
            selectSlot(player, previousSlot);
        }

        if (!thirdActionUsed && settings.postAttackGuard()) {
            triggerPostAttackGuard(client, settings.postAttackGuardTicks());
        }
    }

    private static void scheduleAsync(Minecraft client, int delayMillis, Runnable action) {
        if (client == null || action == null) {
            return;
        }

        CompletableFuture.delayedExecutor(Math.max(1, delayMillis), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    try {
                        client.execute(() -> runScheduledAction(action));
                    } catch (RuntimeException exception) {
                        ShieldHelperMod.LOGGER.warn("Failed to schedule Shield Helper action.", exception);
                    }
                });
    }

    private static void scheduleSequenceStep(Minecraft client, int delayMillis, long seqId, Runnable action) {
        if (client == null || action == null) {
            return;
        }

        CompletableFuture.delayedExecutor(Math.max(1, delayMillis), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    try {
                        client.execute(() -> runSequenceStep(seqId, action));
                    } catch (RuntimeException exception) {
                        abortSequence(seqId, exception);
                    }
                });
    }

    private static void runScheduledAction(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            ShieldHelperMod.LOGGER.warn("Shield Helper delayed action failed.", exception);
        }
    }

    private static void runSequenceStep(long seqId, Runnable action) {
        if (seqId != currentSequenceId || !sequenceInProgress) {
            return;
        }

        try {
            action.run();
        } catch (RuntimeException exception) {
            abortSequence(seqId, exception);
        }
    }

    private static void abortSequence(long seqId, Exception exception) {
        if (seqId == currentSequenceId) {
            sequenceInProgress = false;
        }

        ShieldHelperMod.LOGGER.warn("Shield Helper sequence aborted.", exception);
    }

    private static boolean canContinue(Minecraft client, Player target, boolean requireShieldBlocking) {
        ShieldHelperConfig config = ShieldHelperConfig.get();
        if (!config.enabled || !ShieldHelperSafety.isActionAllowed(client, config) || client.screen != null || client.player == null || client.level == null || client.gameMode == null || !client.gameMode.canHurtPlayer()) {
            return false;
        }

        if (client.player.connection == null || target == null) {
            return false;
        }

        if (isShieldBlocking(client.player)) {
            return false;
        }

        if (target.isRemoved() || !target.isAlive() || !target.isAttackable() || !target.isPickable() || target.level() != client.level) {
            return false;
        }

        if (!isReachableEntity(client.player, target)) {
            return false;
        }

        if (!isAimingAtEntity(client, client.player, target)) {
            return false;
        }

        return !requireShieldBlocking || isShieldEffectivelyBlocking(client.player, target);
    }

    private static boolean isShieldEffectivelyBlocking(Player attacker, Player target) {
        if (!isShieldBlocking(target)) {
            return false;
        }

        Vec3 attackerPos = attacker.position();
        Vec3 targetPos = target.position();
        Vec3 directionToAttacker = attackerPos.subtract(targetPos).normalize();
        Vec3 targetLook = target.getViewVector(1.0F);

        // Vanilla check: Dot product > 0 means the attacker is in the front 180-degree arc of the target
        return targetLook.dot(directionToAttacker) > 0;
    }

    private static boolean canContinueForWeb(Minecraft client, Player target) {
        ShieldHelperConfig config = ShieldHelperConfig.get();
        if (!config.enabled || !ShieldHelperSafety.isActionAllowed(client, config) || client.screen != null || client.player == null || client.level == null || client.gameMode == null || !client.gameMode.canHurtPlayer()) {
            return false;
        }

        if (client.player.connection == null || target == null) {
            return false;
        }

        if (isShieldBlocking(client.player)) {
            return false;
        }

        return !target.isRemoved() && target.isAlive() && target.level() == client.level;
    }

    private static BlockHitResult findBestWebPlacementHit(Minecraft client, Player target, TriggerSettings settings) {
        BlockPos targetFeetWebPos = getTargetFeetWebPos(target);
        BlockHitResult targetFeetHit = findWebPlacementHit(client, target, targetFeetWebPos, settings);
        if (targetFeetHit != null) {
            return targetFeetHit;
        }

        BlockPos predictedWebPos = predictLandingWebPos(client, target);
        if (predictedWebPos != null && !predictedWebPos.equals(targetFeetWebPos)) {
            BlockHitResult predictedHit = findWebPlacementHit(client, target, predictedWebPos, settings);
            if (predictedHit != null) {
                return predictedHit;
            }
        }

        return null;
    }

    private static BlockPos getTargetFeetWebPos(Player target) {
        Vec3 targetFeet = getTargetFeetPosition(target);
        return BlockPos.containing(targetFeet.x, targetFeet.y, targetFeet.z).immutable();
    }

    private static Vec3 getTargetFeetPosition(Player target) {
        return new Vec3(
                (target.getBoundingBox().minX + target.getBoundingBox().maxX) * 0.5D,
                target.getBoundingBox().minY,
                (target.getBoundingBox().minZ + target.getBoundingBox().maxZ) * 0.5D
        );
    }

    private static BlockHitResult findWebPlacementHit(Minecraft client, Player target, BlockPos webPos, TriggerSettings settings) {
        LocalPlayer player = client.player;
        if (client.level == null || player == null || !isWebBetweenPlayerAndTarget(player, target, webPos)) {
            return null;
        }

        if (client.hitResult instanceof BlockHitResult blockHitResult
                && blockHitResult.getType() == HitResult.Type.BLOCK
                && isValidWebPlacementHit(client, blockHitResult, webPos)
                && isRotationPossibleForWeb(client, player, blockHitResult)) {
            return blockHitResult;
        }

        return findLegitWebPlacementHit(client, webPos);
    }

    private static BlockPos predictLandingWebPos(Minecraft client, Player target) {
        if (client.level == null) {
            return null;
        }

        Vec3 position = getTargetFeetPosition(target);
        Vec3 velocity = target.getDeltaMovement();
        for (int tick = 1; tick <= WEB_LANDING_PREDICTION_TICKS; tick++) {
            position = position.add(velocity);
            if (velocity.y <= 0.0D) {
                BlockPos simulatedFeet = BlockPos.containing(position.x, position.y, position.z);
                BlockPos landingPos = findLandingWebCandidate(client, simulatedFeet);
                if (landingPos != null) {
                    return landingPos.immutable();
                }
            }

            velocity = new Vec3(
                    velocity.x * WEB_PREDICTION_HORIZONTAL_DRAG,
                    (velocity.y - WEB_PREDICTION_GRAVITY) * WEB_PREDICTION_VERTICAL_DRAG,
                    velocity.z * WEB_PREDICTION_HORIZONTAL_DRAG
            );
        }

        return findNearestLandingWebCandidate(client, BlockPos.containing(position.x, position.y, position.z));
    }

    private static BlockPos findNearestLandingWebCandidate(Minecraft client, BlockPos startPos) {
        for (int blocksBelow = 0; blocksBelow <= WEB_LANDING_GROUND_SEARCH_BLOCKS; blocksBelow++) {
            BlockPos landingPos = findLandingWebCandidate(client, startPos.below(blocksBelow));
            if (landingPos != null) {
                return landingPos.immutable();
            }
        }

        return null;
    }

    private static BlockPos findLandingWebCandidate(Minecraft client, BlockPos feetPos) {
        BlockPos landingPos = normalizeLandingWebPos(client, feetPos);
        if (landingPos != null) {
            return landingPos;
        }

        return normalizeLandingWebPos(client, feetPos.above());
    }

    private static BlockPos normalizeLandingWebPos(Minecraft client, BlockPos webPos) {
        if (!isLoadedWorldPos(client, webPos) || !canPlaceCobwebAt(client, webPos) || !isLandingSupport(client, webPos.below())) {
            return null;
        }

        return webPos;
    }

    private static boolean isLandingSupport(Minecraft client, BlockPos pos) {
        if (!isLoadedWorldPos(client, pos)) {
            return false;
        }

        BlockState state = client.level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }

    private static boolean isLoadedWorldPos(Minecraft client, BlockPos pos) {
        return client.level != null && client.level.isInWorldBounds(pos) && client.level.isLoaded(pos);
    }

    private static boolean canPlaceCobwebAt(Minecraft client, BlockPos webPos) {
        if (client == null || client.level == null || client.player == null || webPos == null) {
            return false;
        }

        if (!client.level.isInWorldBounds(webPos) || !client.level.isLoaded(webPos) || !client.level.mayInteract(client.player, webPos)) {
            return false;
        }

        BlockState placementState = client.level.getBlockState(webPos);
        return !placementState.is(Blocks.COBWEB) && placementState.canBeReplaced();
    }

    private static boolean isValidWebPlacementHit(Minecraft client, BlockHitResult hitResult, BlockPos webPos) {
        if (!canPlaceCobwebAt(client, webPos)) {
            return false;
        }

        BlockPos expectedPlacementPos = getExpectedPlacementPos(client, hitResult);
        return webPos.equals(expectedPlacementPos) && isReachableBlockHit(client.player, hitResult);
    }

    private static BlockPos getExpectedPlacementPos(Minecraft client, BlockHitResult hitResult) {
        if (client == null || client.level == null || hitResult == null) {
            return null;
        }

        BlockPos clickedPos = hitResult.getBlockPos();
        if (!client.level.isInWorldBounds(clickedPos) || !client.level.isLoaded(clickedPos)) {
            return null;
        }

        BlockState clickedState = client.level.getBlockState(clickedPos);
        if (!clickedState.canBeReplaced() && clickedState.isAir()) {
            return null;
        }

        return clickedState.canBeReplaced() ? clickedPos : clickedPos.relative(hitResult.getDirection());
    }

    private static boolean isReachableBlockHit(LocalPlayer player, BlockHitResult hitResult) {
        if (player == null) return false;
        double interactionRange = player.blockInteractionRange();
        // Keep placement comfortably inside vanilla reach.
        double maxDist = interactionRange - 0.05;
        if (maxDist <= 0.0D) {
            return false;
        }
        return player.getEyePosition().distanceToSqr(hitResult.getLocation()) <= maxDist * maxDist;
    }

    private static BlockHitResult findLegitWebPlacementHit(Minecraft client, BlockPos webPos) {
        LocalPlayer player = client.player;
        if (client.level == null || player == null || !canPlaceCobwebAt(client, webPos)) {
            return null;
        }

        BlockHitResult bestHit = null;
        double bestRotationDelta = Double.POSITIVE_INFINITY;
        for (Direction direction : WEB_PLACEMENT_DIRECTIONS) {
            BlockPos clickedPos = webPos.relative(direction.getOpposite());
            if (!client.level.isInWorldBounds(clickedPos) || !client.level.isLoaded(clickedPos)) {
                continue;
            }

            BlockState clickedState = client.level.getBlockState(clickedPos);
            if (clickedState.canBeReplaced() || clickedState.isAir()) {
                continue;
            }

            Vec3 hitLocation = Vec3.atCenterOf(clickedPos).add(
                    direction.getStepX() * 0.5D,
                    direction.getStepY() * 0.5D,
                    direction.getStepZ() * 0.5D
            );
            BlockHitResult hitResult = new BlockHitResult(hitLocation, direction, clickedPos, false);
            if (!isValidWebPlacementHit(client, hitResult, webPos) || !isRotationPossibleForWeb(client, player, hitResult)) {
                continue;
            }

            double rotationDelta = getRotationDeltaDegrees(player, hitLocation);
            if (rotationDelta < bestRotationDelta) {
                bestRotationDelta = rotationDelta;
                bestHit = hitResult;
            }
        }

        return bestHit;
    }

    private static boolean isWebBetweenPlayerAndTarget(LocalPlayer player, Player target, BlockPos webPos) {
        Vec3 playerPos = getTargetFeetPosition(player);
        Vec3 targetPos = getTargetFeetPosition(target);
        Vec3 webCenter = Vec3.atCenterOf(webPos);
        double toTargetX = targetPos.x - playerPos.x;
        double toTargetZ = targetPos.z - playerPos.z;
        double targetDistance = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
        if (targetDistance <= 1.0E-5D) {
            return false;
        }

        double toWebX = webCenter.x - playerPos.x;
        double toWebZ = webCenter.z - playerPos.z;
        double projection = (toWebX * toTargetX + toWebZ * toTargetZ) / targetDistance;
        if (projection < -WEB_BETWEEN_TARGET_TOLERANCE_BLOCKS || projection > targetDistance + 3.0D) {
            return false;
        }

        double perpendicularX = toWebX - toTargetX / targetDistance * projection;
        double perpendicularZ = toWebZ - toTargetZ / targetDistance * projection;
        return perpendicularX * perpendicularX + perpendicularZ * perpendicularZ <= WEB_MAX_LINE_OFFSET_BLOCKS * WEB_MAX_LINE_OFFSET_BLOCKS;
    }

    private static boolean isRotationPossibleForWeb(Minecraft client, LocalPlayer player, BlockHitResult hitResult) {
        if (client.level == null || player == null || hitResult == null) {
            return false;
        }

        Vec3 hitLocation = hitResult.getLocation();
        if (getRotationDeltaDegrees(player, hitLocation) > WEB_MAX_ROTATION_DELTA_DEGREES) {
            return false;
        }

        Vec3 eyePosition = player.getEyePosition();
        BlockHitResult lineOfSight = client.level.clip(new ClipContext(
                eyePosition,
                hitLocation,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return lineOfSight.getType() != HitResult.Type.BLOCK || lineOfSight.getBlockPos().equals(hitResult.getBlockPos());
    }

    private static double getRotationDeltaDegrees(LocalPlayer player, Vec3 hitLocation) {
        Vec3 delta = hitLocation.subtract(player.getEyePosition());
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D;
        double pitch = -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || pitch < -90.0D || pitch > 90.0D) {
            return Double.POSITIVE_INFINITY;
        }

        double yawDelta = wrapDegrees(yaw - player.getYRot());
        double pitchDelta = pitch - player.getXRot();
        return Math.hypot(yawDelta, pitchDelta);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        } else if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }

    private static boolean isReachableEntity(LocalPlayer player, Entity target) {
        if (player == null || target == null) return false;
        double interactionRange = player.entityInteractionRange();
        double maxAttackRange = ShieldHelperConfig.get().maxAttackRange;
        double maxDist = Math.min(interactionRange, maxAttackRange) - 0.05;
        if (maxDist <= 0.0D) {
            return false;
        }
        return target.getBoundingBox().distanceToSqr(player.getEyePosition()) <= maxDist * maxDist;
    }

    private static boolean isAimingAtEntity(Minecraft client, LocalPlayer player, Entity target) {
        if (client == null || client.level == null || player == null || target == null) {
            return false;
        }

        double interactionRange = player.entityInteractionRange();
        Vec3 eyePosition = player.getEyePosition();
        Vec3 lookVector = player.getViewVector(1.0F);
        Vec3 lookEnd = eyePosition.add(lookVector.scale(interactionRange));
        
        Optional<Vec3> entityHit = target.getBoundingBox().inflate(target.getPickRadius()).clip(eyePosition, lookEnd);
        if (entityHit.isEmpty()) {
            return false;
        }

        double entityHitDistance = eyePosition.distanceToSqr(entityHit.get());
        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eyePosition,
                entityHit.get(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));

        return blockHit.getType() != HitResult.Type.BLOCK
                || eyePosition.distanceToSqr(blockHit.getLocation()) + 1.0E-5D >= entityHitDistance;
    }

    private static Entity getCrosshairEntity(Minecraft client) {
        if (client.hitResult instanceof EntityHitResult entityHitResult) {
            return entityHitResult.getEntity();
        }

        return null;
    }

    private static boolean isShieldBlocking(Player player) {
        return player.isBlocking() && player.getUseItem().is(Items.SHIELD);
    }

    private static int findHotbarAxe(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (isAxeInSlot(inventory, slot)) {
                return slot;
            }
        }

        return Inventory.NOT_FOUND_INDEX;
    }

    private static int getStunSlot(LocalPlayer player, int previousSlot, int axeSlot, TriggerSettings settings) {
        if (player == null) {
            return Inventory.NOT_FOUND_INDEX;
        }

        if (settings.switchBackAfterAttack()) {
            return Inventory.isHotbarSlot(previousSlot) ? previousSlot : Inventory.NOT_FOUND_INDEX;
        }

        return isAxeInSlot(player.getInventory(), axeSlot) ? axeSlot : Inventory.NOT_FOUND_INDEX;
    }

    private static int findHotbarCobweb(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (isCobwebInSlot(inventory, slot)) {
                return slot;
            }
        }

        return Inventory.NOT_FOUND_INDEX;
    }

    private static boolean isAxeInSlot(Inventory inventory, int slot) {
        return Inventory.isHotbarSlot(slot) && inventory.getItem(slot).is(ItemTags.AXES);
    }

    private static boolean isCobwebInSlot(Inventory inventory, int slot) {
        return Inventory.isHotbarSlot(slot) && inventory.getItem(slot).is(Items.COBWEB);
    }

    private static void restorePreviousSlotIfNeeded(LocalPlayer player, int previousSlot, TriggerSettings settings) {
        if (settings.switchBackAfterAttack() && player != null && Inventory.isHotbarSlot(previousSlot) && player.getInventory().getSelectedSlot() != previousSlot) {
            selectSlot(player, previousSlot);
        }
    }

    private static boolean selectSlot(LocalPlayer player, int slot) {
        if (!canSendSlotSelection(player, slot)) {
            return false;
        }

        if (player.getInventory().getSelectedSlot() == slot) {
            return true;
        }

        int previousSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(slot);
        if (player.getInventory().getSelectedSlot() != slot) {
            return false;
        }

        resetEquipProgress(Minecraft.getInstance());

        try {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        } catch (RuntimeException exception) {
            player.getInventory().setSelectedSlot(previousSlot);
            resetEquipProgress(Minecraft.getInstance());
            ShieldHelperMod.LOGGER.warn("Skipped Shield Helper slot packet after send failure.", exception);
            return false;
        }
        return true;
    }

    private static void resetEquipProgress(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        try {
            net.minecraft.client.renderer.ItemInHandRenderer renderer = getItemInHandRenderer(client);
            if (renderer == null) {
                return;
            }
            java.lang.reflect.Field mainHandItemField = net.minecraft.client.renderer.ItemInHandRenderer.class.getDeclaredField("mainHandItem");
            java.lang.reflect.Field mainHandHeightField = net.minecraft.client.renderer.ItemInHandRenderer.class.getDeclaredField("mainHandHeight");
            java.lang.reflect.Field oMainHandHeightField = net.minecraft.client.renderer.ItemInHandRenderer.class.getDeclaredField("oMainHandHeight");
            
            mainHandItemField.setAccessible(true);
            mainHandHeightField.setAccessible(true);
            oMainHandHeightField.setAccessible(true);
            
            mainHandItemField.set(renderer, client.player.getMainHandItem());
            mainHandHeightField.setFloat(renderer, 1.0F);
            oMainHandHeightField.setFloat(renderer, 1.0F);
        } catch (Exception exception) {
            ShieldHelperMod.LOGGER.warn("Failed to reset item equip progress via reflection.", exception);
        }
    }

    private static net.minecraft.client.renderer.ItemInHandRenderer getItemInHandRenderer(Minecraft client) {
        if (client == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = net.minecraft.client.renderer.GameRenderer.class.getDeclaredField("itemInHandRenderer");
            field.setAccessible(true);
            return (net.minecraft.client.renderer.ItemInHandRenderer) field.get(client.gameRenderer);
        } catch (Exception e) {
            ShieldHelperMod.LOGGER.warn("Failed to get itemInHandRenderer via reflection.", e);
            return null;
        }
    }

    private static boolean canSendSlotSelection(LocalPlayer player, int slot) {
        return player != null
                && player.connection != null
                && Inventory.isHotbarSlot(slot);
    }

    private static boolean performNormalAttack(Minecraft client, LocalPlayer player, Player target, int expectedSlot, boolean requireShieldBlocking) {
        if (client == null || player == null || target == null || player != client.player || player.connection == null || !canContinue(client, target, requireShieldBlocking)) {
            return false;
        }

        if (!Inventory.isHotbarSlot(expectedSlot) || player.getInventory().getSelectedSlot() != expectedSlot) {
            return false;
        }

        if (requireShieldBlocking && !isAxeInSlot(player.getInventory(), expectedSlot)) {
            return false;
        }

        swingMainHand(player);
        client.gameMode.attack(player, target);
        return true;
    }

    private static boolean performValidatedWebPlacement(Minecraft client, LocalPlayer player, Player target, BlockHitResult hitResult, int webSlot) {
        if (client == null || player == null || target == null || player != client.player || player.connection == null || !canContinueForWeb(client, target)) {
            return false;
        }

        if (!isCobwebInSlot(player.getInventory(), webSlot) || player.getInventory().getSelectedSlot() != webSlot) {
            return false;
        }

        BlockPos webPos = getExpectedPlacementPos(client, hitResult);
        if (webPos == null
                || !isWebBetweenPlayerAndTarget(player, target, webPos)
                || !isValidWebPlacementHit(client, hitResult, webPos)
                || !isRotationPossibleForWeb(client, player, hitResult)) {
            return false;
        }

        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        if (result.consumesAction()) {
            swingMainHand(player);
            return true;
        }

        return false;
    }

    private static void swingMainHand(LocalPlayer player) {
        player.swing(InteractionHand.MAIN_HAND);
    }

    private static void playLocalMainHandSwing(LocalPlayer player) {
        if (player != null) {
            player.swing(InteractionHand.MAIN_HAND, false);
        }
    }

    private record TriggerSettings(
            boolean switchBackAfterAttack,
            boolean stunning,
            ShieldHelperConfig.StunWebMode stunWeb,
            int attackCooldownTicks,
            int firstAttackDelayMillis,
            int stunningMinDelayMillis,
            int stunningMaxDelayMillis,
            int stunWebDelayMillis,
            int switchBackDelayMillis,
            boolean postAttackGuard,
            int postAttackGuardTicks,
            int missPercentage
    ) {
        private static TriggerSettings from(ShieldHelperConfig config) {
            return new TriggerSettings(
                    config.switchBackAfterAttack,
                    config.stunning,
                    config.stunning ? config.stunWeb : ShieldHelperConfig.StunWebMode.OFF,
                    config.attackCooldownTicks,
                    config.firstAttackDelayMillis,
                    Math.min(config.stunningMinDelayMillis, config.stunningMaxDelayMillis),
                    Math.max(config.stunningMinDelayMillis, config.stunningMaxDelayMillis),
                    config.stunWebDelayMillis,
                    config.switchBackDelayMillis,
                    config.postAttackGuard,
                    config.postAttackGuardTicks,
                    config.missPercentage
            );
        }
    }
}
