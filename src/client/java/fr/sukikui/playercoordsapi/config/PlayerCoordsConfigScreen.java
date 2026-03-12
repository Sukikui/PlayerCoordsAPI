package fr.sukikui.playercoordsapi.config;

import fr.sukikui.playercoordsapi.PlayerCoordsAPIClient;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Custom Mod Menu screen used to edit the local API settings and origin whitelist.
 */
@Environment(EnvType.CLIENT)
final class PlayerCoordsConfigScreen extends Screen {
    private static final TooltipPositioner TOP_TOOLTIP_POSITIONER = (screenWidth, screenHeight, x, y, width, height) ->
            new Vector2i(
                    MathHelper.clamp(x + 12, 6, Math.max(6, screenWidth - width - 6)),
                    MathHelper.clamp(y - height - 12, 6, Math.max(6, screenHeight - height - 6))
            );
    private static final int CONTENT_WIDTH = 340;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 28;
    private static final int ROW_GAP = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STATUS_BOX_Y = 28;
    private static final int ORIGINS_SECTION_TOP_GAP = 12;
    private static final int STATUS_BOX_HEIGHT = 16;
    private static final int RESET_BUTTON_WIDTH = 46;
    private static final int CONTROL_WIDTH = 144;
    private static final int ORIGIN_ROW_HEIGHT = 28;
    private static final int ORIGIN_ROW_SPACING = 6;
    private static final int ORIGIN_SCHEME_WIDTH = 64;
    private static final int ORIGIN_PORT_WIDTH = 52;
    private static final int ORIGIN_REMOVE_WIDTH = 20;

    private final Screen parent;
    private final ModConfig workingConfig = new ModConfig();
    private final List<OriginDraft> originDrafts;
    private final List<OriginRow> originRows = new ArrayList<>();

    private TextWidget enabledLabel;
    private TextWidget apiPortLabel;
    private TextWidget corsPolicyLabel;
    private TextWidget nonBrowserClientsLabel;
    private CyclingButtonWidget<Boolean> enabledButton;
    private TextFieldWidget apiPortField;
    private CyclingButtonWidget<ModConfig.CorsPolicy> corsPolicyButton;
    private CyclingButtonWidget<Boolean> nonBrowserClientsButton;
    private ButtonWidget apiPortResetButton;
    private ButtonWidget corsPolicyResetButton;
    private ButtonWidget nonBrowserClientsResetButton;
    private ButtonWidget addOriginButton;
    private ButtonWidget applyButton;
    private ButtonWidget doneButton;

    private boolean hasValidationError;
    private String apiPortValue;
    private int scrollOffset;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;

    /**
     * Creates the screen and copies the persisted config into a mutable working draft.
     */
    PlayerCoordsConfigScreen(Screen parent) {
        super(Text.translatable("text.autoconfig.playercoordsapi.title"));
        this.parent = parent;

        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        workingConfig.enabled = config.enabled;
        workingConfig.apiPort = ModConfig.normalizeApiPort(config.apiPort);
        workingConfig.corsPolicy = config.corsPolicy == null ? ModConfig.CorsPolicy.ALLOW_ALL : config.corsPolicy;
        workingConfig.allowNonBrowserLocalClients = config.allowNonBrowserLocalClients;
        workingConfig.allowedOrigins = new ArrayList<>(config.allowedOrigins == null ? List.of() : config.allowedOrigins);
        workingConfig.originEntries = new ArrayList<>(config.originEntries == null ? List.of() : config.originEntries);
        apiPortValue = Integer.toString(workingConfig.apiPort);

        this.originDrafts = createDrafts(workingConfig.originEntries, workingConfig.allowedOrigins);
        syncOriginDraftsWithCorsPolicy();
    }

    /**
     * Builds all visible widgets for the current screen size and working config.
     */
    @Override
    protected void init() {
        int left = this.width / 2 - CONTENT_WIDTH / 2;
        int controlX = left + CONTENT_WIDTH - CONTROL_WIDTH;
        int resetX = controlX - ROW_GAP - RESET_BUTTON_WIDTH;
        int labelWidth = resetX - ROW_GAP - left;
        int y = 52;

        enabledLabel = this.addDrawableChild(new TextWidget(left, y + 6, labelWidth, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.enabled"), this.textRenderer));
        enabledLabel.active = false;
        enabledButton = this.addDrawableChild(CyclingButtonWidget.onOffBuilder(
                        ScreenTexts.ON.copy().formatted(Formatting.GREEN),
                        ScreenTexts.OFF.copy().formatted(Formatting.RED),
                        workingConfig.enabled
                )
                .omitKeyText()
                .build(controlX, y, CONTROL_WIDTH, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.enabled"), (button, value) -> workingConfig.enabled = value));

        y += ROW_SPACING;
        apiPortLabel = this.addDrawableChild(new TextWidget(left, y + 6, labelWidth, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.api_port"), this.textRenderer));
        apiPortLabel.active = false;
        apiPortResetButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("config.playercoordsapi.button.reset"), button -> resetApiPort())
                .dimensions(resetX, y, RESET_BUTTON_WIDTH, ROW_HEIGHT)
                .build());
        apiPortField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, controlX, y, CONTROL_WIDTH, ROW_HEIGHT, ScreenTexts.EMPTY));
        apiPortField.setMaxLength(5);
        apiPortField.setTextPredicate(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        apiPortField.setPlaceholder(Text.literal(Integer.toString(ModConfig.DEFAULT_API_PORT)));
        apiPortField.setText(apiPortValue);
        apiPortField.setEditableColor(0xFFE0E0E0);
        apiPortField.setUneditableColor(0xFF808080);
        apiPortField.setChangedListener(value -> {
            apiPortValue = value;
            ModConfig.parseApiPort(value).ifPresent(port -> workingConfig.apiPort = port);
            updateValidation();
        });

        y += ROW_SPACING;

        nonBrowserClientsLabel = this.addDrawableChild(new TextWidget(
                left,
                y + 6,
                labelWidth,
                ROW_HEIGHT,
                Text.translatable("config.playercoordsapi.option.allow_non_browser_local_clients"),
                this.textRenderer
        ));
        nonBrowserClientsLabel.active = false;
        nonBrowserClientsResetButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("config.playercoordsapi.button.reset"), button -> resetNonBrowserClients())
                .dimensions(resetX, y, RESET_BUTTON_WIDTH, ROW_HEIGHT)
                .build());
        nonBrowserClientsButton = this.addDrawableChild(CyclingButtonWidget.onOffBuilder(
                        ScreenTexts.ON.copy().formatted(Formatting.GREEN),
                        ScreenTexts.OFF.copy().formatted(Formatting.RED),
                        workingConfig.allowNonBrowserLocalClients
                )
                .omitKeyText()
                .build(
                        controlX,
                        y,
                        CONTROL_WIDTH,
                        ROW_HEIGHT,
                        Text.translatable("config.playercoordsapi.option.allow_non_browser_local_clients"),
                        (button, value) -> {
                            workingConfig.allowNonBrowserLocalClients = value;
                            updateValidation();
                        }
                ));

        y += ROW_SPACING;
        corsPolicyLabel = this.addDrawableChild(new TextWidget(left, y + 6, labelWidth, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.cors_policy"), this.textRenderer));
        corsPolicyLabel.active = false;
        corsPolicyResetButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("config.playercoordsapi.button.reset"), button -> resetCorsPolicy())
                .dimensions(resetX, y, RESET_BUTTON_WIDTH, ROW_HEIGHT)
                .build());
        corsPolicyButton = this.addDrawableChild(CyclingButtonWidget.builder(PlayerCoordsConfigScreen::getCorsPolicyLabel, workingConfig.corsPolicy)
                .values(ModConfig.CorsPolicy.values())
                .omitKeyText()
                .build(controlX, y, CONTROL_WIDTH, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.cors_policy"), (button, value) -> {
                    workingConfig.corsPolicy = value;
                    syncOriginDraftsWithCorsPolicy();
                    scrollOffset = 0;
                    clearAndInit();
                }));

        y += ROW_SPACING;

        int bottomButtonsY = this.height - 28;
        int addButtonY = bottomButtonsY - BUTTON_HEIGHT - 8;
        listLeft = left;
        listWidth = CONTENT_WIDTH;
        listTop = y + ORIGINS_SECTION_TOP_GAP;
        listBottom = addButtonY - 8;

        buildOriginRows();

        addOriginButton = this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("config.playercoordsapi.option.add_origin"),
                        button -> {
                            if (hasEmptyOriginDraft()) {
                                return;
                            }

                            originDrafts.add(new OriginDraft());
                            scrollOffset = Integer.MAX_VALUE;
                            clearAndInit();
                        })
                .dimensions(left, addButtonY, CONTENT_WIDTH, BUTTON_HEIGHT)
                .build());

        int bottomButtonWidth = (CONTENT_WIDTH - ROW_GAP * 2) / 3;
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left, bottomButtonsY, bottomButtonWidth, BUTTON_HEIGHT)
                .build());

        applyButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("config.playercoordsapi.button.apply"), button -> {
                    if (applyChanges()) {
                        clearAndInit();
                    }
                })
                .dimensions(left + bottomButtonWidth + ROW_GAP, bottomButtonsY, bottomButtonWidth, BUTTON_HEIGHT)
                .build());

        doneButton = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left + (bottomButtonWidth + ROW_GAP) * 2, bottomButtonsY, bottomButtonWidth, BUTTON_HEIGHT)
                .build());

        updateValidation();
        clampScroll();
    }

    /**
     * Returns to the parent screen without applying pending changes.
     */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    /**
     * Scrolls the whitelist area when the cursor is inside its viewport.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listLeft
                && mouseX <= listLeft + listWidth
                && mouseY >= listTop
                && mouseY <= listBottom
                && getMaxScroll() > 0) {
            scrollOffset = MathHelper.clamp(scrollOffset - (int) (verticalAmount * 18.0), 0, getMaxScroll());
            layoutOriginRows();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /**
     * Routes clicks to focused text fields before falling back to default widget handling.
     */
    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (apiPortField.isMouseOver(click.x(), click.y()) && apiPortField.mouseClicked(click, doubleClick)) {
            apiPortField.setFocused(true);
            clearOriginFieldFocus();
            this.setFocused(apiPortField);
            return true;
        }

        if (isWhitelistEditable() && click.button() == 0) {
            for (OriginRow row : originRows) {
                if (row.tryFocusField(click, doubleClick)) {
                    return true;
                }
            }
        }

        return super.mouseClicked(click, doubleClick);
    }

    /**
     * Forwards key presses to the currently focused text field when applicable.
     */
    @Override
    public boolean keyPressed(KeyInput keyInput) {
        TextFieldWidget focusedTextField = getFocusedTextField();

        if (focusedTextField != null && focusedTextField.keyPressed(keyInput)) {
            return true;
        }

        return super.keyPressed(keyInput);
    }

    /**
     * Forwards typed characters to the currently focused text field when applicable.
     */
    @Override
    public boolean charTyped(CharInput charInput) {
        TextFieldWidget focusedTextField = getFocusedTextField();

        if (focusedTextField != null && focusedTextField.charTyped(charInput)) {
            return true;
        }

        return super.charTyped(charInput);
    }

    /**
     * Renders the custom whitelist panel, standard widgets, and active tooltips.
     */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        layoutOriginRows();
        renderOriginBlocks(context);
        super.render(context, mouseX, mouseY, delta);

        int left = this.width / 2 - CONTENT_WIDTH / 2;
        int controlX = left + CONTENT_WIDTH - CONTROL_WIDTH;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
        renderServerStatus(context, left);
        if (getMaxScroll() > 0) {
            int indicatorX = controlX + CONTROL_WIDTH - 8;
            context.drawTextWithShadow(this.textRenderer, Text.literal(scrollOffset > 0 ? "^" : ""), indicatorX, listTop - 12, 0xFF808080);
            context.drawTextWithShadow(this.textRenderer, Text.literal(scrollOffset < getMaxScroll() ? "v" : ""), indicatorX, listBottom - 8, 0xFF808080);
        }

        renderHoveredTooltip(context, mouseX, mouseY);
    }

    /**
     * Applies changes and closes the screen when validation succeeds.
     */
    private void saveAndClose() {
        if (applyChanges()) {
            close();
        }
    }

    /**
     * Persists the working draft and requests a runtime server refresh.
     */
    private boolean applyChanges() {
        if (hasValidationError) {
            return false;
        }

        workingConfig.apiPort = ModConfig.parseApiPort(apiPortValue).orElse(workingConfig.apiPort);
        workingConfig.originEntries = collectOriginEntries();
        workingConfig.allowedOrigins = CorsUtils.normalizeConfiguredOriginsFromEntries(workingConfig.originEntries);

        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        config.enabled = workingConfig.enabled;
        config.apiPort = ModConfig.normalizeApiPort(workingConfig.apiPort);
        config.corsPolicy = workingConfig.corsPolicy;
        config.allowNonBrowserLocalClients = workingConfig.allowNonBrowserLocalClients;
        config.originEntries = new ArrayList<>(workingConfig.originEntries);
        config.allowedOrigins = new ArrayList<>(workingConfig.allowedOrigins);
        AutoConfig.getConfigHolder(ModConfig.class).save();
        PlayerCoordsAPIClient.requestServerRefresh();
        return true;
    }

    /**
     * Restores the API port field to its default value.
     */
    private void resetApiPort() {
        workingConfig.apiPort = ModConfig.DEFAULT_API_PORT;
        apiPortValue = Integer.toString(ModConfig.DEFAULT_API_PORT);
        clearAndInit();
    }

    /**
     * Restores the with-origin policy to its default mode.
     */
    private void resetCorsPolicy() {
        workingConfig.corsPolicy = ModConfig.CorsPolicy.ALLOW_ALL;
        syncOriginDraftsWithCorsPolicy();
        scrollOffset = 0;
        clearAndInit();
    }

    /**
     * Restores the without-origin toggle to its default enabled state.
     */
    private void resetNonBrowserClients() {
        workingConfig.allowNonBrowserLocalClients = true;
        clearAndInit();
    }

    /**
     * Recomputes validation and updates the enabled state of dependent controls.
     */
    private void updateValidation() {
        hasValidationError = !isConfigValid();

        if (addOriginButton != null) {
            addOriginButton.active = isWhitelistEditable() && !hasEmptyOriginDraft();
        }

        if (apiPortResetButton != null) {
            apiPortResetButton.active = !apiPortValue.equals(Integer.toString(ModConfig.DEFAULT_API_PORT));
        }

        if (corsPolicyResetButton != null) {
            corsPolicyResetButton.active = workingConfig.corsPolicy != ModConfig.CorsPolicy.ALLOW_ALL;
        }

        if (nonBrowserClientsResetButton != null) {
            nonBrowserClientsResetButton.active = !workingConfig.allowNonBrowserLocalClients;
        }

        if (applyButton != null) {
            applyButton.active = !hasValidationError;
        }

        if (doneButton != null) {
            doneButton.active = !hasValidationError;
        }
    }

    /**
     * Returns whether the current working config can be applied safely.
     */
    private boolean isConfigValid() {
        return isApiPortValid() && isWhitelistValid();
    }

    /**
     * Validates the API port input field.
     */
    private boolean isApiPortValid() {
        return ModConfig.parseApiPort(apiPortValue).isPresent();
    }

    /**
     * Validates whitelist rows when whitelist mode is active.
     */
    private boolean isWhitelistValid() {
        if (workingConfig.corsPolicy != ModConfig.CorsPolicy.CUSTOM_WHITELIST) {
            return true;
        }

        if (originDrafts.isEmpty()) {
            return false;
        }

        Set<String> normalizedOrigins = new LinkedHashSet<>();

        for (OriginDraft draft : originDrafts) {
            Optional<String> normalizedOrigin = draft.toNormalizedOrigin();

            if (normalizedOrigin.isEmpty()) {
                return false;
            }

            if (!normalizedOrigins.add(normalizedOrigin.get())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Rebuilds the row widgets used to display editable whitelist entries.
     */
    private void buildOriginRows() {
        originRows.clear();

        for (OriginDraft draft : originDrafts) {
            OriginRow row = new OriginRow(draft);
            originRows.add(row);
            this.addDrawableChild(row.schemeButton);
            this.addDrawableChild(row.hostField);
            this.addDrawableChild(row.portField);
            this.addDrawableChild(row.removeButton);
        }
    }

    /**
     * Positions whitelist row widgets according to scroll state and edit mode.
     */
    private void layoutOriginRows() {
        clampScroll();
        boolean whitelistEditable = isWhitelistEditable();

        int rowWidth = CONTENT_WIDTH;
        int hostWidth = rowWidth - ORIGIN_SCHEME_WIDTH - ORIGIN_PORT_WIDTH - ORIGIN_REMOVE_WIDTH - 24;

        for (int i = 0; i < originRows.size(); i++) {
            OriginRow row = originRows.get(i);
            int rowY = listTop + i * (ORIGIN_ROW_HEIGHT + ORIGIN_ROW_SPACING) - scrollOffset;
            boolean visible = rowY + ORIGIN_ROW_HEIGHT >= listTop && rowY <= listBottom;

            row.setState(visible, whitelistEditable);

            if (!visible) {
                continue;
            }

            row.schemeButton.setPosition(listLeft + 4, rowY + 4);
            row.hostField.setPosition(listLeft + ORIGIN_SCHEME_WIDTH + 12, rowY + 4);
            row.portField.setPosition(listLeft + CONTENT_WIDTH - ORIGIN_REMOVE_WIDTH - ORIGIN_PORT_WIDTH - 12, rowY + 4);
            row.removeButton.setPosition(listLeft + CONTENT_WIDTH - ORIGIN_REMOVE_WIDTH - 4, rowY + 4);

            row.hostField.setWidth(hostWidth);
        }
    }

    /**
     * Draws the bordered background blocks that visually group whitelist rows.
     */
    private void renderOriginBlocks(DrawContext context) {
        int panelTop = listTop - 4;
        int panelBottom = listBottom + 2;
        boolean whitelistEditable = isWhitelistEditable();

        drawBorder(context, listLeft, panelTop, CONTENT_WIDTH, panelBottom - panelTop, 0xFF4A4A4A);

        for (int i = 0; i < originRows.size(); i++) {
            int rowY = listTop + i * (ORIGIN_ROW_HEIGHT + ORIGIN_ROW_SPACING) - scrollOffset;

            if (rowY + ORIGIN_ROW_HEIGHT < listTop || rowY > listBottom) {
                continue;
            }

            int fillColor = whitelistEditable ? 0x44202020 : 0x44303030;
            context.fill(listLeft + 1, rowY, listLeft + CONTENT_WIDTH - 1, rowY + ORIGIN_ROW_HEIGHT, fillColor);
            drawBorder(context, listLeft, rowY, CONTENT_WIDTH, ORIGIN_ROW_HEIGHT, 0xFF5A5A5A);
        }

        if (!whitelistEditable) {
            context.fill(listLeft + 1, panelTop + 1, listLeft + CONTENT_WIDTH - 1, panelBottom - 1, 0x55202020);
        }
    }

    /**
     * Draws a simple rectangular border using filled quads.
     */
    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    /**
     * Converts all non-empty UI drafts into persisted origin entries.
     */
    private List<ModConfig.OriginEntry> collectOriginEntries() {
        List<ModConfig.OriginEntry> originEntries = new ArrayList<>();

        for (OriginDraft draft : originDrafts) {
            if (draft.isEmpty()) {
                continue;
            }

            originEntries.add(draft.toConfigEntry());
        }

        return originEntries;
    }

    /**
     * Computes the maximum vertical scroll offset for the whitelist panel.
     */
    private int getMaxScroll() {
        int contentHeight = originRows.size() * (ORIGIN_ROW_HEIGHT + ORIGIN_ROW_SPACING) - ORIGIN_ROW_SPACING;
        int visibleHeight = Math.max(0, listBottom - listTop);
        return Math.max(0, contentHeight - visibleHeight);
    }

    /**
     * Clamps the current whitelist scroll offset to the valid range.
     */
    private void clampScroll() {
        scrollOffset = MathHelper.clamp(scrollOffset, 0, getMaxScroll());
    }

    /**
     * Removes one draft row and rebuilds the screen layout.
     */
    private void removeDraft(OriginDraft draft) {
        originDrafts.remove(draft);
        clearAndInit();
    }

    /**
     * Clears focus from all whitelist text fields.
     */
    private void clearOriginFieldFocus() {
        for (OriginRow row : originRows) {
            row.hostField.setFocused(false);
            row.portField.setFocused(false);
        }
    }

    /**
     * Keeps an empty placeholder row only while whitelist mode is active.
     */
    private void syncOriginDraftsWithCorsPolicy() {
        if (workingConfig.corsPolicy == ModConfig.CorsPolicy.CUSTOM_WHITELIST) {
            if (originDrafts.isEmpty()) {
                originDrafts.add(new OriginDraft());
            }

            return;
        }

        originDrafts.removeIf(OriginDraft::isEmpty);
    }

    /**
     * Returns whether the whitelist already contains an empty placeholder row.
     */
    private boolean hasEmptyOriginDraft() {
        for (OriginDraft draft : originDrafts) {
            if (draft.host == null || draft.host.trim().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Renders the tooltip currently targeted by the mouse cursor.
     */
    private void renderHoveredTooltip(DrawContext context, int mouseX, int mouseY) {
        List<Text> tooltip = getHoveredTooltip(mouseX, mouseY);

        if (!tooltip.isEmpty()) {
            List<net.minecraft.text.OrderedText> orderedTooltip = new ArrayList<>(tooltip.size());
            for (Text line : tooltip) {
                orderedTooltip.add(line.asOrderedText());
            }
            context.drawTooltip(this.textRenderer, orderedTooltip, TOP_TOOLTIP_POSITIONER, mouseX, mouseY, false);
        }
    }

    /**
     * Resolves which tooltip should be shown for the current mouse position.
     */
    private List<Text> getHoveredTooltip(int mouseX, int mouseY) {
        if (isHoveringLabel(apiPortLabel, mouseX, mouseY)) {
            return buildApiPortTooltip();
        }

        if (isHoveringLabel(corsPolicyLabel, mouseX, mouseY)) {
            return buildWithOriginTooltip();
        }

        if (isHoveringLabel(nonBrowserClientsLabel, mouseX, mouseY)) {
            return buildWithoutOriginTooltip();
        }

        for (OriginRow row : originRows) {
            if (row.hostField.visible && row.hostField.isMouseOver(mouseX, mouseY)) {
                return buildHostTooltip();
            }

            if (row.portField.visible && row.portField.isMouseOver(mouseX, mouseY)) {
                return buildPortTooltip();
            }
        }

        return List.of();
    }

    /**
     * Returns the text field that should currently receive keyboard input.
     */
    private TextFieldWidget getFocusedTextField() {
        if (apiPortField != null && apiPortField.isFocused()) {
            return apiPortField;
        }

        for (OriginRow row : originRows) {
            if (row.hostField.visible && row.hostField.isFocused()) {
                return row.hostField;
            }

            if (row.portField.visible && row.portField.isFocused()) {
                return row.portField;
            }
        }

        return null;
    }

    /**
     * Builds the multi-line tooltip used by whitelist host fields.
     */
    private List<Text> buildHostTooltip() {
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip"));
        tooltip.add(buildHostTooltipLine("config.playercoordsapi.option.origin_host.tooltip.localhost", "localhost"));
        tooltip.add(buildHostTooltipLine("config.playercoordsapi.option.origin_host.tooltip.domain", "example.com"));
        tooltip.add(buildHostTooltipLine("config.playercoordsapi.option.origin_host.tooltip.ipv4", "127.0.0.1"));
        tooltip.add(buildHostTooltipLine("config.playercoordsapi.option.origin_host.tooltip.ipv6", "2001:db8::1"));
        appendWhitelistDisabledHint(tooltip);
        return tooltip;
    }

    /**
     * Builds one highlighted example line for the host tooltip.
     */
    private Text buildHostTooltipLine(String key, String example) {
        return Text.translatable(key).append(Text.literal(" " + example).formatted(Formatting.GREEN));
    }

    /**
     * Builds the tooltip shown for the API port field.
     */
    private List<Text> buildApiPortTooltip() {
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.translatable("config.playercoordsapi.option.api_port.tooltip"));
        tooltip.add(Text.translatable("config.playercoordsapi.option.api_port.tooltip.range")
                .append(Text.literal(" " + ModConfig.MIN_API_PORT + "-" + ModConfig.MAX_API_PORT).formatted(Formatting.GREEN)));
        tooltip.add(Text.translatable("config.playercoordsapi.option.api_port.tooltip.example")
                .append(Text.literal(" 3000").formatted(Formatting.GREEN)));
        return tooltip;
    }

    /**
     * Builds the tooltip shown for whitelist origin port fields.
     */
    private List<Text> buildPortTooltip() {
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_port.tooltip"));
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_port.tooltip.example")
                .append(Text.literal(" 3000").formatted(Formatting.GREEN)));
        appendWhitelistDisabledHint(tooltip);
        return tooltip;
    }

    /**
     * Builds the tooltip for requests that provide an {@code Origin} header.
     */
    private List<Text> buildWithOriginTooltip() {
        List<Text> tooltip = new ArrayList<>(6);
        tooltip.add(Text.translatable("config.playercoordsapi.option.cors_policy.tooltip.1"));
        tooltip.add(Text.translatable("config.playercoordsapi.option.cors_policy.tooltip.2"));
        tooltip.add(Text.translatable(
                "config.playercoordsapi.option.cors_policy.tooltip.3",
                emphasizeTooltipValue(Text.translatable("config.playercoordsapi.option.cors_policy.allow_all"))
        ));
        tooltip.add(Text.translatable(
                "config.playercoordsapi.option.cors_policy.tooltip.4",
                emphasizeTooltipValue(Text.translatable("config.playercoordsapi.option.cors_policy.local_web_apps_only")),
                Text.literal("localhost").formatted(Formatting.GREEN),
                Text.literal("127.0.0.1").formatted(Formatting.GREEN),
                Text.literal("::1").formatted(Formatting.GREEN)
        ));
        tooltip.add(Text.translatable(
                "config.playercoordsapi.option.cors_policy.tooltip.5",
                emphasizeTooltipValue(Text.translatable("config.playercoordsapi.option.cors_policy.custom_whitelist"))
        ));
        tooltip.add(Text.translatable("config.playercoordsapi.option.cors_policy.tooltip.6"));
        return tooltip;
    }

    /**
     * Builds the tooltip for requests that do not provide an {@code Origin} header.
     */
    private List<Text> buildWithoutOriginTooltip() {
        List<Text> tooltip = new ArrayList<>(4);
        tooltip.add(Text.translatable("config.playercoordsapi.option.allow_non_browser_local_clients.tooltip.1"));
        tooltip.add(Text.translatable("config.playercoordsapi.option.allow_non_browser_local_clients.tooltip.2"));
        tooltip.add(Text.translatable(
                "config.playercoordsapi.option.allow_non_browser_local_clients.tooltip.3",
                emphasizeTooltipValue(ScreenTexts.ON.copy())
        ));
        tooltip.add(Text.translatable(
                "config.playercoordsapi.option.allow_non_browser_local_clients.tooltip.4",
                emphasizeTooltipValue(ScreenTexts.OFF.copy()),
                emphasizeTooltipValue(Text.translatable("config.playercoordsapi.option.cors_policy"))
        ));
        return tooltip;
    }

    /**
     * Applies the shared visual emphasis used for important tooltip values.
     */
    private Text emphasizeTooltipValue(Text text) {
        return text.copy().formatted(Formatting.GRAY, Formatting.BOLD);
    }

    /**
     * Appends the disabled-editing hint when the whitelist is visible but locked.
     */
    private void appendWhitelistDisabledHint(List<Text> tooltip) {
        if (!isWhitelistEditable()) {
            tooltip.add(Text.translatable("config.playercoordsapi.option.allowed_origins.disabled").formatted(Formatting.GRAY));
        }
    }

    /**
     * Draws the top banner summarizing the HTTP server state.
     */
    private void renderServerStatus(DrawContext context, int left) {
        int boxTop = STATUS_BOX_Y;
        int boxBottom = boxTop + STATUS_BOX_HEIGHT;
        context.fill(left, boxTop, left + CONTENT_WIDTH, boxBottom, getServerStatusBackgroundColor());
        drawBorder(context, left, boxTop, CONTENT_WIDTH, STATUS_BOX_HEIGHT, getServerStatusBorderColor());
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                getServerStatusText(),
                this.width / 2,
                boxTop + 4,
                getServerStatusColor()
        );
    }

    /**
     * Returns the localized text shown inside the server status banner.
     */
    private Text getServerStatusText() {
        PlayerCoordsAPIClient.ServerStatus status = PlayerCoordsAPIClient.getServerStatus();

        return switch (status.state()) {
            case DISABLED -> Text.translatable("config.playercoordsapi.status.disabled");
            case STOPPED -> Text.translatable("config.playercoordsapi.status.stopped", status.port());
            case RUNNING -> Text.translatable("config.playercoordsapi.status.running", status.port());
            case FAILED -> Text.translatable("config.playercoordsapi.status.failed", status.port(), status.detail());
        };
    }

    /**
     * Returns the banner text color for the current server state.
     */
    private int getServerStatusColor() {
        return switch (PlayerCoordsAPIClient.getServerStatus().state()) {
            case DISABLED -> 0xFFD0D0D0;
            case STOPPED -> 0xFFFFD070;
            case RUNNING -> 0xFF90FF90;
            case FAILED -> 0xFFFF9090;
        };
    }

    /**
     * Returns the banner background color for the current server state.
     */
    private int getServerStatusBackgroundColor() {
        return switch (PlayerCoordsAPIClient.getServerStatus().state()) {
            case DISABLED -> 0x66303030;
            case STOPPED -> 0x66504020;
            case RUNNING -> 0x66204020;
            case FAILED -> 0x66502020;
        };
    }

    /**
     * Returns the banner border color for the current server state.
     */
    private int getServerStatusBorderColor() {
        return switch (PlayerCoordsAPIClient.getServerStatus().state()) {
            case DISABLED -> 0xFF606060;
            case STOPPED -> 0xFFD0A040;
            case RUNNING -> 0xFF55CC55;
            case FAILED -> 0xFFFF5555;
        };
    }

    /**
     * Returns whether the mouse position lies inside the given rectangle.
     */
    private static boolean isWithin(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Returns whether the cursor is hovering the active area of a setting label.
     */
    private static boolean isHoveringLabel(TextWidget label, int mouseX, int mouseY) {
        return isWithin(mouseX, mouseY, label.getX(), label.getY(), label.getWidth(), ROW_HEIGHT);
    }

    /**
     * Returns whether whitelist rows should currently be editable.
     */
    private boolean isWhitelistEditable() {
        return workingConfig.corsPolicy == ModConfig.CorsPolicy.CUSTOM_WHITELIST;
    }

    /**
     * Returns the localized label used by the with-origin policy cycling button.
     */
    private static Text getCorsPolicyLabel(ModConfig.CorsPolicy policy) {
        return Text.translatable("config.playercoordsapi.option.cors_policy." + policy.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the localized label used by the origin scheme cycling button.
     */
    private static Text getOriginSchemeModeLabel(ModConfig.OriginSchemeMode mode) {
        return Text.translatable("config.playercoordsapi.option.origin_scheme." + mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Creates UI drafts from persisted entries or the legacy flat origin list.
     */
    private static List<OriginDraft> createDrafts(List<ModConfig.OriginEntry> originEntries, List<String> origins) {
        List<OriginDraft> drafts = new ArrayList<>();

        if (originEntries != null) {
            for (ModConfig.OriginEntry originEntry : originEntries) {
                if (originEntry != null) {
                    drafts.add(OriginDraft.fromConfigEntry(originEntry));
                }
            }
        }

        if (!drafts.isEmpty()) {
            return drafts;
        }

        for (String origin : origins) {
            CorsUtils.createConfiguredOriginEntry(origin)
                    .map(OriginDraft::fromConfigEntry)
                    .ifPresent(drafts::add);
        }

        return drafts;
    }

    /**
     * One rendered whitelist row composed of scheme, host, port, and remove controls.
     */
    private final class OriginRow {
        private final CyclingButtonWidget<ModConfig.OriginSchemeMode> schemeButton;
        private final TextFieldWidget hostField;
        private final TextFieldWidget portField;
        private final ButtonWidget removeButton;

        /**
         * Builds the widgets backing a single whitelist row.
         */
        private OriginRow(OriginDraft draft) {
            this.schemeButton = CyclingButtonWidget.builder(PlayerCoordsConfigScreen::getOriginSchemeModeLabel, draft.mode)
                    .values(ModConfig.OriginSchemeMode.values())
                    .omitKeyText()
                    .build(0, 0, ORIGIN_SCHEME_WIDTH, ROW_HEIGHT, ScreenTexts.EMPTY, (button, value) -> {
                        draft.mode = value;
                        updateValidation();
                    });

            this.hostField = new TextFieldWidget(PlayerCoordsConfigScreen.this.textRenderer, 0, 0, 160, ROW_HEIGHT, ScreenTexts.EMPTY);
            this.hostField.setMaxLength(255);
            this.hostField.setPlaceholder(Text.translatable("config.playercoordsapi.option.origin_host"));
            this.hostField.setText(draft.host);
            this.hostField.setChangedListener(value -> {
                draft.host = value;
                updateValidation();
            });

            this.portField = new TextFieldWidget(PlayerCoordsConfigScreen.this.textRenderer, 0, 0, ORIGIN_PORT_WIDTH, ROW_HEIGHT, ScreenTexts.EMPTY);
            this.portField.setMaxLength(5);
            this.portField.setTextPredicate(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
            this.portField.setPlaceholder(Text.translatable("config.playercoordsapi.option.origin_port"));
            this.portField.setText(draft.port);
            this.portField.setChangedListener(value -> {
                draft.port = value;
                updateValidation();
            });

            this.removeButton = ButtonWidget.builder(Text.literal("X"), button -> removeDraft(draft))
                    .dimensions(0, 0, ORIGIN_REMOVE_WIDTH, ROW_HEIGHT)
                    .build();
        }

        /**
         * Updates visibility, editability, and focus behavior for this row.
         */
        private void setState(boolean visible, boolean editable) {
            schemeButton.visible = visible;
            schemeButton.active = visible && editable;
            hostField.visible = visible;
            hostField.active = visible && editable;
            hostField.setEditable(visible && editable);
            hostField.setEditableColor(0xFFE0E0E0);
            hostField.setUneditableColor(0xFF808080);
            hostField.setFocusUnlocked(editable);
            if (!editable) {
                hostField.setFocused(false);
            }
            portField.visible = visible;
            portField.active = visible && editable;
            portField.setEditable(visible && editable);
            portField.setEditableColor(0xFFE0E0E0);
            portField.setUneditableColor(0xFF808080);
            portField.setFocusUnlocked(editable);
            if (!editable) {
                portField.setFocused(false);
            }
            removeButton.visible = visible;
            removeButton.active = visible && editable;
        }

        /**
         * Tries to focus one of the row text fields from the given mouse click.
         */
        private boolean tryFocusField(Click click, boolean doubleClick) {
            if (!hostField.visible || !hostField.active) {
                return false;
            }

            if (hostField.isMouseOver(click.x(), click.y()) && hostField.mouseClicked(click, doubleClick)) {
                hostField.setFocused(true);
                portField.setFocused(false);
                apiPortField.setFocused(false);
                PlayerCoordsConfigScreen.this.setFocused(hostField);
                return true;
            }

            if (portField.isMouseOver(click.x(), click.y()) && portField.mouseClicked(click, doubleClick)) {
                portField.setFocused(true);
                hostField.setFocused(false);
                apiPortField.setFocused(false);
                PlayerCoordsConfigScreen.this.setFocused(portField);
                return true;
            }

            return false;
        }
    }

    /**
     * Mutable UI representation of one whitelist entry before normalization and save.
     */
    private static final class OriginDraft {
        private ModConfig.OriginSchemeMode mode;
        private String host;
        private String port;

        /**
         * Creates an empty placeholder draft.
         */
        private OriginDraft() {
            this(ModConfig.OriginSchemeMode.AUTO, "", "");
        }

        /**
         * Creates a draft with explicit values for all editable fields.
         */
        private OriginDraft(ModConfig.OriginSchemeMode mode, String host, String port) {
            this.mode = mode;
            this.host = host;
            this.port = port;
        }

        /**
         * Builds a draft from a persisted config entry.
         */
        private static OriginDraft fromConfigEntry(ModConfig.OriginEntry originEntry) {
            return new OriginDraft(
                    originEntry.schemeMode == null ? ModConfig.OriginSchemeMode.AUTO : originEntry.schemeMode,
                    originEntry.host == null ? "" : originEntry.host,
                    originEntry.port == null ? "" : originEntry.port
            );
        }

        /**
         * Converts the draft back into its persisted config representation.
         */
        private ModConfig.OriginEntry toConfigEntry() {
            ModConfig.OriginEntry originEntry = new ModConfig.OriginEntry();
            originEntry.schemeMode = mode;
            originEntry.host = host;
            originEntry.port = port;
            return originEntry;
        }

        /**
         * Normalizes the draft into a canonical origin string when valid.
         */
        private Optional<String> toNormalizedOrigin() {
            return CorsUtils.normalizeConfiguredOrigin(host, port, mode);
        }

        /**
         * Returns whether the draft is still just an empty placeholder row.
         */
        private boolean isEmpty() {
            return host == null || host.trim().isEmpty();
        }
    }
}
