package com.example.shieldhelper.mixin;

import com.example.shieldhelper.ShieldHelperMod;
import com.example.shieldhelper.client.ShieldHelperRuntime;
import com.example.shieldhelper.client.ShieldHelperSafety;
import com.example.shieldhelper.client.SilentRotation;
import com.example.shieldhelper.config.ShieldHelperConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin {
    private static final int WEB_LANDING_PREDICTION_TICKS = 16;
    private static final int WEB_LANDING_GROUND_SEARCH_BLOCKS = 8;
    private static final double WEB_PREDICTION_GRAVITY = 0.08D;
    private static final double WEB_PREDICTION_VERTICAL_DRAG = 0.98D;
    private static final double WEB_PREDICTION_HORIZONTAL_DRAG = 0.91D;
    private static final double WEB_BETWEEN_TARGET_TOLERANCE_BLOCKS = 0.75D;
    private static final double WEB_MAX_LINE_OFFSET_BLOCKS = 1.25D;
    private static final Direction[] WEB_PLACEMENT_DIRECTIONS = {
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.DOWN
    };
    private static final ScheduledExecutorService shieldhelper$scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Shield Helper Sequence");
        thread.setDaemon(true);
        return thread;
    });

    @Shadow
    @Final
    private Minecraft minecraft;

    private boolean shieldhelper$processing;
    private int shieldhelper$sequenceGeneration;

    @Shadow
    public abstract void ensureHasSentCarriedItem();

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void shieldhelper$onAttack(Player player, Entity target, CallbackInfo ci) {
        if (this.shieldhelper$processing) {
            if (ShieldHelperRuntime.isSequenceCurrent(this.shieldhelper$sequenceGeneration)) {
                return;
            }

            this.shieldhelper$processing = false;
        }

        ShieldHelperConfig config = ShieldHelperConfig.get();
        if (!this.shieldhelper$canStart(player, target, config)) {
            return;
        }

        // Intentional miss: roll to skip the disable (never in blatant mode). The vanilla attack is
        // left to proceed normally, so the click still lands as an ordinary hit.
        if (!config.blatantMode && config.missPercentage > 0
                && ThreadLocalRandom.current().nextInt(100) < config.missPercentage) {
            ShieldHelperRuntime.triggerCooldownTicks = Math.max(2, config.attackCooldownTicks);
            return;
        }

        LocalPlayer clientPlayer = this.minecraft.player;
        ItemStack mainHandStack = clientPlayer.getMainHandItem();
        boolean holdingSword = this.shieldhelper$isSword(mainHandStack);
        boolean holdingMace = mainHandStack.getItem() instanceof MaceItem;
        boolean isAirborne = !clientPlayer.onGround();
        int axeSlot = this.shieldhelper$findItem(clientPlayer, false);
        int maceSlot = this.shieldhelper$findItem(clientPlayer, true);
        int currentSlot = clientPlayer.getInventory().getSelectedSlot();
        int stunSlot = this.shieldhelper$getStunSlot(clientPlayer, currentSlot, axeSlot, maceSlot, isAirborne, config);

        // Both sword and mace holders route through the axe swap so the shield is actually
        // disabled (maces do not disable shields); when airborne the stun step uses the mace
        // (see getStunSlot) to land the smash follow-up.
        if ((holdingSword || holdingMace) && axeSlot != Inventory.NOT_FOUND_INDEX) {
            ci.cancel();
            try {
                this.shieldhelper$startSwapAndAttack(clientPlayer, axeSlot, currentSlot, stunSlot, target, config);
            } catch (RuntimeException exception) {
                // An inline (zero-delay / blatant) step threw before any async continuation could
                // clear the guard; reset it so the macro is not stranded off until relog.
                this.shieldhelper$processing = false;
                ShieldHelperMod.LOGGER.warn("Shield Helper sequence failed.", exception);
            }
        }
    }

    private boolean shieldhelper$canStart(Player player, Entity target, ShieldHelperConfig config) {
        if (config.enabled && ShieldHelperSafety.isActionAllowed(this.minecraft, config)
                && (config.blatantMode || ShieldHelperRuntime.triggerCooldownTicks <= 0)
                && this.minecraft.screen == null && this.minecraft.player != null && this.minecraft.level != null && player == this.minecraft.player) {
            if (player instanceof LocalPlayer localPlayer && target instanceof LivingEntity livingTarget) {
                if (this.shieldhelper$isBlocking(livingTarget) && this.shieldhelper$canAttackTarget(localPlayer, livingTarget, config)) {
                    return config.blatantMode || !config.requireAttackCooldown
                            || this.minecraft.player.getAttackStrengthScale(0.0F) * 100.0F >= (float) config.minimumAttackStrengthPercent;
                }
            }
        }
        return false;
    }

    private boolean shieldhelper$canContinue(LocalPlayer player, Entity target, boolean requireShieldBlocking, ShieldHelperConfig config) {
        if (config.enabled && ShieldHelperSafety.isActionAllowed(this.minecraft, config) && this.minecraft.screen == null
                && this.minecraft.player != null && this.minecraft.level != null && this.minecraft.gameMode != null && player != null && player == this.minecraft.player) {
            if (target instanceof LivingEntity livingTarget && !livingTarget.isRemoved() && livingTarget.isAlive()) {
                return this.shieldhelper$canAttackTarget(player, livingTarget, config) && (!requireShieldBlocking || this.shieldhelper$isBlocking(livingTarget));
            }
        }
        return false;
    }

    private boolean shieldhelper$canAttackTarget(LocalPlayer player, Entity target, ShieldHelperConfig config) {
        return player != null && target != null && !target.isRemoved() && target.isAlive()
                && target.level() == this.minecraft.level
                && this.shieldhelper$isWithinEntityReach(player, target, config);
    }

    private boolean shieldhelper$isSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS);
    }

    private int shieldhelper$getStunSlot(LocalPlayer player, int originalSlot, int axeSlot, int maceSlot, boolean isAirborne, ShieldHelperConfig config) {
        if (!config.stunning || player == null) {
            return Inventory.NOT_FOUND_INDEX;
        }

        if (isAirborne && Inventory.isHotbarSlot(maceSlot)) {
            return maceSlot;
        }

        if (config.switchBackAfterAttack && Inventory.isHotbarSlot(originalSlot)) {
            return originalSlot;
        }

        return Inventory.isHotbarSlot(axeSlot) ? axeSlot : Inventory.NOT_FOUND_INDEX;
    }

    private void shieldhelper$startSwapAndAttack(LocalPlayer player, int axeSlot, int originalSlot, int secondarySlot, Entity target, ShieldHelperConfig config) {
        this.shieldhelper$processing = true;
        this.shieldhelper$sequenceGeneration = ShieldHelperRuntime.currentSequenceGeneration();
        int swapDelay = this.shieldhelper$getDelayMillis(config, config.swapDelayMillis);
        if (swapDelay <= 0) {
            this.shieldhelper$executeSwap(player, axeSlot, originalSlot, secondarySlot, target, config);
        } else {
            this.shieldhelper$schedule(swapDelay, () -> this.shieldhelper$executeSwap(player, axeSlot, originalSlot, secondarySlot, target, config));
        }
    }

    private void shieldhelper$executeSwap(LocalPlayer player, int axeSlot, int originalSlot, int secondarySlot, Entity target, ShieldHelperConfig config) {
        LocalPlayer currentPlayer = this.minecraft.player;
        if (this.shieldhelper$canContinue(currentPlayer, target, true, config) && this.shieldhelper$selectSlot(currentPlayer, axeSlot)) {
            int firstAttackDelay = this.shieldhelper$getDelayMillis(config, config.firstAttackDelayMillis);
            if (firstAttackDelay <= 0) {
                this.shieldhelper$executeAttack(currentPlayer, axeSlot, originalSlot, secondarySlot, target, config);
            } else {
                this.shieldhelper$schedule(firstAttackDelay, () -> this.shieldhelper$executeAttack(currentPlayer, axeSlot, originalSlot, secondarySlot, target, config));
            }
        } else {
            this.shieldhelper$restoreOrStop(currentPlayer, originalSlot, config);
        }
    }

    private void shieldhelper$executeAttack(LocalPlayer player, int axeSlot, int originalSlot, int secondarySlot, Entity target, ShieldHelperConfig config) {
        LocalPlayer currentPlayer = this.minecraft.player;
        if (this.shieldhelper$canContinue(currentPlayer, target, true, config) && this.shieldhelper$selectSlot(currentPlayer, axeSlot)) {
            ((MultiPlayerGameMode) (Object) this).attack(currentPlayer, target);
            ShieldHelperRuntime.triggerCooldownTicks = Math.max(0, config.attackCooldownTicks);
            currentPlayer.swing(InteractionHand.MAIN_HAND);
            if (secondarySlot != Inventory.NOT_FOUND_INDEX) {
                this.shieldhelper$schedule(this.shieldhelper$getStunDelayMillis(config), () -> this.shieldhelper$executeSecondaryAttack(currentPlayer, originalSlot, secondarySlot, target, config));
            } else {
                this.shieldhelper$restoreOrStop(currentPlayer, originalSlot, config);
            }
        } else {
            this.shieldhelper$restoreOrStop(currentPlayer, originalSlot, config);
        }
    }

    private void shieldhelper$executeSecondaryAttack(LocalPlayer player, int originalSlot, int secondarySlot, Entity target, ShieldHelperConfig config) {
        LocalPlayer currentPlayer = this.minecraft.player;
        if (this.shieldhelper$canContinue(currentPlayer, target, false, config) && this.shieldhelper$selectSlot(currentPlayer, secondarySlot)) {
            ((MultiPlayerGameMode) (Object) this).attack(currentPlayer, target);
            currentPlayer.swing(InteractionHand.MAIN_HAND);
            this.shieldhelper$webOrRestore(currentPlayer, originalSlot, secondarySlot, target, config);
        } else {
            this.shieldhelper$restoreOrStop(currentPlayer, originalSlot, config);
        }
    }

    private void shieldhelper$webOrRestore(LocalPlayer player, int originalSlot, int restoreSlot, Entity target, ShieldHelperConfig config) {
        if (config.stunning && config.stunWeb) {
            int webDelay = this.shieldhelper$getDelayMillis(config, config.stunWebDelayMillis);
            if (webDelay <= 0) {
                this.shieldhelper$executeStunWeb(player, originalSlot, restoreSlot, target, config);
            } else {
                this.shieldhelper$schedule(webDelay, () -> this.shieldhelper$executeStunWeb(player, originalSlot, restoreSlot, target, config));
            }
        } else {
            this.shieldhelper$restoreOrStop(player, originalSlot, config);
        }
    }

    private void shieldhelper$executeStunWeb(LocalPlayer player, int originalSlot, int restoreSlot, Entity target, ShieldHelperConfig config) {
        LocalPlayer currentPlayer = this.minecraft.player != null ? this.minecraft.player : player;
        if (currentPlayer != null && target instanceof LivingEntity livingTarget && this.shieldhelper$canContinueForWeb(currentPlayer, livingTarget, config)) {
            int webSlot = this.shieldhelper$findHotbarCobweb(currentPlayer);
            BlockHitResult hitResult = this.shieldhelper$findBestWebPlacementHit(currentPlayer, livingTarget);
            if (webSlot != Inventory.NOT_FOUND_INDEX && hitResult != null && this.shieldhelper$selectSlot(currentPlayer, webSlot)) {
                BlockPos webPos = this.shieldhelper$getExpectedPlacementPos(hitResult);
                Rotation rotation = this.shieldhelper$getRotationTo(currentPlayer, hitResult.getLocation());
                if (webPos != null && rotation != null && this.shieldhelper$isValidWebPlacementHit(hitResult, webPos)) {
                    boolean placed = false;
                    SilentRotation.Snapshot rotationSnapshot = null;
                    SilentRotation.set(rotation.yaw(), rotation.pitch(), this);
                    try {
                        rotationSnapshot = SilentRotation.apply(currentPlayer);
                        if (this.minecraft.gameMode != null && this.shieldhelper$canContinueForWeb(currentPlayer, livingTarget, config)
                                && this.shieldhelper$isCobwebInSlot(currentPlayer, webSlot)
                                && currentPlayer.getInventory().getSelectedSlot() == webSlot
                                && this.shieldhelper$sendLookPacket(currentPlayer, rotation)) {
                            InteractionResult result = this.minecraft.gameMode.useItemOn(currentPlayer, InteractionHand.MAIN_HAND, hitResult);
                            placed = result.consumesAction();
                        }
                    } finally {
                        if (rotationSnapshot != null) {
                            rotationSnapshot.restore(currentPlayer);
                        }
                        SilentRotation.stop(this);
                    }

                    if (placed) {
                        currentPlayer.swing(InteractionHand.MAIN_HAND);
                    }
                }
            }
        }

        this.shieldhelper$finishAfterWeb(currentPlayer, originalSlot, restoreSlot, config);
    }

    private void shieldhelper$finishAfterWeb(LocalPlayer player, int originalSlot, int restoreSlot, ShieldHelperConfig config) {
        int targetSlot = config.switchBackAfterAttack ? originalSlot : restoreSlot;
        if (!Inventory.isHotbarSlot(targetSlot)) {
            this.shieldhelper$processing = false;
            return;
        }

        int restoreDelay = config.switchBackAfterAttack ? this.shieldhelper$getDelayMillis(config, config.switchBackDelayMillis) : 0;
        if (restoreDelay <= 0) {
            this.shieldhelper$restoreSelectedSlot(player, targetSlot);
        } else {
            this.shieldhelper$schedule(restoreDelay, () -> this.shieldhelper$restoreSelectedSlot(player, targetSlot));
        }
    }

    private void shieldhelper$restoreOrStop(LocalPlayer player, int originalSlot, ShieldHelperConfig config) {
        if (!config.switchBackAfterAttack) {
            this.shieldhelper$processing = false;
            return;
        }

        int restoreDelay = this.shieldhelper$getDelayMillis(config, config.switchBackDelayMillis);
        if (restoreDelay <= 0) {
            this.shieldhelper$restoreSelectedSlot(player, originalSlot);
        } else {
            this.shieldhelper$schedule(restoreDelay, () -> this.shieldhelper$restoreSelectedSlot(player, originalSlot));
        }
    }

    private void shieldhelper$restoreSelectedSlot(LocalPlayer player, int slot) {
        if (player != null && Inventory.isHotbarSlot(slot)) {
            this.shieldhelper$selectSlot(player, slot);
        }
        this.shieldhelper$processing = false;
    }

    private boolean shieldhelper$canContinueForWeb(LocalPlayer player, LivingEntity target, ShieldHelperConfig config) {
        return this.minecraft.player != null && this.minecraft.level != null && this.minecraft.gameMode != null
                && player != null && player == this.minecraft.player
                && config.enabled && ShieldHelperSafety.isActionAllowed(this.minecraft, config)
                && this.shieldhelper$canAttackTarget(player, target, config);
    }

    private BlockHitResult shieldhelper$findBestWebPlacementHit(LocalPlayer player, LivingEntity target) {
        BlockPos targetFeet = this.shieldhelper$getTargetFeetWebPos(target);
        BlockHitResult hitResult = this.shieldhelper$findWebPlacementHit(player, target, targetFeet);
        if (hitResult != null) {
            return hitResult;
        }

        BlockPos landingPos = this.shieldhelper$predictLandingWebPos(target);
        return landingPos != null && !landingPos.equals(targetFeet) ? this.shieldhelper$findWebPlacementHit(player, target, landingPos) : null;
    }

    private BlockPos shieldhelper$getTargetFeetWebPos(LivingEntity target) {
        Vec3 feet = this.shieldhelper$getTargetFeetPosition(target);
        return BlockPos.containing(feet.x, feet.y, feet.z);
    }

    private Vec3 shieldhelper$getTargetFeetPosition(LivingEntity target) {
        return new Vec3(target.getX(), target.getBoundingBox().minY, target.getZ());
    }

    private BlockHitResult shieldhelper$findWebPlacementHit(LocalPlayer player, LivingEntity target, BlockPos webPos) {
        return webPos != null && this.shieldhelper$isWebBetweenPlayerAndTarget(player, target, webPos) ? this.shieldhelper$findLegitWebPlacementHit(player, webPos) : null;
    }

    private BlockPos shieldhelper$predictLandingWebPos(LivingEntity target) {
        if (this.minecraft.level == null) {
            return null;
        }

        Vec3 position = this.shieldhelper$getTargetFeetPosition(target);
        Vec3 velocity = target.getDeltaMovement();
        for (int tick = 1; tick <= WEB_LANDING_PREDICTION_TICKS; tick++) {
            position = position.add(velocity);
            if (velocity.y <= 0.0D) {
                BlockPos landingPos = this.shieldhelper$findLandingWebCandidate(BlockPos.containing(position.x, position.y, position.z));
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

        return this.shieldhelper$findNearestLandingWebCandidate(BlockPos.containing(position.x, position.y, position.z));
    }

    private BlockPos shieldhelper$findNearestLandingWebCandidate(BlockPos startPos) {
        for (int blocksBelow = 0; blocksBelow <= WEB_LANDING_GROUND_SEARCH_BLOCKS; blocksBelow++) {
            BlockPos landingPos = this.shieldhelper$findLandingWebCandidate(startPos.below(blocksBelow));
            if (landingPos != null) {
                return landingPos.immutable();
            }
        }
        return null;
    }

    private BlockPos shieldhelper$findLandingWebCandidate(BlockPos feetPos) {
        BlockPos landingPos = this.shieldhelper$normalizeLandingWebPos(feetPos);
        return landingPos != null ? landingPos : this.shieldhelper$normalizeLandingWebPos(feetPos.above());
    }

    private BlockPos shieldhelper$normalizeLandingWebPos(BlockPos webPos) {
        return this.shieldhelper$isLoadedWorldPos(webPos) && this.shieldhelper$canPlaceCobwebAt(webPos) && this.shieldhelper$isLandingSupport(webPos.below()) ? webPos : null;
    }

    private boolean shieldhelper$isLandingSupport(BlockPos pos) {
        if (!this.shieldhelper$isLoadedWorldPos(pos)) {
            return false;
        }
        BlockState state = this.minecraft.level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }

    private boolean shieldhelper$isLoadedWorldPos(BlockPos pos) {
        return this.minecraft.level != null && this.minecraft.level.isInWorldBounds(pos) && this.minecraft.level.isLoaded(pos);
    }

    private boolean shieldhelper$canPlaceCobwebAt(BlockPos webPos) {
        LocalPlayer player = this.minecraft.player;
        if (this.minecraft.level == null || player == null || webPos == null) {
            return false;
        }

        if (!this.minecraft.level.isInWorldBounds(webPos) || !this.minecraft.level.isLoaded(webPos) || !this.minecraft.level.mayInteract(player, webPos)) {
            return false;
        }

        BlockState placementState = this.minecraft.level.getBlockState(webPos);
        return !placementState.is(Blocks.COBWEB) && placementState.canBeReplaced();
    }

    private boolean shieldhelper$isValidWebPlacementHit(BlockHitResult hitResult, BlockPos webPos) {
        if (!this.shieldhelper$canPlaceCobwebAt(webPos)) {
            return false;
        }
        BlockPos expectedPlacementPos = this.shieldhelper$getExpectedPlacementPos(hitResult);
        return webPos.equals(expectedPlacementPos) && this.shieldhelper$isReachableBlockHit(hitResult);
    }

    private BlockPos shieldhelper$getExpectedPlacementPos(BlockHitResult hitResult) {
        if (this.minecraft.level == null || hitResult == null) {
            return null;
        }

        BlockPos clickedPos = hitResult.getBlockPos();
        if (!this.minecraft.level.isInWorldBounds(clickedPos) || !this.minecraft.level.isLoaded(clickedPos)) {
            return null;
        }

        BlockState clickedState = this.minecraft.level.getBlockState(clickedPos);
        return clickedState.canBeReplaced() ? clickedPos : clickedPos.relative(hitResult.getDirection());
    }

    private boolean shieldhelper$isReachableBlockHit(BlockHitResult hitResult) {
        LocalPlayer player = this.minecraft.player;
        if (player == null || hitResult == null) {
            return false;
        }
        double maxDistance = player.blockInteractionRange() - 0.05D;
        return maxDistance > 0.0D && player.getEyePosition().distanceToSqr(hitResult.getLocation()) <= maxDistance * maxDistance;
    }

    private boolean shieldhelper$isWithinEntityReach(LocalPlayer player, Entity target, ShieldHelperConfig config) {
        double maxDistance = Math.min(player.entityInteractionRange(), config.disableDistanceBlocks) - 0.05D;
        return maxDistance > 0.0D && target.getBoundingBox().distanceToSqr(player.getEyePosition()) <= maxDistance * maxDistance;
    }

    private BlockHitResult shieldhelper$findLegitWebPlacementHit(LocalPlayer player, BlockPos webPos) {
        if (this.minecraft.level == null || player == null || !this.shieldhelper$canPlaceCobwebAt(webPos)) {
            return null;
        }

        BlockHitResult bestHit = null;
        double bestRotationDelta = Double.POSITIVE_INFINITY;
        for (Direction direction : WEB_PLACEMENT_DIRECTIONS) {
            BlockPos clickedPos = webPos.relative(direction.getOpposite());
            if (!this.minecraft.level.isInWorldBounds(clickedPos) || !this.minecraft.level.isLoaded(clickedPos)) {
                continue;
            }

            BlockState clickedState = this.minecraft.level.getBlockState(clickedPos);
            if (clickedState.canBeReplaced() || clickedState.isAir()) {
                continue;
            }

            Vec3 hitLocation = Vec3.atCenterOf(clickedPos).add(
                    direction.getStepX() * 0.5D,
                    direction.getStepY() * 0.5D,
                    direction.getStepZ() * 0.5D
            );
            BlockHitResult hitResult = new BlockHitResult(hitLocation, direction, clickedPos, false);
            if (!this.shieldhelper$isValidWebPlacementHit(hitResult, webPos) || !this.shieldhelper$hasLineOfSightToHit(player, hitResult)) {
                continue;
            }

            double rotationDelta = this.shieldhelper$getRotationDeltaDegrees(player, hitLocation);
            if (rotationDelta < bestRotationDelta) {
                bestRotationDelta = rotationDelta;
                bestHit = hitResult;
            }
        }

        return bestHit;
    }

    private boolean shieldhelper$isWebBetweenPlayerAndTarget(LocalPlayer player, LivingEntity target, BlockPos webPos) {
        Vec3 playerPos = this.shieldhelper$getTargetFeetPosition(player);
        Vec3 targetPos = this.shieldhelper$getTargetFeetPosition(target);
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

    private boolean shieldhelper$hasLineOfSightToHit(LocalPlayer player, BlockHitResult hitResult) {
        if (this.minecraft.level == null || player == null || hitResult == null) {
            return false;
        }

        BlockHitResult lineOfSight = this.minecraft.level.clip(new ClipContext(
                player.getEyePosition(),
                hitResult.getLocation(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return lineOfSight.getType() != HitResult.Type.BLOCK || lineOfSight.getBlockPos().equals(hitResult.getBlockPos());
    }

    private Rotation shieldhelper$getRotationTo(LocalPlayer player, Vec3 hitLocation) {
        Vec3 delta = hitLocation.subtract(player.getEyePosition());
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D;
        double pitch = -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
        return Double.isFinite(yaw) && Double.isFinite(pitch)
                ? new Rotation((float) yaw, (float) Math.max(-90.0D, Math.min(90.0D, pitch)))
                : null;
    }

    private boolean shieldhelper$sendLookPacket(LocalPlayer player, Rotation rotation) {
        if (player != null && player.connection != null && rotation != null) {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(rotation.yaw(), rotation.pitch(), player.onGround(), player.horizontalCollision));
            return true;
        }
        return false;
    }

    private double shieldhelper$getRotationDeltaDegrees(LocalPlayer player, Vec3 hitLocation) {
        Rotation rotation = this.shieldhelper$getRotationTo(player, hitLocation);
        if (rotation == null) {
            return Double.POSITIVE_INFINITY;
        }
        double yawDelta = this.shieldhelper$wrapDegrees(rotation.yaw() - player.getYRot());
        double pitchDelta = rotation.pitch() - player.getXRot();
        return Math.hypot(yawDelta, pitchDelta);
    }

    private double shieldhelper$wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        } else if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }

    private void shieldhelper$schedule(int delayMillis, Runnable action) {
        int generation = this.shieldhelper$sequenceGeneration;
        shieldhelper$scheduler.schedule(() -> this.minecraft.execute(() -> {
            if (!ShieldHelperRuntime.isSequenceCurrent(generation)) {
                this.shieldhelper$processing = false;
                return;
            }

            try {
                action.run();
            } catch (RuntimeException exception) {
                this.shieldhelper$processing = false;
                ShieldHelperMod.LOGGER.warn("Shield Helper sequence step failed.", exception);
            }
        }), delayMillis, TimeUnit.MILLISECONDS);
    }

    private int shieldhelper$getStunDelayMillis(ShieldHelperConfig config) {
        if (config.blatantMode) {
            return 0;
        }
        int minDelay = Math.min(config.stunningMinDelayMillis, config.stunningMaxDelayMillis);
        int maxDelay = Math.max(config.stunningMinDelayMillis, config.stunningMaxDelayMillis);
        return minDelay == maxDelay ? this.shieldhelper$getDelayMillis(config, minDelay) : this.shieldhelper$getDelayMillis(config, ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1));
    }

    private int shieldhelper$getDelayMillis(ShieldHelperConfig config, int delayMillis) {
        return config.blatantMode ? 0 : Math.max(0, delayMillis);
    }

    private boolean shieldhelper$isBlocking(LivingEntity entity) {
        return entity.isUsingItem() && entity.getUseItem().is(Items.SHIELD);
    }

    private int shieldhelper$findItem(LocalPlayer player, boolean lookForMace) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (lookForMace) {
                if (stack.getItem() instanceof MaceItem) {
                    return slot;
                }
            } else if (stack.is(ItemTags.AXES)) {
                return slot;
            }
        }
        return Inventory.NOT_FOUND_INDEX;
    }

    private int shieldhelper$findHotbarCobweb(LocalPlayer player) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (this.shieldhelper$isCobwebInSlot(player, slot)) {
                return slot;
            }
        }
        return Inventory.NOT_FOUND_INDEX;
    }

    private boolean shieldhelper$isCobwebInSlot(LocalPlayer player, int slot) {
        return player != null && Inventory.isHotbarSlot(slot) && player.getInventory().getItem(slot).is(Items.COBWEB);
    }

    private boolean shieldhelper$selectSlot(LocalPlayer player, int slot) {
        if (player == null || !Inventory.isHotbarSlot(slot)) {
            return false;
        }

        if (player.getInventory().getSelectedSlot() == slot) {
            return true;
        }

        try {
            player.getInventory().setSelectedSlot(slot);
            this.ensureHasSentCarriedItem();
            return player.getInventory().getSelectedSlot() == slot;
        } catch (RuntimeException exception) {
            this.shieldhelper$processing = false;
            ShieldHelperMod.LOGGER.warn("Shield Helper failed to switch hotbar slot.", exception);
            return false;
        }
    }

    private record Rotation(float yaw, float pitch) {
    }
}
