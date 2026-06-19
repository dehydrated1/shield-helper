package com.example.shieldhelper.client;

import com.example.shieldhelper.config.ShieldHelperConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

@Environment(EnvType.CLIENT)
public final class ShieldHelperConfigScreen extends Screen {

    private static final int PAGE_GENERAL = 0;
    private static final int PAGE_CONTROLS = 1;
    private static final int PAGE_DELAYS = 2;
    private static final int CONTROL_WIDTH = 220;
    private static final int CONTROL_HEIGHT = 20;
    private static final int CONTROL_SPACING = 20;
    private static final int DELAY_MAX_MILLIS = 250;
    private static final int DELAY_STEP_MILLIS = 5;

    private final Screen parent;
    private final ShieldHelperConfig config;

    private int page = PAGE_GENERAL;
    private boolean waitingForToggleKey;

    private Button generalPageButton;
    private Button controlsPageButton;
    private Button delaysPageButton;

    private Button enabledButton;
    private Button hotkeyToggleButton;
    private Button statusNotificationsButton;
    private Button toggleKeyButton;
    private Button safetyStatusButton;
    private Button trustedServerButton;

    private Button switchBackButton;
    private Button stunningButton;
    private Button stunWebButton;
    private Button cooldownRequirementButton;
    private Button attackStrengthButton;
    private Button triggerCooldownButton;
    private Button pingCompensationButton;
    private Button maxAttackRangeButton;
    private Button postAttackGuardButton;
    private Button postAttackGuardTicksButton;
    private Button missPercentageButton;

    private DelaySliderButton swapDelaySlider;
    private DelaySliderButton firstAttackDelaySlider;
    private DelaySliderButton stunningMinDelaySlider;
    private DelaySliderButton stunningMaxDelaySlider;
    private DelaySliderButton stunWebDelaySlider;
    private DelaySliderButton switchBackDelaySlider;

    public ShieldHelperConfigScreen(Screen parent) {
        super(Component.translatable("shield-helper.config.title"));
        this.parent = parent;
        this.config = ShieldHelperConfig.get();
    }

    @Override
    protected void init() {
        buildWidgets();
    }

    private void buildWidgets() {
        clearWidgets();

        int x = (this.width - CONTROL_WIDTH) / 2;
        int tabY = 44;
        int tabWidth = (CONTROL_WIDTH - 12) / 3;

        generalPageButton = addRenderableWidget(Button.builder(generalPageText(), button -> {
            page = PAGE_GENERAL;
            waitingForToggleKey = false;
            buildWidgets();
        }).bounds(x, tabY, tabWidth, CONTROL_HEIGHT).build());

        controlsPageButton = addRenderableWidget(Button.builder(controlsPageText(), button -> {
            page = PAGE_CONTROLS;
            waitingForToggleKey = false;
            buildWidgets();
        }).bounds(x + tabWidth + 6, tabY, tabWidth, CONTROL_HEIGHT).build());

        delaysPageButton = addRenderableWidget(Button.builder(delaysPageText(), button -> {
            page = PAGE_DELAYS;
            waitingForToggleKey = false;
            buildWidgets();
        }).bounds(x + (tabWidth + 6) * 2, tabY, tabWidth, CONTROL_HEIGHT).build());

        int y = tabY + CONTROL_SPACING + 4;
        if (page == PAGE_DELAYS) {
            addDelayControls(x, y);
        } else if (page == PAGE_CONTROLS) {
            addControlControls(x, y);
        } else {
            addGeneralControls(x, y);
        }

        int bottomY = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(x, bottomY, CONTROL_WIDTH / 2 - 2, CONTROL_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable("shield-helper.config.reset"), button -> {
            config.reset();
            ShieldHelperConfig.save();
            buildWidgets();
        }).bounds(x + CONTROL_WIDTH / 2 + 2, bottomY, CONTROL_WIDTH / 2 - 2, CONTROL_HEIGHT).build());
    }

    private void addGeneralControls(int x, int y) {
        int offset = 0;

        switchBackButton = addRenderableWidget(Button.builder(switchBackText(), button -> {
            config.switchBackAfterAttack = !config.switchBackAfterAttack;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        stunningButton = addRenderableWidget(Button.builder(stunningText(), button -> {
            config.stunning = !config.stunning;
            if (!config.stunning) {
                config.stunWeb = ShieldHelperConfig.StunWebMode.OFF;
            }
            ShieldHelperConfig.save();
            buildWidgets();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        if (config.stunning) {
            stunWebButton = addRenderableWidget(Button.builder(stunWebText(), button -> {
                config.stunWeb = config.stunWeb == ShieldHelperConfig.StunWebMode.ON ? ShieldHelperConfig.StunWebMode.OFF : ShieldHelperConfig.StunWebMode.ON;
                ShieldHelperConfig.save();
                updateButtonLabels();
            }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
            offset++;
        }

        cooldownRequirementButton = addRenderableWidget(Button.builder(cooldownRequirementText(), button -> {
            config.requireAttackCooldown = !config.requireAttackCooldown;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        attackStrengthButton = addRenderableWidget(Button.builder(attackStrengthText(), button -> {
            config.cycleMinimumAttackStrength();
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        triggerCooldownButton = addRenderableWidget(Button.builder(triggerCooldownText(), button -> {
            config.cycleAttackCooldownTicks();
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        postAttackGuardButton = addRenderableWidget(Button.builder(postAttackGuardText(), button -> {
            config.postAttackGuard = !config.postAttackGuard;
            ShieldHelperConfig.save();
            buildWidgets();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        if (config.postAttackGuard) {
            postAttackGuardTicksButton = addRenderableWidget(Button.builder(postAttackGuardTicksText(), button -> {
                config.cyclePostAttackGuardTicks();
                ShieldHelperConfig.save();
                updateButtonLabels();
            }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
            offset++;
        }

        missPercentageButton = addRenderableWidget(Button.builder(missPercentageText(), button -> {
            config.cycleMissPercentage();
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
    }

    private void addControlControls(int x, int y) {
        int offset = 0;

        enabledButton = addRenderableWidget(Button.builder(enabledText(), button -> {
            config.enabled = !config.enabled;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        hotkeyToggleButton = addRenderableWidget(Button.builder(hotkeyToggleText(), button -> {
            config.hotkeyToggleEnabled = !config.hotkeyToggleEnabled;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        statusNotificationsButton = addRenderableWidget(Button.builder(statusNotificationsText(), button -> {
            config.statusNotifications = !config.statusNotifications;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        toggleKeyButton = addRenderableWidget(Button.builder(toggleKeyText(), button -> {
            waitingForToggleKey = true;
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        safetyStatusButton = addRenderableWidget(Button.builder(safetyStatusText(), button -> {
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        safetyStatusButton.active = false;
        offset++;

        trustedServerButton = addRenderableWidget(Button.builder(trustedServerText(), button -> {
            String address = ShieldHelperSafety.getCurrentServerAddress(this.minecraft);
            if (!address.isEmpty()) {
                config.toggleTrustedServer(address);
                ShieldHelperConfig.save();
                updateButtonLabels();
            }
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        trustedServerButton.active = ShieldHelperSafety.canTrustCurrentServer(this.minecraft);
        offset++;

        pingCompensationButton = addRenderableWidget(Button.builder(pingCompensationText(), button -> {
            config.pingCompensation = !config.pingCompensation;
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
        offset++;

        maxAttackRangeButton = addRenderableWidget(Button.builder(maxAttackRangeText(), button -> {
            config.cycleMaxAttackRange();
            ShieldHelperConfig.save();
            updateButtonLabels();
        }).bounds(x, y + CONTROL_SPACING * offset, CONTROL_WIDTH, CONTROL_HEIGHT).build());
    }

    private void addDelayControls(int x, int y) {
        swapDelaySlider = addRenderableWidget(new DelaySliderButton(
                x,
                y,
                "shield-helper.config.swap_delay",
                () -> config.swapDelayMillis,
                value -> config.swapDelayMillis = value
        ));

        firstAttackDelaySlider = addRenderableWidget(new DelaySliderButton(
                x,
                y + CONTROL_SPACING,
                "shield-helper.config.first_attack_delay",
                () -> config.firstAttackDelayMillis,
                value -> config.firstAttackDelayMillis = value
        ));

        stunningMinDelaySlider = addRenderableWidget(new DelaySliderButton(
                x,
                y + CONTROL_SPACING * 2,
                "shield-helper.config.stunning_min_delay",
                () -> config.stunningMinDelayMillis,
                value -> config.stunningMinDelayMillis = value
        ));

        stunningMaxDelaySlider = addRenderableWidget(new DelaySliderButton(
                x,
                y + CONTROL_SPACING * 3,
                "shield-helper.config.stunning_max_delay",
                () -> config.stunningMaxDelayMillis,
                value -> config.stunningMaxDelayMillis = value
        ));

        int nextOffset = 4;
        if (config.stunning && config.stunWeb != ShieldHelperConfig.StunWebMode.OFF) {
            stunWebDelaySlider = addRenderableWidget(new DelaySliderButton(
                    x,
                    y + CONTROL_SPACING * nextOffset,
                    "shield-helper.config.stun_web_delay",
                    () -> config.stunWebDelayMillis,
                    value -> config.stunWebDelayMillis = value
            ));
            nextOffset++;
        }

        switchBackDelaySlider = addRenderableWidget(new DelaySliderButton(
                x,
                y + CONTROL_SPACING * nextOffset,
                "shield-helper.config.switch_back_delay",
                () -> config.switchBackDelayMillis,
                value -> config.switchBackDelayMillis = value
        ));
    }

    private void updateButtonLabels() {
        if (generalPageButton != null) {
            generalPageButton.setMessage(generalPageText());
        }
        if (controlsPageButton != null) {
            controlsPageButton.setMessage(controlsPageText());
        }
        if (delaysPageButton != null) {
            delaysPageButton.setMessage(delaysPageText());
        }
        if (enabledButton != null) {
            enabledButton.setMessage(enabledText());
        }
        if (hotkeyToggleButton != null) {
            hotkeyToggleButton.setMessage(hotkeyToggleText());
        }
        if (statusNotificationsButton != null) {
            statusNotificationsButton.setMessage(statusNotificationsText());
        }
        if (toggleKeyButton != null) {
            toggleKeyButton.setMessage(toggleKeyText());
        }
        if (safetyStatusButton != null) {
            safetyStatusButton.setMessage(safetyStatusText());
        }
        if (trustedServerButton != null) {
            trustedServerButton.setMessage(trustedServerText());
            trustedServerButton.active = ShieldHelperSafety.canTrustCurrentServer(this.minecraft);
        }
        if (switchBackButton != null) {
            switchBackButton.setMessage(switchBackText());
        }
        if (stunningButton != null) {
            stunningButton.setMessage(stunningText());
        }
        if (stunWebButton != null) {
            stunWebButton.setMessage(stunWebText());
        }
        if (cooldownRequirementButton != null) {
            cooldownRequirementButton.setMessage(cooldownRequirementText());
        }
        if (attackStrengthButton != null) {
            attackStrengthButton.setMessage(attackStrengthText());
        }
        if (triggerCooldownButton != null) {
            triggerCooldownButton.setMessage(triggerCooldownText());
        }
        if (pingCompensationButton != null) {
            pingCompensationButton.setMessage(pingCompensationText());
        }
        if (maxAttackRangeButton != null) {
            maxAttackRangeButton.setMessage(maxAttackRangeText());
        }
        if (postAttackGuardButton != null) {
            postAttackGuardButton.setMessage(postAttackGuardText());
        }
        if (postAttackGuardTicksButton != null) {
            postAttackGuardTicksButton.setMessage(postAttackGuardTicksText());
        }
        if (missPercentageButton != null) {
            missPercentageButton.setMessage(missPercentageText());
        }
    }

    private Component generalPageText() {
        return pageText(PAGE_GENERAL, "shield-helper.config.page.general");
    }

    private Component controlsPageText() {
        return pageText(PAGE_CONTROLS, "shield-helper.config.page.controls");
    }

    private Component delaysPageText() {
        return pageText(PAGE_DELAYS, "shield-helper.config.page.delays");
    }

    private Component pageText(int targetPage, String translationKey) {
        return Component.translatable(page == targetPage ? translationKey + ".selected" : translationKey);
    }

    private Component enabledText() {
        return Component.translatable("shield-helper.config.macro", stateText(config.enabled));
    }

    private Component hotkeyToggleText() {
        return Component.translatable("shield-helper.config.hotkey_toggle", stateText(config.hotkeyToggleEnabled));
    }

    private Component statusNotificationsText() {
        return Component.translatable("shield-helper.config.status_notifications", stateText(config.statusNotifications));
    }

    private Component toggleKeyText() {
        if (waitingForToggleKey) {
            return Component.translatable("shield-helper.config.toggle_key.waiting");
        }

        return Component.translatable("shield-helper.config.toggle_key", InputConstants.getKey(config.toggleKeyName).getDisplayName());
    }

    private Component safetyStatusText() {
        return Component.translatable("shield-helper.config.safety_status", safetyStatusValueText());
    }

    private Component safetyStatusValueText() {
        return Component.translatable(ShieldHelperSafety.isActionAllowed(this.minecraft, config)
                ? "shield-helper.config.safety_status.allowed"
                : "shield-helper.config.safety_status.blocked");
    }

    private Component trustedServerText() {
        return Component.translatable("shield-helper.config.trusted_server", trustedServerValueText());
    }

    private Component trustedServerValueText() {
        if (!ShieldHelperSafety.canTrustCurrentServer(this.minecraft)) {
            return Component.translatable("shield-helper.config.trusted_server.unavailable");
        }

        return stateText(config.isTrustedServer(ShieldHelperSafety.getCurrentServerAddress(this.minecraft)));
    }

    private Component switchBackText() {
        return Component.translatable("shield-helper.config.switch_back", stateText(config.switchBackAfterAttack));
    }

    private Component stunningText() {
        return Component.translatable("shield-helper.config.stunning", stateText(config.stunning));
    }

    private Component stunWebText() {
        return Component.translatable("shield-helper.config.stun_web", stateText(config.stunWeb == ShieldHelperConfig.StunWebMode.ON));
    }

    private Component cooldownRequirementText() {
        return Component.translatable("shield-helper.config.require_cooldown", stateText(config.requireAttackCooldown));
    }

    private Component attackStrengthText() {
        return Component.translatable("shield-helper.config.attack_strength", Component.literal(config.minimumAttackStrengthPercent + "%"));
    }

    private Component triggerCooldownText() {
        return Component.translatable("shield-helper.config.trigger_cooldown", Component.literal(config.attackCooldownTicks + " ticks"));
    }

    private Component pingCompensationText() {
        return Component.translatable("shield-helper.config.ping_compensation", stateText(config.pingCompensation));
    }

    private Component maxAttackRangeText() {
        return Component.translatable("shield-helper.config.max_attack_range", Component.literal(String.format(java.util.Locale.US, "%.1f blocks", config.maxAttackRange)));
    }

    private Component postAttackGuardText() {
        return Component.translatable("shield-helper.config.post_attack_guard", stateText(config.postAttackGuard));
    }

    private Component postAttackGuardTicksText() {
        return Component.translatable("shield-helper.config.post_attack_guard_ticks", Component.literal(config.postAttackGuardTicks + " ticks"));
    }

    private Component missPercentageText() {
        return Component.translatable("shield-helper.config.miss_percentage", Component.literal(config.missPercentage + "%"));
    }

    private static Component stateText(boolean enabled) {
        return Component.translatable(enabled ? "gui.yes" : "gui.no");
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (waitingForToggleKey) {
            int keyCode = event.key();
            if (keyCode == InputConstants.KEY_ESCAPE) {
                waitingForToggleKey = false;
            } else {
                config.toggleKeyCode = keyCode;
                config.toggleKeyName = InputConstants.getKey(event).getName();
                ShieldHelperConfig.save();
                waitingForToggleKey = false;
            }

            updateButtonLabels();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        int bottomY = this.height - 48;
        graphics.drawCenteredString(this.font, successRateText(), this.width / 2, bottomY, 0xAAAAAA);
    }

    private Component successRateText() {
        if (config.shieldDisableAttempts <= 0L) {
            return Component.translatable("shield-helper.config.success_rate.empty");
        }

        int successPercent = config.getShieldDisableSuccessPercent();
        int missPercent = 100 - successPercent;
        long misses = config.shieldDisableAttempts - config.shieldDisableSuccesses;

        return Component.translatable("shield-helper.config.success_rate",
                successPercent, config.shieldDisableSuccesses, missPercent, misses, config.shieldDisableAttempts);
    }

    private static final class DelaySliderButton extends AbstractSliderButton {
        private final String translationKey;
        private final IntSupplier getter;
        private final IntConsumer setter;

        private DelaySliderButton(int x, int y, String translationKey, IntSupplier getter, IntConsumer setter) {
            super(x, y, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), valueToSlider(getter.getAsInt()));
            this.translationKey = translationKey;
            this.getter = getter;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(translationKey, sliderToValue(this.value)));
        }

        @Override
        protected void applyValue() {
            setter.accept(sliderToValue(this.value));
            ShieldHelperConfig.save();
            this.value = valueToSlider(getter.getAsInt());
            updateMessage();
        }

        private static int sliderToValue(double value) {
            int valueMillis = (int) Math.round(value * DELAY_MAX_MILLIS);
            return (valueMillis / DELAY_STEP_MILLIS) * DELAY_STEP_MILLIS;
        }

        private static double valueToSlider(int valueMillis) {
            if (valueMillis <= 0) {
                return 0.0D;
            }

            return Math.min(valueMillis, DELAY_MAX_MILLIS) / (double) DELAY_MAX_MILLIS;
        }
    }
}
