package fr.sukikui.playercoordsapi.config;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    private static final int BUTTON_HEIGHT = 20;
    private static final int CONTROL_WIDTH = 170;
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
    private TextWidget corsPolicyLabel;
    private TextWidget nonBrowserClientsLabel;
    private CyclingButtonWidget<Boolean> enabledButton;
    private CyclingButtonWidget<ModConfig.CorsPolicy> corsPolicyButton;
    private CyclingButtonWidget<Boolean> nonBrowserClientsButton;
    private ButtonWidget addOriginButton;
    private ButtonWidget doneButton;

    private Optional<Text> whitelistError = Optional.empty();
    private int scrollOffset;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;
    private int corsPolicyRowY;
    private int nonBrowserClientsRowY;

    PlayerCoordsConfigScreen(Screen parent) {
        super(Text.translatable("text.autoconfig.playercoordsapi.title"));
        this.parent = parent;

        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        workingConfig.enabled = config.enabled;
        workingConfig.corsPolicy = config.corsPolicy == null ? ModConfig.CorsPolicy.ALLOW_ALL : config.corsPolicy;
        workingConfig.allowNonBrowserLocalClients = config.allowNonBrowserLocalClients;
        workingConfig.allowedOrigins = new ArrayList<>(config.allowedOrigins == null ? List.of() : config.allowedOrigins);
        workingConfig.originEntries = new ArrayList<>(config.originEntries == null ? List.of() : config.originEntries);

        this.originDrafts = createDrafts(workingConfig.originEntries, workingConfig.allowedOrigins);
        syncOriginDraftsWithCorsPolicy();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - CONTENT_WIDTH / 2;
        int controlX = left + CONTENT_WIDTH - CONTROL_WIDTH;
        int y = 32;

        enabledLabel = this.addDrawableChild(new TextWidget(left, y + 6, CONTROL_WIDTH - 12, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.enabled"), this.textRenderer));
        enabledLabel.active = false;
        enabledButton = this.addDrawableChild(CyclingButtonWidget.onOffBuilder(
                        ScreenTexts.ON.copy().formatted(Formatting.GREEN),
                        ScreenTexts.OFF.copy().formatted(Formatting.RED),
                        workingConfig.enabled
                )
                .omitKeyText()
                .build(controlX, y, CONTROL_WIDTH, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.enabled"), (button, value) -> workingConfig.enabled = value));

        y += ROW_SPACING;

        corsPolicyRowY = y;
        corsPolicyLabel = this.addDrawableChild(new TextWidget(left, y + 6, CONTROL_WIDTH - 12, ROW_HEIGHT, Text.translatable("config.playercoordsapi.option.cors_policy"), this.textRenderer));
        corsPolicyLabel.active = false;
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
        nonBrowserClientsRowY = y;

        nonBrowserClientsLabel = this.addDrawableChild(new TextWidget(
                left,
                y + 6,
                CONTROL_WIDTH - 12,
                ROW_HEIGHT,
                Text.translatable("config.playercoordsapi.option.allow_non_browser_local_clients"),
                this.textRenderer
        ));
        nonBrowserClientsLabel.active = false;
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
                        (button, value) -> workingConfig.allowNonBrowserLocalClients = value
                ));

        y += ROW_SPACING;

        int bottomButtonsY = this.height - 28;
        int addButtonY = bottomButtonsY - BUTTON_HEIGHT - 8;
        listLeft = left;
        listWidth = CONTENT_WIDTH;
        listTop = y + 40;
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

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left, bottomButtonsY, 150, BUTTON_HEIGHT)
                .build());

        doneButton = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left + CONTENT_WIDTH - 150, bottomButtonsY, 150, BUTTON_HEIGHT)
                .build());

        updateValidation();
        clampScroll();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

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

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (isWhitelistEditable() && click.button() == 0) {
            for (OriginRow row : originRows) {
                if (row.tryFocusField(click, doubleClick)) {
                    return true;
                }
            }
        }

        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        TextFieldWidget focusedTextField = getFocusedTextField();

        if (focusedTextField != null && focusedTextField.keyPressed(keyInput)) {
            return true;
        }

        return super.keyPressed(keyInput);
    }

    @Override
    public boolean charTyped(CharInput charInput) {
        TextFieldWidget focusedTextField = getFocusedTextField();

        if (focusedTextField != null && focusedTextField.charTyped(charInput)) {
            return true;
        }

        return super.charTyped(charInput);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        layoutOriginRows();
        renderOriginBlocks(context);
        super.render(context, mouseX, mouseY, delta);

        int left = this.width / 2 - CONTENT_WIDTH / 2;
        int controlX = left + CONTENT_WIDTH - CONTROL_WIDTH;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
        int headerY = listTop - 20;
        int schemeX = left;
        int hostX = schemeX + ORIGIN_SCHEME_WIDTH + 8;
        int portX = left + CONTENT_WIDTH - ORIGIN_REMOVE_WIDTH - 8 - ORIGIN_PORT_WIDTH;
        boolean whitelistEditable = isWhitelistEditable();
        int headerColor = whitelistEditable ? 0xA0A0A0 : 0x707070;

        context.drawTextWithShadow(this.textRenderer, Text.translatable("config.playercoordsapi.option.allowed_origins"), left, headerY - 12, headerColor);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("config.playercoordsapi.option.origin_scheme"), schemeX, headerY, headerColor);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("config.playercoordsapi.option.origin_host"), hostX, headerY, headerColor);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("config.playercoordsapi.option.origin_port"), portX, headerY, headerColor);

        if (getMaxScroll() > 0) {
            int indicatorX = controlX + CONTROL_WIDTH - 8;
            context.drawTextWithShadow(this.textRenderer, Text.literal(scrollOffset > 0 ? "^" : ""), indicatorX, headerY - 12, 0x808080);
            context.drawTextWithShadow(this.textRenderer, Text.literal(scrollOffset < getMaxScroll() ? "v" : ""), indicatorX, listBottom - 8, 0x808080);
        }

        whitelistError.ifPresent(error -> context.drawCenteredTextWithShadow(
                this.textRenderer,
                error,
                this.width / 2,
                this.height - 44,
                0xFF5555
        ));

        renderHoveredTooltip(context, mouseX, mouseY);
    }

    private void saveAndClose() {
        workingConfig.originEntries = collectOriginEntries();
        workingConfig.allowedOrigins = CorsUtils.normalizeConfiguredOriginsFromEntries(workingConfig.originEntries);

        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        config.enabled = workingConfig.enabled;
        config.corsPolicy = workingConfig.corsPolicy;
        config.allowNonBrowserLocalClients = workingConfig.allowNonBrowserLocalClients;
        config.originEntries = new ArrayList<>(workingConfig.originEntries);
        config.allowedOrigins = new ArrayList<>(workingConfig.allowedOrigins);
        AutoConfig.getConfigHolder(ModConfig.class).save();

        close();
    }

    private void updateValidation() {
        whitelistError = validateWhitelist();

        if (addOriginButton != null) {
            addOriginButton.active = isWhitelistEditable() && !hasEmptyOriginDraft();
        }

        if (doneButton != null) {
            doneButton.active = whitelistError.isEmpty();
        }
    }

    private Optional<Text> validateWhitelist() {
        if (workingConfig.corsPolicy != ModConfig.CorsPolicy.CUSTOM_WHITELIST) {
            return Optional.empty();
        }

        if (originDrafts.isEmpty()) {
            return Optional.of(Text.translatable("config.playercoordsapi.option.allowed_origins.error.empty"));
        }

        List<String> normalizedOrigins = new ArrayList<>();

        for (OriginDraft draft : originDrafts) {
            Optional<String> normalizedOrigin = draft.toNormalizedOrigin();

            if (normalizedOrigin.isEmpty()) {
                return Optional.of(Text.translatable("config.playercoordsapi.option.allowed_origins.error.invalid"));
            }

            normalizedOrigins.add(normalizedOrigin.get());
        }

        if (CorsUtils.hasDuplicateOrigins(normalizedOrigins)) {
            return Optional.of(Text.translatable("config.playercoordsapi.option.allowed_origins.error.duplicate"));
        }

        return Optional.empty();
    }

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

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

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

    private int getMaxScroll() {
        int contentHeight = originRows.size() * (ORIGIN_ROW_HEIGHT + ORIGIN_ROW_SPACING) - ORIGIN_ROW_SPACING;
        int visibleHeight = Math.max(0, listBottom - listTop);
        return Math.max(0, contentHeight - visibleHeight);
    }

    private void clampScroll() {
        scrollOffset = MathHelper.clamp(scrollOffset, 0, getMaxScroll());
    }

    private void removeDraft(OriginDraft draft) {
        originDrafts.remove(draft);
        clearAndInit();
    }

    private void syncOriginDraftsWithCorsPolicy() {
        if (workingConfig.corsPolicy == ModConfig.CorsPolicy.CUSTOM_WHITELIST) {
            if (originDrafts.isEmpty()) {
                originDrafts.add(new OriginDraft());
            }

            return;
        }

        originDrafts.removeIf(OriginDraft::isEmpty);
    }

    private boolean hasEmptyOriginDraft() {
        for (OriginDraft draft : originDrafts) {
            if (draft.host == null || draft.host.trim().isEmpty()) {
                return true;
            }
        }

        return false;
    }

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

    private List<Text> getHoveredTooltip(int mouseX, int mouseY) {
        int headerY = listTop - 20;
        int hostX = listLeft + ORIGIN_SCHEME_WIDTH + 8;
        int hostWidth = CONTENT_WIDTH - ORIGIN_SCHEME_WIDTH - ORIGIN_PORT_WIDTH - ORIGIN_REMOVE_WIDTH - 24;
        int portX = listLeft + CONTENT_WIDTH - ORIGIN_REMOVE_WIDTH - 8 - ORIGIN_PORT_WIDTH;

        if (isHoveringLabel(corsPolicyLabel, mouseX, mouseY)) {
            return buildTooltipLines("config.playercoordsapi.option.cors_policy.tooltip", 6, false);
        }

        if (isHoveringLabel(nonBrowserClientsLabel, mouseX, mouseY)) {
            return buildTooltipLines("config.playercoordsapi.option.allow_non_browser_local_clients.tooltip", 4, false);
        }

        if (isWithin(mouseX, mouseY, hostX, headerY, hostWidth, ROW_HEIGHT)) {
            return buildHostTooltip();
        }

        if (isWithin(mouseX, mouseY, portX, headerY, ORIGIN_PORT_WIDTH, ROW_HEIGHT)) {
            return buildTooltip("config.playercoordsapi.option.origin_port.tooltip", true);
        }

        for (OriginRow row : originRows) {
            if (row.hostField.visible && row.hostField.isMouseOver(mouseX, mouseY)) {
                return buildHostTooltip();
            }

            if (row.portField.visible && row.portField.isMouseOver(mouseX, mouseY)) {
                return buildTooltip("config.playercoordsapi.option.origin_port.tooltip", true);
            }
        }

        return List.of();
    }

    private TextFieldWidget getFocusedTextField() {
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

    private List<Text> buildHostTooltip() {
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip"));
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip.localhost")
                .append(Text.literal(" localhost").formatted(Formatting.GREEN)));
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip.domain")
                .append(Text.literal(" example.com").formatted(Formatting.GREEN)));
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip.ipv4")
                .append(Text.literal(" 127.0.0.1").formatted(Formatting.GREEN)));
        tooltip.add(Text.translatable("config.playercoordsapi.option.origin_host.tooltip.ipv6")
                .append(Text.literal(" 2001:db8::1").formatted(Formatting.GREEN)));

        if (!isWhitelistEditable()) {
            tooltip.add(Text.translatable("config.playercoordsapi.option.allowed_origins.disabled").formatted(Formatting.GRAY));
        }

        return tooltip;
    }

    private List<Text> buildTooltip(String key, boolean includeWhitelistDisabledHint) {
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.translatable(key));

        if (includeWhitelistDisabledHint && !isWhitelistEditable()) {
            tooltip.add(Text.translatable("config.playercoordsapi.option.allowed_origins.disabled").formatted(Formatting.GRAY));
        }

        return tooltip;
    }

    private List<Text> buildTooltipLines(String keyPrefix, int lineCount, boolean includeWhitelistDisabledHint) {
        List<Text> tooltip = new ArrayList<>(lineCount + 1);

        for (int i = 1; i <= lineCount; i++) {
            tooltip.add(Text.translatable(keyPrefix + "." + i));
        }

        if (includeWhitelistDisabledHint && !isWhitelistEditable()) {
            tooltip.add(Text.translatable("config.playercoordsapi.option.allowed_origins.disabled").formatted(Formatting.GRAY));
        }

        return tooltip;
    }

    private static boolean isWithin(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static boolean isHoveringLabel(TextWidget label, int mouseX, int mouseY) {
        return isWithin(mouseX, mouseY, label.getX(), label.getY(), label.getWidth(), ROW_HEIGHT);
    }

    private boolean isWhitelistEditable() {
        return workingConfig.corsPolicy == ModConfig.CorsPolicy.CUSTOM_WHITELIST;
    }

    private static Text getCorsPolicyLabel(ModConfig.CorsPolicy policy) {
        return Text.translatable("config.playercoordsapi.option.cors_policy." + policy.name().toLowerCase(Locale.ROOT));
    }

    private static Text getOriginSchemeModeLabel(ModConfig.OriginSchemeMode mode) {
        return Text.translatable("config.playercoordsapi.option.origin_scheme." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static List<OriginDraft> createDrafts(List<ModConfig.OriginEntry> originEntries, List<String> origins) {
        List<OriginDraft> drafts = new ArrayList<>();

        if (originEntries != null && !originEntries.isEmpty()) {
            for (ModConfig.OriginEntry originEntry : originEntries) {
                if (originEntry != null) {
                    drafts.add(OriginDraft.fromConfigEntry(originEntry));
                }
            }

            return drafts;
        }

        for (String origin : origins) {
            CorsUtils.createConfiguredOriginEntry(origin)
                    .map(OriginDraft::fromConfigEntry)
                    .ifPresent(drafts::add);
        }

        return drafts;
    }

    private final class OriginRow {
        private final CyclingButtonWidget<ModConfig.OriginSchemeMode> schemeButton;
        private final TextFieldWidget hostField;
        private final TextFieldWidget portField;
        private final ButtonWidget removeButton;

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

        private boolean tryFocusField(Click click, boolean doubleClick) {
            if (!hostField.visible || !hostField.active) {
                return false;
            }

            if (hostField.isMouseOver(click.x(), click.y()) && hostField.mouseClicked(click, doubleClick)) {
                hostField.setFocused(true);
                portField.setFocused(false);
                PlayerCoordsConfigScreen.this.setFocused(hostField);
                return true;
            }

            if (portField.isMouseOver(click.x(), click.y()) && portField.mouseClicked(click, doubleClick)) {
                portField.setFocused(true);
                hostField.setFocused(false);
                PlayerCoordsConfigScreen.this.setFocused(portField);
                return true;
            }

            return false;
        }
    }

    private static final class OriginDraft {
        private ModConfig.OriginSchemeMode mode;
        private String host;
        private String port;

        private OriginDraft() {
            this(ModConfig.OriginSchemeMode.AUTO, "", "");
        }

        private OriginDraft(ModConfig.OriginSchemeMode mode, String host, String port) {
            this.mode = mode;
            this.host = host;
            this.port = port;
        }

        private static OriginDraft fromConfigEntry(ModConfig.OriginEntry originEntry) {
            return new OriginDraft(
                    originEntry.schemeMode == null ? ModConfig.OriginSchemeMode.AUTO : originEntry.schemeMode,
                    originEntry.host == null ? "" : originEntry.host,
                    originEntry.port == null ? "" : originEntry.port
            );
        }

        private ModConfig.OriginEntry toConfigEntry() {
            ModConfig.OriginEntry originEntry = new ModConfig.OriginEntry();
            originEntry.schemeMode = mode;
            originEntry.host = host;
            originEntry.port = port;
            return originEntry;
        }

        private Optional<String> toNormalizedOrigin() {
            return CorsUtils.normalizeConfiguredOrigin(host, port, mode);
        }

        private boolean isEmpty() {
            return host == null || host.trim().isEmpty();
        }
    }
}
