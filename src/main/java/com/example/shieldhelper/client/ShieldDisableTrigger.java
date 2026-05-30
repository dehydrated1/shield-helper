package com.example.shieldhelper.client;

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
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@Environment(EnvType.CLIENT)
public final class ShieldDisableTrigger {
    private static final int SUCCESS_CHECK_DELAY_MILLIS = 100;
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
        if (ShieldHelperConfig.get().blatantMode) {
            return 0;
        }
        int delay = baseDelayMillis;
        if (ShieldHelperConfig.get().pingCompensation && client.getConnection() != null && client.player != null) {
            net.minecraft.client.multiplayer.PlayerInfo playerInfo = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (playerInfo != null) {
                delay += (playerInfo.getLatency() / 2);
            }
        }
        return delay;
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
                    client.options.keyUse.setDown(false);
                }
            });
        }
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

        if (!config.blatantMode && config.requireAttackCooldown && player.getAttackStrengthScale(0.0F) * 100.0F < config.minimumAttackStrengthPercent) {
            return false;
        }

        int axeSlot = findHotbarAxe(player.getInventory());
        if (axeSlot == Inventory.NOT_FOUND_INDEX) {
            return false;
        }

        // Anti-suspicion: Roll shield disable miss chance
        if (config.shieldMissChancePercent > 0 && ThreadLocalRandom.current().nextInt(100) < config.shieldMissChancePercent) {
            triggerCooldownTicks = ShieldHelperConfig.get().blatantMode ? 0 : config.attackCooldownTicks;
            return false;
        }

        int previousSlot = player.getInventory().getSelectedSlot();
        TriggerSettings settings = TriggerSettings.from(config);
        
        // Sequence initialization with unique tracking ID
        currentSequenceId++;
        long seqId = currentSequenceId;
        sequenceInProgress = true;
        
        int swapDelay = getPingAdjustedMillis(client, config.swapDelayMillis);
        if (config.blatantMode || swapDelay <= 0) {
            swapToAxe(client, targetPlayer, previousSlot, axeSlot, settings, seqId);
        } else {
            scheduleAsync(client, swapDelay, () -> swapToAxe(client, targetPlayer, previousSlot, axeSlot, settings, seqId));
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
        
        if (ShieldHelperConfig.get().blatantMode || firstAttackDelay <= 0) {
            performFirstAttack(client, target, previousSlot, axeSlot, settings, seqId);
        } else {
            scheduleAsync(client, firstAttackDelay, () -> performFirstAttack(client, target, previousSlot, axeSlot, settings, seqId));
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

        isMacroAttacking = true;
        try {
            client.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        } finally {
            isMacroAttacking = false;
        }
        
        triggerCooldownTicks = ShieldHelperConfig.get().blatantMode ? 0 : settings.attackCooldownTicks();
        scheduleAsync(client, SUCCESS_CHECK_DELAY_MILLIS, () -> recordDisableResult(client, target));

        if (settings.stunning()) {
            int stunDelayMillis = ThreadLocalRandom.current().nextInt(settings.stunningMinDelayMillis(), settings.stunningMaxDelayMillis() + 1);
            int pingAdjustedDelay = getPingAdjustedMillis(client, stunDelayMillis);
            if (ShieldHelperConfig.get().blatantMode || pingAdjustedDelay <= 0) {
                performStunClick(client, target, previousSlot, axeSlot, settings, seqId);
            } else {
                scheduleAsync(client, pingAdjustedDelay, () -> performStunClick(client, target, previousSlot, axeSlot, settings, seqId));
            }
        } else {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
        }
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
        
        // Anti-suspicion: Roll miss chance for the extra click
        if (settings.missChancePercent() > 0 && ThreadLocalRandom.current().nextInt(100) < settings.missChancePercent()) {
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        LocalPlayer player = client.player;
        int stunSlot = getStunSlot(player, axeSlot, settings);
        if (!canContinue(client, target, false) || player == null || stunSlot == Inventory.NOT_FOUND_INDEX) {
            if (previousSlot != axeSlot) selectSlotSilent(client.player, previousSlot);
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        executeStunAttack(client, target, previousSlot, axeSlot, stunSlot, settings, seqId);
    }

    private static void executeStunAttack(Minecraft client, Player target, int previousSlot, int axeSlot, int stunSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinue(client, target, false) || player == null) {
            if (previousSlot != stunSlot) selectSlotSilent(player, previousSlot);
            finishSequence(client, previousSlot, axeSlot, settings, seqId);
            return;
        }

        boolean swapped = (previousSlot != stunSlot);
        if (swapped) {
            selectSlotSilent(player, stunSlot);
        }

        isMacroAttacking = true;
        try {
            client.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        } finally {
            isMacroAttacking = false;
        }

        if (swapped) {
            selectSlotSilent(player, previousSlot);
        }

        boolean forceSwitchBack = settings.smpProfile() && settings.stunning();
        if (settings.stunWeb() != ShieldHelperConfig.StunWebMode.OFF) {
            BlockPos webPos = BlockPos.containing(target.getX(), target.getY(), target.getZ()).immutable();
            int webDelay = getPingAdjustedMillis(client, settings.stunWebDelayMillis());
            if (ShieldHelperConfig.get().blatantMode || webDelay <= 0) {
                performStunWeb(client, target, webPos, previousSlot, axeSlot, settings, forceSwitchBack, seqId);
            } else {
                scheduleAsync(client, webDelay, () -> performStunWeb(client, target, webPos, previousSlot, axeSlot, settings, forceSwitchBack, seqId));
            }
        } else {
            finishSequence(client, previousSlot, axeSlot, settings, forceSwitchBack, seqId);
        }
    }

    private static void performStunWeb(Minecraft client, Player target, BlockPos webPos, int previousSlot, int restoreSlot, TriggerSettings settings, boolean forceSwitchBack, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        int webSlot = player == null ? Inventory.NOT_FOUND_INDEX : findHotbarCobweb(player.getInventory());
        if (!canContinueForWeb(client, target) || player == null || webSlot == Inventory.NOT_FOUND_INDEX || !isCobwebInSlot(player.getInventory(), webSlot)) {
            finishSequence(client, previousSlot, restoreSlot, settings, forceSwitchBack, seqId);
            return;
        }

        BlockHitResult hitResult = findWebPlacementHit(client, webPos);
        if (hitResult == null) {
            finishSequence(client, previousSlot, restoreSlot, settings, forceSwitchBack, seqId);
            return;
        }

        executeWebPlacement(client, target, hitResult, previousSlot, restoreSlot, webSlot, settings, seqId);
    }

    private static void executeWebPlacement(Minecraft client, Player target, BlockHitResult hitResult, int previousSlot, int restoreSlot, int webSlot, TriggerSettings settings, long seqId) {
        if (seqId != currentSequenceId || !sequenceInProgress) return;
        LocalPlayer player = client.player;
        if (!canContinueForWeb(client, target) || player == null || !isCobwebInSlot(player.getInventory(), webSlot)) {
            finishSequence(client, previousSlot, restoreSlot, settings, true, seqId);
            return;
        }

        if (!isReachableBlockHit(player, hitResult)) {
            finishSequence(client, previousSlot, restoreSlot, settings, true, seqId);
            return;
        }

        boolean swapped = (previousSlot != webSlot);
        if (swapped) {
            selectSlotSilent(player, webSlot);
        }

        if (ShieldHelperConfig.get().blatantMode) {
            Rotation rot = rotationTo(player.getEyePosition(), hitResult.getLocation());
            player.setYRot(rot.yRot());
            player.setXRot(rot.xRot());
            player.connection.send(new ServerboundMovePlayerPacket.Rot(rot.yRot(), rot.xRot(), player.onGround(), player.horizontalCollision));
        }

        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        if (swapped) {
            selectSlotSilent(player, previousSlot);
        }

        finishSequence(client, previousSlot, restoreSlot, settings, true, seqId);
    }

    private static void finishSequence(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, long seqId) {
        finishSequence(client, previousSlot, axeSlot, settings, false, seqId);
    }

    private static void finishSequence(Minecraft client, int previousSlot, int axeSlot, TriggerSettings settings, boolean forceSwitchBack, long seqId) {
        if (seqId != currentSequenceId) return;

        try {
            if ((settings.switchBackAfterAttack() || forceSwitchBack) && previousSlot != axeSlot) {
                int switchBackDelay = getPingAdjustedMillis(client, settings.switchBackDelayMillis());
                if (ShieldHelperConfig.get().blatantMode && switchBackDelay <= 0) {
                    switchBackDelay = 15; // Safe minimum delay in blatant mode
                }
                if (switchBackDelay <= 0) {
                    try {
                        LocalPlayer player = client.player;
                        if (player != null && player.getInventory().getSelectedSlot() == axeSlot) {
                            selectSlot(player, previousSlot);
                            if (settings.postAttackGuard()) {
                                triggerPostAttackGuard(client, settings.postAttackGuardTicks());
                            }
                        }
                    } finally {
                        sequenceInProgress = false;
                    }
                } else {
                    scheduleAsync(client, switchBackDelay, () -> {
                        if (seqId != currentSequenceId) return;
                        try {
                            LocalPlayer player = client.player;
                            if (player != null && player.getInventory().getSelectedSlot() == axeSlot) {
                                selectSlot(player, previousSlot);
                                if (settings.postAttackGuard()) {
                                    triggerPostAttackGuard(client, settings.postAttackGuardTicks());
                                }
                            }
                        } finally {
                            sequenceInProgress = false;
                        }
                    });
                }
            } else {
                sequenceInProgress = false;
                if (settings.postAttackGuard()) {
                    triggerPostAttackGuard(client, settings.postAttackGuardTicks());
                }
            }
        } catch (Exception e) {
            sequenceInProgress = false;
        }
    }

    private static void scheduleAsync(Minecraft client, int delayMillis, Runnable action) {
        CompletableFuture.runAsync(
                () -> client.execute(action),
                CompletableFuture.delayedExecutor(Math.max(1, delayMillis), TimeUnit.MILLISECONDS)
        );
    }

    private static boolean canContinue(Minecraft client, Player target, boolean requireShieldBlocking) {
        ShieldHelperConfig config = ShieldHelperConfig.get();
        if (!config.enabled || !ShieldHelperSafety.isActionAllowed(client, config) || client.screen != null || client.player == null || client.level == null || client.gameMode == null || !client.gameMode.canHurtPlayer()) {
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

        if (!ShieldHelperConfig.get().blatantMode && !isAimingAtEntity(client, client.player, target)) {
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

        if (isShieldBlocking(client.player)) {
            return false;
        }

        return !target.isRemoved() && target.isAlive() && target.level() == client.level;
    }

    private static BlockHitResult findWebPlacementHit(Minecraft client, BlockPos webPos) {
        if (client.level == null || client.player == null) {
            return null;
        }

        if (!canPlaceCobwebAt(client, webPos)) {
            return null;
        }

        if (client.hitResult instanceof BlockHitResult blockHitResult
                && blockHitResult.getType() == HitResult.Type.BLOCK
                && isValidWebPlacementHit(client, blockHitResult, webPos)) {
            return blockHitResult;
        }

        if (ShieldHelperConfig.get().blatantMode) {
            return findBlatantWebPlacementHit(client, webPos);
        }

        return null;
    }

    private static boolean canPlaceCobwebAt(Minecraft client, BlockPos webPos) {
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

        BlockPos clickedPos = hitResult.getBlockPos();
        if (!client.level.isInWorldBounds(clickedPos) || !client.level.isLoaded(clickedPos)) {
            return false;
        }

        BlockState clickedState = client.level.getBlockState(clickedPos);
        BlockPos expectedPlacementPos = clickedState.canBeReplaced() ? clickedPos : clickedPos.relative(hitResult.getDirection());
        if (!expectedPlacementPos.equals(webPos)) {
            return false;
        }

        if (!clickedState.canBeReplaced() && clickedState.isAir()) {
            return false;
        }

        return isReachableBlockHit(client.player, hitResult);
    }

    private static boolean isReachableBlockHit(LocalPlayer player, BlockHitResult hitResult) {
        if (player == null) return false;
        double interactionRange = player.blockInteractionRange();
        // GHOST: Hardened Legitimacy - Subtract a tiny epsilon (0.05) to ensure we are comfortably within vanilla range
        double maxDist = interactionRange - 0.05;
        return player.getEyePosition().distanceToSqr(hitResult.getLocation()) <= maxDist * maxDist;
    }

    private static boolean isReachableEntity(LocalPlayer player, Entity target) {
        if (player == null || target == null) return false;
        double interactionRange = player.entityInteractionRange();
        double maxAttackRange = ShieldHelperConfig.get().maxAttackRange;
        double maxDist = Math.min(interactionRange, maxAttackRange) - 0.05;
        return target.getBoundingBox().distanceToSqr(player.getEyePosition()) <= maxDist * maxDist;
    }

    private static boolean isAimingAtEntity(Minecraft client, LocalPlayer player, Entity target) {
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

    private static int getStunSlot(LocalPlayer player, int axeSlot, TriggerSettings settings) {
        if (player == null) {
            return Inventory.NOT_FOUND_INDEX;
        }

        if (!settings.smpProfile()) {
            return isAxeInSlot(player.getInventory(), axeSlot) ? axeSlot : Inventory.NOT_FOUND_INDEX;
        }

        int knockbackSwordSlot = findHotbarKnockbackOneSword(player.getInventory());
        return knockbackSwordSlot == Inventory.NOT_FOUND_INDEX ? findHotbarSword(player.getInventory()) : knockbackSwordSlot;
    }

    private static int findHotbarKnockbackOneSword(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (isKnockbackOneSword(inventory, slot)) {
                return slot;
            }
        }

        return Inventory.NOT_FOUND_INDEX;
    }

    private static int findHotbarSword(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (isSwordInSlot(inventory, slot)) {
                return slot;
            }
        }

        return Inventory.NOT_FOUND_INDEX;
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

    private static boolean isSwordInSlot(Inventory inventory, int slot) {
        return Inventory.isHotbarSlot(slot) && inventory.getItem(slot).is(ItemTags.SWORDS);
    }

    private static boolean isCobwebInSlot(Inventory inventory, int slot) {
        return Inventory.isHotbarSlot(slot) && inventory.getItem(slot).is(Items.COBWEB);
    }

    private static boolean isKnockbackOneSword(Inventory inventory, int slot) {
        if (!isSwordInSlot(inventory, slot)) {
            return false;
        }

        for (Object2IntMap.Entry<Holder<Enchantment>> entry : inventory.getItem(slot).getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.KNOCKBACK) && entry.getIntValue() == 1) {
                return true;
            }
        }

        return false;
    }

    private static boolean selectSlot(LocalPlayer player, int slot) {
        if (player == null || !Inventory.isHotbarSlot(slot)) {
            return false;
        }

        // GHOST: Redundant check to avoid unnecessary packets
        if (player.getInventory().getSelectedSlot() == slot) {
            return true;
        }

        player.getInventory().setSelectedSlot(slot);
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        return true;
    }

    private static boolean selectSlotSilent(LocalPlayer player, int slot) {
        if (player == null || !Inventory.isHotbarSlot(slot)) {
            return false;
        }
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        return true;
    }

    private record TriggerSettings(
            boolean switchBackAfterAttack,
            boolean stunning,
            ShieldHelperConfig.StunWebMode stunWeb,
            boolean smpProfile,
            int attackCooldownTicks,
            int firstAttackDelayMillis,
            int stunningMinDelayMillis,
            int stunningMaxDelayMillis,
            int stunWebDelayMillis,
            int switchBackDelayMillis,
            boolean postAttackGuard,
            int postAttackGuardTicks,
            int missChancePercent,
            int shieldMissChancePercent
    ) {
        private static TriggerSettings from(ShieldHelperConfig config) {
            return new TriggerSettings(
                    config.switchBackAfterAttack,
                    config.stunning,
                    config.stunning ? config.stunWeb : ShieldHelperConfig.StunWebMode.OFF,
                    ShieldHelperConfig.PROFILE_SMP.equals(config.combatProfile),
                    config.attackCooldownTicks,
                    config.firstAttackDelayMillis,
                    Math.min(config.stunningMinDelayMillis, config.stunningMaxDelayMillis),
                    Math.max(config.stunningMinDelayMillis, config.stunningMaxDelayMillis),
                    config.stunWebDelayMillis,
                    config.switchBackDelayMillis,
                    config.postAttackGuard,
                    config.postAttackGuardTicks,
                    config.missChancePercent,
                    config.shieldMissChancePercent
            );
        }
    }

    private static BlockHitResult findBlatantWebPlacementHit(Minecraft client, BlockPos webPos) {
        LocalPlayer player = client.player;
        if (player == null) return null;

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
            if (isValidWebPlacementHit(client, hitResult, webPos)) {
                return hitResult;
            }
        }

        return null;
    }

    private record Rotation(float yRot, float xRot) {}

    private static Rotation rotationTo(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yRot = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float xRot = (float) -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
        return new Rotation(yRot, xRot);
    }
}
