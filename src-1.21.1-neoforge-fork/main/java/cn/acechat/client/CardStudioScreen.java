package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SACS-1.21.1-Neoforge-fork · 角色卡工坊（分页 + 每页独立滚动）。
 *
 * <pre>
 * ┌──────────────────────────────┬─────────────────────────────┐
 * │ 实际样式预览（形象框+聊天框） │ 8 个页签（高亮当前页）        │
 * ├──────────────────────────────┤ ┌─────────────────────────┐ │
 * │ 小比例屏幕示意（相对位置）    │ │ 当前页字段（本页滚动）   │ │
 * │                              │ └─────────────────────────┘ │
 * └──────────────────────────────┴─────────────────────────────┘
 * </pre>
 */
public class CardStudioScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final int TOP = 26;
    private static final int BOTTOM = 16;
    private static final int ROW = 22;
    private static final String[] TABS = {"聊天", "形象", "文字", "颜色", "管理", "剧本"};

    private final Screen parent;
    private String selected = "";
    private String displayName = "";
    private boolean bound = true;
    private String skinRef = "";
    private String portraitRef = "";

    private final List<String> skins = new ArrayList<>();
    private final List<String> portraits = new ArrayList<>();
    private final List<String> cards = new ArrayList<>();

    private int tab;
    private final int[] pageScroll = new int[TABS.length];
    private int maxScroll;
    private boolean draggingBar;

    private int leftW;
    private int panelX, panelW;
    private int previewX, previewY, previewW, previewH;
    private int miniX, miniY, miniW, miniH;
    private int contentTop, contentBottom;

    private int cursorY;
    private boolean buildingWidgets;
    private final List<int[]> labelLines = new ArrayList<>();
    private final List<String> labelTexts = new ArrayList<>();
    private int textSel;
    private EditBox cardNameBox;

    // 内嵌取色面板（不跳转界面）
    private boolean colorOpen;
    private int colorX, colorY;
    private static final int COLOR_W = 224;
    private static final int COLOR_H = 152;
    private final int[] color = new int[4];
    private java.util.function.IntConsumer colorApply;
    private int colorDrag = -1;
    /** 取色拖动期间只改进内存，松手/收起时统一写盘一次。 */
    private boolean colorDirty;
    private EditBox newCardBox;
    /** 剧本页：工程名输入框 / 当前选中工程 / 操作结果提示。 */
    private EditBox stageNameBox;
    private String stageNameInput = "";
    private String stageSelected = "";
    private String stageStatus = "";
    private String stageDeleteTarget = "";
    private long stageDeleteAt;
    /** 工坊内一次操作的提示（导入/模板/在线建卡等）。 */
    private String actionStatus = "";
    private String filterTemplateInput = "";
    private boolean nameClearArmed;
    private long nameClearAt;

    public CardStudioScreen(Screen parent) {
        super(Component.literal("角色卡工坊"));
        this.parent = parent;
    }

    // 自动保存：改动后 400ms 内没有新改动就落盘；顶部「保存」按钮仍可手动立即保存
    private boolean savePending;
    private long saveAt;

    // ───────────────────────── 生命周期 ─────────────────────────

    private void autoSave() {
        this.savePending = true;
        this.saveAt = System.currentTimeMillis() + 400L;
    }

    private void flushAutoSave() {
        if (this.savePending && System.currentTimeMillis() >= this.saveAt) {
            this.savePending = false;
            CommsConfig.save();
        }
    }

    /** 关界面/退出前无条件补写：400ms 防抖没到也要落盘，避免“输完名字马上 Esc”丢改动。 */
    private void flushAutoSaveNow() {
        if (this.savePending) {
            this.savePending = false;
            CommsConfig.save();
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        CommsConfig.setEditingScreen(true);
        CommsHud.setPreviewForced(true);
        if (this.selected == null || this.selected.isBlank() || !PlayerCards.exists(this.selected)) {
            this.selected = CommsConfig.currentCard();
        }
        // 打开工坊时强制套用“正在编辑的卡”：渲染期可能临时套用了说话人的卡，
        // 否则会出现标题是自己的卡、文字元素却是别人卡的情况（v0.1.3.6）。
        if (!this.selected.equals(CommsConfig.appliedCardName())) {
            CommsConfig.applyCardForEditing(this.selected);
        }
        reloadLists();
        readCharacter();

        this.leftW = Math.max(300, Math.min(560, (int) (this.width * 0.46)));
        this.panelX = this.leftW + 6;
        this.panelW = this.width - this.panelX - 4;

        int leftInner = this.leftW - 24;
        this.previewX = 10;
        this.previewY = TOP + 14;
        this.previewW = Math.max(180, leftInner);
        this.previewH = Math.max(160, (int) (this.height * 0.40));

        int areaTop = this.previewY + this.previewH + 18;
        int areaBottom = this.height - BOTTOM - 8;
        int availW = Math.max(160, leftInner);
        int availH = Math.max(90, areaBottom - areaTop);
        this.miniW = Math.min(availW, (int) (availH * 16.0 / 9.0));
        this.miniH = (int) (this.miniW * 9.0 / 16.0);
        if (this.miniH > availH) {
            this.miniH = availH;
            this.miniW = (int) (this.miniH * 16.0 / 9.0);
        }
        this.miniX = 10 + (availW - this.miniW) / 2;
        this.miniY = areaTop + (availH - this.miniH) / 2;

        // 排查用：README 里让看的就是这一行（尺寸 + 预览区 + 屏幕示意）。
        LOGGER.info("[acechat] 卡工坊已打开（尺寸 {}×{}，预览区 {}×{}，屏幕示意 {}×{}）",
                this.width, this.height, this.previewW, this.previewH, this.miniW, this.miniH);

        this.contentTop = TOP + 46;
        this.contentBottom = this.height - BOTTOM - 2;

        layoutPage();
    }

    private void reloadLists() {
        this.cards.clear();
        this.cards.addAll(PlayerCards.list());
        this.skins.clear();
        this.skins.add("");
        for (CardAssets.Entry entry : CardAssets.skins()) {
            if (entry.usable() || entry.needsScale()) {
                this.skins.add(entry.reference());
            }
        }
        this.portraits.clear();
        this.portraits.add("");
        for (CardAssets.Entry entry : CardAssets.portraits()) {
            if (entry.usable() || entry.needsScale()) {
                this.portraits.add(entry.fileName());
            }
        }
    }

    private void readCharacter() {
        CardCharacter.Data data = CardCharacter.read(this.selected);
        this.displayName = data.displayName == null ? "" : data.displayName;
        this.bound = data.boundPlayer;
        this.skinRef = data.skin == null ? "" : data.skin;
        this.portraitRef = data.portrait == null ? "" : data.portrait;
    }

    // ───────────────────────── 框架 ─────────────────────────

    private void buildChrome() {
        this.addRenderableWidget(Button.builder(Component.literal("角色卡：" + this.selected + " ▸"), b ->
                Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "选择角色卡", this.cards, value -> {
                    CommsConfig.selectCard(value);
                    this.selected = value;
                    this.tab = 0;
                    this.init();
                }))).bounds(8, 4, Math.min(260, this.width / 3), 16).build());

        this.addRenderableWidget(Button.builder(Component.literal("保存"), b -> {
            writeCharacter();
            CommsConfig.save();
        }).bounds(this.width - 106, 4, 48, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("重读"), b -> this.init())
                .bounds(this.width - 54, 4, 48, 16).build());

        // 6 个页签：3 列 × 2 行，宽度按可用空间算，留出滚动条余量
        int gridW = Math.min(this.panelW - 12, this.width - this.panelX - 22);
        int bw = Math.max(48, gridW / 3);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            int col = i % 3;
            int row = i / 3;
            int x = this.panelX + 4 + col * bw;
            int y = TOP + 2 + row * 17;
            if (x + bw - 2 > this.width - 14) {
                continue;   // 放不下就不画，绝不越界
            }
            String label = (index == this.tab ? "◆ " : "") + TABS[i];
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                this.tab = index;
                this.actionStatus = "";
                this.nameClearArmed = false;
                layoutPage();
            }).bounds(x, y, bw - 2, 15).build());
        }
    }

    /** 只布局当前页：先测量高度，再按本页滚动偏移创建控件。 */
    private void layoutPage() {
        this.clearWidgets();
        buildChrome();
        this.labelLines.clear();
        this.labelTexts.clear();

        this.buildingWidgets = false;
        this.cursorY = this.contentTop;
        buildPage(false);
        int height = this.cursorY - this.contentTop;
        int view = Math.max(20, this.contentBottom - this.contentTop);
        this.maxScroll = Math.max(0, height - view);
        this.pageScroll[this.tab] = Math.max(0, Math.min(this.pageScroll[this.tab], this.maxScroll));

        this.labelLines.clear();
        this.labelTexts.clear();
        this.buildingWidgets = true;
        this.cursorY = this.contentTop - this.pageScroll[this.tab];
        buildPage(true);
    }

    private void buildPage(boolean widgets) {
        this.buildingWidgets = widgets;
        this.cursorY = widgets ? this.contentTop - this.pageScroll[this.tab] : this.contentTop;
        switch (this.tab) {
            case 0 -> chatPage();
            case 1 -> {
                sectionLabel("身份");
                identityPage();
                sectionLabel("外观");
                appearancePage();
            }
            case 2 -> textPage();
            case 3 -> colorPage();
            case 4 -> managePage();
            default -> stagePage();
        }
    }

    /** 页内小标题（画在控件层下面，滚动时跟着走）。 */
    private void sectionLabel(String text) {
        if (this.buildingWidgets && visible(this.cursorY)) {
            this.labelLines.add(new int[]{this.cursorY, 0xFFFFD27A, this.labelTexts.size()});
            this.labelTexts.add("◆ " + text);
        }
        this.cursorY += 22;
    }

    // ───────────────────────── 各页 ─────────────────────────

    private void identityPage() {
        EditBox box = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16, Component.literal("显示名"));
        box.setMaxLength(64);
        box.setValue(this.displayName);
        box.setResponder(value -> {
            this.displayName = value;
            writeCharacter();
            autoSave();
        });
        addWidget(box);
        this.cursorY += ROW;

        addToggleRow("关联玩家名", this.bound, () -> {
            this.bound = !this.bound;
            writeCharacter();
        });
        addCycleRow("皮肤", this.skinRef.isBlank() ? "（未指定）" : this.skinRef, () ->
                Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "选择皮肤", this.skins, value -> {
                    this.skinRef = value;
                    writeCharacter();
                    this.init();
                })));
        addCycleRow("立绘", this.portraitRef.isBlank() ? "（未指定）" : this.portraitRef, () ->
                Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "选择立绘", this.portraits, value -> {
                    this.portraitRef = value;
                    writeCharacter();
                    this.init();
                })));

        String skinText = this.skinRef.isBlank() ? "跟随玩家皮肤" : this.skinRef;
        String artText = this.portraitRef.isBlank() ? "未指定" : this.portraitRef;
        String missing = !this.skinRef.isBlank() && !this.skins.contains(this.skinRef)
                ? "（皮肤文件未找到）" : "";
        String artMissing = !this.portraitRef.isBlank() && !this.portraits.contains(this.portraitRef)
                ? "（立绘文件未找到）" : "";
        addInfoRow(trim("皮肤：" + skinText + missing + "　立绘：" + artText + artMissing, 70));
        int dirHalf = Math.max(80, (fieldW() - 6) / 2);
        addButton("重新扫描立绘目录", fieldX(), this.cursorY, dirHalf, () -> {
            PortraitFiles.rescan();
            this.actionStatus = "已重新扫描立绘目录";
            this.init();
        });
        addButton("打开立绘目录", fieldX() + dirHalf + 6, this.cursorY, dirHalf,
                () -> openPath(PortraitFiles.portraitDir()));
        this.cursorY += ROW;
    }

    private void appearancePage() {
        String[] modes = {"只画头像", "头肩", "半身", "整张原图", "全身"};
        addCycleRow("照片模式", modes[Math.max(0, Math.min(4, CommsConfig.portraitPhotoMode.get()))], () -> {
            CommsConfig.portraitPhotoMode.set((CommsConfig.portraitPhotoMode.get() + 1) % modes.length);
            this.layoutPage();
        });
        addSliderRow("照片区高度", CommsConfig.portraitPhotoHeight, 30, 90, "");
        addToggleRowRaw("照片分隔线", CommsConfig.portraitPhotoDivider);
        addToggleRowRaw("外层皮肤", CommsConfig.portraitOuterLayer);
        addSliderRow("半身像缩放", CommsConfig.portraitBustScale, 40, 150, "");
        addSliderRow("立绘缩放", CommsConfig.portraitImageScale, 10, 400, " %");
        addSliderRow("立绘 X", CommsConfig.portraitImageOffsetX, -200, 200, "");
        addSliderRow("立绘 Y", CommsConfig.portraitImageOffsetY, -200, 200, "");
        addSliderRowDouble("整体缩放", CommsConfig.portraitScale, 0.5, 2.0);
        addSliderRow("宽", CommsConfig.portraitWidth, 80, 300, "");
        addSliderRow("高", CommsConfig.portraitHeight, 90, 340, "");
        addSliderRow("X 偏移", CommsConfig.portraitXOffset, -300, 300, "");
        addSliderRow("Y 偏移", CommsConfig.portraitYOffset, -300, 300, "");
        addSliderRow("皮肤 X", CommsConfig.skinOffsetX, -120, 120, "");
        addSliderRow("皮肤 Y", CommsConfig.skinOffsetY, -120, 120, "");
        addSliderRow("不透明度", CommsConfig.portraitOpacity, 0, 100, "");
        addSliderRow("亮度", CommsConfig.portraitBrightness, 30, 100, "");
        addColorRow("颜色蒙版", CommsConfig.portraitTint);
        addToggleRowRaw("背景板", CommsConfig.portraitBackdropEnabled);
        addColorRow("背景板颜色", CommsConfig.portraitBackdropColor);
        addSliderRow("背景板透明", CommsConfig.portraitBackdropAlpha, 0, 100, "");
        addSliderRow("背景板内边距", CommsConfig.portraitBackdropPadding, 0, 16, "");
        addColorRow("面板颜色", CommsConfig.portraitPanelColor);
        addSliderRow("面板透明", CommsConfig.portraitPanelAlpha, 0, 100, "");
        addColorRow("边框颜色", CommsConfig.portraitBorderColor);
        addSliderRow("边框粗细", CommsConfig.portraitBorderThickness, 0, 2, "");

        sectionLabel("死亡覆盖");
        addToggleRowRaw("启用死亡覆盖", CommsConfig.deathOverlayEnabled);
        boolean noSignal = DeathPortraits.PRESET_NO_SIGN.equals(CommsConfig.deathPreset.get());
        addCycleRow("预设", noSignal ? "NO SIGN（内置）" : "只用 deaths 目录文件", () -> {
            CommsConfig.deathPreset.set(noSignal ? "" : DeathPortraits.PRESET_NO_SIGN);
            DeathPortraits.clearCache();
            CommsConfig.save();
            this.layoutPage();
        });
        addSliderRow("最大尺寸", CommsConfig.deathImageMaxSize, 64, 1024, " px");
        addSliderRow("覆盖缩放", CommsConfig.deathImageScale, 20, 400, " %");
        addSliderRow("覆盖 X 偏移", CommsConfig.deathImageOffsetX, -200, 200, "");
        addSliderRow("覆盖 Y 偏移", CommsConfig.deathImageOffsetY, -200, 200, "");
        addButton("打开死亡图目录", fieldX(), this.cursorY, fieldW(), () -> {
            try {
                net.minecraft.Util.getPlatform().openUri(DeathPortraits.deathsDir().toUri().toString());
            } catch (RuntimeException ignored) {
                // 打不开就算了
            }
        });
        this.cursorY += ROW;
    }

    private void textPage() {
        List<CommsConfig.TextElement> elements = elements();
        int shown = elements.size();
        for (int i = 0; i < shown; i++) {
            final int index = i;
            CommsConfig.TextElement element = elements.get(i);
            addCycleRow((i == this.textSel ? "▶ " : "") + (i < 4 ? builtInName(i) : "自定义"),
                    trim(element.content, 12), () -> {
                        this.textSel = index;
                        this.init();
                    });
        }
        List<CommsConfig.TextElement> current = elements();
        if (this.textSel >= current.size()) {
            this.textSel = Math.max(0, current.size() - 1);
        }
        CommsConfig.TextElement element = current.isEmpty() ? null : current.get(this.textSel);
        if (element == null) {
            return;
        }
        EditBox box = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16, Component.literal("内容"));
        box.setMaxLength(256);
        box.setValue(element.content);
        box.setResponder(value -> {
            element.content = value;
            CommsConfig.save();
        });
        addWidget(box);
        this.cursorY += ROW;

        addElemSlider("字号", element, 6, 24, e -> e.size, (e, v) -> e.size = v);
        addElemSlider("X", element, -300, 300, e -> e.x, (e, v) -> e.x = v);
        addElemSlider("Y", element, -300, 300, e -> e.y, (e, v) -> e.y = v);
        addElemSlider("最大宽", element, 20, 400, e -> e.maxWidth, (e, v) -> e.maxWidth = v);

        int half = Math.max(80, (fieldW() - 6) / 2);
        addButton("水平：" + alignH(element), fieldX(), this.cursorY, half, () -> {
            element.horizontal = CommsConfig.Horizontal.values()[(element.horizontal.ordinal() + 1) % 3];
            CommsConfig.save();
            this.init();
        });
        addButton("垂直：" + alignV(element), fieldX() + half + 6, this.cursorY, half, () -> {
            element.vertical = CommsConfig.Vertical.values()[(element.vertical.ordinal() + 1) % 3];
            CommsConfig.save();
            this.init();
        });
        this.cursorY += ROW;

        addButton("阴影：" + (element.shadow ? "开" : "关"), fieldX(), this.cursorY, half, () -> {
            element.shadow = !element.shadow;
            CommsConfig.save();
            this.init();
        });
        addButton("显示：" + (isVisible(element) ? "开" : "关"), fieldX() + half + 6, this.cursorY, half, () -> {
            element.visible = !isVisible(element);
            CommsConfig.save();
            this.init();
        });
        this.cursorY += ROW;

        addButton("颜色", fieldX(), this.cursorY, half, () ->
                openColor(element.color, argb -> element.color = argb));
        addButton("+ 新元素", fieldX() + half + 6, this.cursorY, half, () -> {
            CommsConfig.TextElement created = new CommsConfig.TextElement("自定义文字",
                    CommsConfig.Horizontal.LEFT, CommsConfig.Vertical.TOP, 0, 0, 9, 0xFFFFFFFF, true, 160);
            created.kind = CommsConfig.KIND_CUSTOM;
            created.visible = Boolean.TRUE;
            CommsConfig.textElements.add(created);
            CommsConfig.save();
            this.textSel = elements().size() - 1;
            this.init();
        });
        this.cursorY += ROW;

        addButton("复制", fieldX(), this.cursorY, half, () -> {
            CommsConfig.TextElement copy = element.copy();
            copy.kind = CommsConfig.KIND_CUSTOM;
            CommsConfig.textElements.add(copy);
            CommsConfig.save();
            this.textSel = elements().size() - 1;
            this.init();
        });
        addButton("删除", fieldX() + half + 6, this.cursorY, half, () -> {
            if (CommsConfig.KIND_CUSTOM.equals(element.kind)) {
                CommsConfig.textElements.remove(element);
                CommsConfig.save();
                this.textSel = Math.max(0, this.textSel - 1);
            }
            this.init();
        });
        this.cursorY += ROW;

        addButton("安全重置位置（保留内容/颜色/字号）", fieldX(), this.cursorY, fieldW(), () -> {
            CommsConfig.resetElementPositions();
            this.init();
        });
        this.cursorY += ROW;

        sectionLabel("名字颜色（角色卡）");
        addColorRow("默认名字颜色", CommsConfig.chatNameColor);

        // 本卡默认名字颜色：不用给玩家加条目，套用到这张卡时就用它（演出也生效）。
        Integer ownColor = CommsConfig.cardNameColorArgb();
        int ownShown = ownColor == null ? 0xFFFFFFFF : ownColor;
        addButton("本卡名字颜色：" + (ownColor == null ? "跟随全局" : String.format("#%06X", ownShown & 0xFFFFFF)),
                fieldX(), this.cursorY, Math.max(80, fieldW() - 52), () ->
                        openColor(ownShown, CommsConfig::setCardNameColorArgb));
        addButton("清除", fieldX() + fieldW() - 46, this.cursorY, 46, () -> {
            CommsConfig.clearCardNameColor();
            CommsConfig.save();
            this.layoutPage();
        });
        this.cursorY += ROW;

        // 符号颜色不再单独设置：统一跟随名字颜色（HUD / 原版回放 / 注入历史都同一套）
        Button symbolInfo = Button.builder(Component.literal("符号颜色：跟随名字颜色（不单独设置）"), b -> { })
                .bounds(fieldX(), this.cursorY, fieldW(), 16).build();
        symbolInfo.active = false;
        addWidget(symbolInfo);
        this.cursorY += ROW;

        int half2 = Math.max(80, (fieldW() - 6) / 2);
        addButton("+ 新增一条", fieldX(), this.cursorY, half2, () -> {
            // 名字颜色跟「角色卡」绑定：条目名＝卡名，演出里这张卡发言时命中
            CommsConfig.NameColor created = new CommsConfig.NameColor("", this.selected, 0xFFFFFFFF, "");
            CommsConfig.nameColors.add(created);
            CommsConfig.save();
            openColor(created.color, argb -> created.color = argb);   // 落盘由取色面板统一提交
            this.layoutPage();
        });
        this.cursorY += ROW;

        int count = CommsConfig.nameColors.size();
        for (int i = 0; i < count; i++) {
            CommsConfig.NameColor item = CommsConfig.nameColors.get(i);
            String label = item.name.isBlank() ? ("玩家 " + (i + 1)) : item.name;
            addButton(label + "  #" + String.format("%06X", item.color & 0xFFFFFF),
                    fieldX(), this.cursorY, Math.max(80, fieldW() - 92), () ->
                            openColor(item.color, argb -> item.color = argb));
            addButton("复制", fieldX() + fieldW() - 88, this.cursorY, 42, () -> {
                CommsConfig.nameColors.add(new CommsConfig.NameColor(item.uuid, item.name, item.color, item.note));
                CommsConfig.save();
                this.layoutPage();
            });
            addButton("删", fieldX() + fieldW() - 42, this.cursorY, 42, () -> {
                CommsConfig.nameColors.remove(item);
                CommsConfig.save();
                this.layoutPage();
            });
            this.cursorY += ROW;
        }
        if (!CommsConfig.nameColors.isEmpty()) {
            boolean armed = this.nameClearArmed && System.currentTimeMillis() - this.nameClearAt < 5000;
            addButton(armed ? "再点一次清空全部" : "清空全部名字颜色条目", fieldX(), this.cursorY, fieldW(), () -> {
                if (armed) {
                    CommsConfig.nameColors.clear();
                    CommsConfig.save();
                    this.nameClearArmed = false;
                    this.actionStatus = "已清空名字颜色条目";
                } else {
                    this.nameClearArmed = true;
                    this.nameClearAt = System.currentTimeMillis();
                    this.actionStatus = "再点一次确认清空";
                }
                this.layoutPage();
            });
            this.cursorY += ROW;
        }
        addStatusRow();
    }

    private void colorPage() {
        addToggleRowRaw("启用调色", CommsConfig.gradeEnabled);
        addSliderRow("亮度", CommsConfig.gradeBrightness, 30, 180, "");
        addSliderRow("对比", CommsConfig.gradeContrast, 30, 200, "");
        addSliderRow("饱和", CommsConfig.gradeSaturation, 0, 200, "");
        addSliderRow("色相", CommsConfig.gradeHue, -180, 180, "");
        addToggleRowRaw("双色调", CommsConfig.duotoneEnabled);
        int half = Math.max(80, (fieldW() - 6) / 2);
        addButton("暗色", fieldX(), this.cursorY, half, () ->
                openColor(CommsConfig.duotoneDark.get(), argb -> {
                    CommsConfig.duotoneDark.set(argb);
                    PortraitGrade.invalidate();
                }));
        addButton("亮色", fieldX() + half + 6, this.cursorY, half, () ->
                openColor(CommsConfig.duotoneLight.get(), argb -> {
                    CommsConfig.duotoneLight.set(argb);
                    PortraitGrade.invalidate();
                }));
        this.cursorY += ROW;
        addSliderRow("双色调强度", CommsConfig.duotoneAmount, 0, 100, "");

        addToggleRowRaw("启用滤镜", CommsConfig.filterEnabled);
        List<String> names = new ArrayList<>();
        for (String id : PortraitFilters.BUILTIN_IDS) {
            names.add(id);
        }
        for (CommsConfig.FilterTemplate template : CommsConfig.filterTemplates) {
            names.add(template.filterName);
        }
        int index = names.indexOf(CommsConfig.filterName.get());
        addCycleRow("滤镜", index < 0 ? "（无）" : shortFilter(names.get(index)), () -> {
            List<String> all = new ArrayList<>();
            for (String id : PortraitFilters.BUILTIN_IDS) {
                all.add(id);
            }
            for (CommsConfig.FilterTemplate template : CommsConfig.filterTemplates) {
                all.add(template.filterName);
            }
            int i = all.indexOf(CommsConfig.filterName.get());
            CommsConfig.filterName.set(all.get((i + 1) % Math.max(1, all.size())));
            this.layoutPage();
        });
        addSliderRow("滤镜强度", CommsConfig.filterAlpha, 0, 100, "");
        addButton("混合：" + blendText(), fieldX(), this.cursorY, half, () -> {
            CommsConfig.filterBlend.set((CommsConfig.filterBlend.get() + 1) % 4);
            this.layoutPage();
        });
        addButton("滤染色", fieldX() + half + 6, this.cursorY, half, () ->
                openColor(CommsConfig.filterTint.get(), argb -> CommsConfig.filterTint.set(argb)));
        this.cursorY += ROW;
        addSliderRow("滤镜亮度", CommsConfig.filterBrightness, 30, 180, "");
        addSliderRow("滤镜对比", CommsConfig.filterContrast, 30, 200, "");
        addSliderRow("滤镜饱和", CommsConfig.filterSaturation, 0, 200, "");
        addSliderRow("滤镜色相", CommsConfig.filterHue, -180, 180, "");

        sectionLabel("滤镜模板");
        EditBox templateName = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16,
                Component.literal("模板名"));
        templateName.setMaxLength(48);
        templateName.setValue(this.filterTemplateInput);
        templateName.setResponder(value -> this.filterTemplateInput = value);
        addWidget(templateName);
        this.cursorY += ROW;

        int tbw = Math.max(70, (fieldW() - 12) / 3);
        addButton("保存模板", fieldX(), this.cursorY, tbw, () -> {
            String name = this.filterTemplateInput == null ? "" : this.filterTemplateInput.trim();
            if (name.isEmpty()) {
                name = "模板 " + (CommsConfig.filterTemplates.size() + 1);
            }
            final String target = name;
            CommsConfig.filterTemplates.removeIf(t -> t.name != null && t.name.equalsIgnoreCase(target));
            CommsConfig.filterTemplates.add(CommsConfig.captureTemplate(target));
            this.filterTemplateInput = target;
            CommsConfig.save();
            this.actionStatus = "已保存模板 " + target;
            this.layoutPage();
        });
        addButton("删除模板", fieldX() + tbw + 6, this.cursorY, tbw, () -> {
            String name = this.filterTemplateInput == null ? "" : this.filterTemplateInput.trim();
            boolean removed = name.isEmpty() ? false
                    : CommsConfig.filterTemplates.removeIf(t -> t.name != null && t.name.equalsIgnoreCase(name));
            if (removed) {
                CommsConfig.save();
                this.actionStatus = "已删除模板 " + name;
            } else {
                this.actionStatus = "没有找到模板：" + (name.isEmpty() ? "（名字为空）" : name);
            }
            this.layoutPage();
        });
        addButton("套用模板…", fieldX() + (tbw + 6) * 2, this.cursorY, tbw, this::applyTemplateDialog);
        this.cursorY += ROW;
        addStatusRow();
    }

    /** 选一个模板，连同调色/滤镜参数一起套用。 */
    private void applyTemplateDialog() {
        if (CommsConfig.filterTemplates.isEmpty()) {
            this.actionStatus = "还没有保存过滤镜模板";
            this.layoutPage();
            return;
        }
        List<String> names = new ArrayList<>();
        for (CommsConfig.FilterTemplate template : CommsConfig.filterTemplates) {
            names.add(template.name == null ? "" : template.name);
        }
        Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "套用滤镜模板", names, picked -> {
            for (CommsConfig.FilterTemplate template : CommsConfig.filterTemplates) {
                if (template.name != null && template.name.equalsIgnoreCase(picked)) {
                    CommsConfig.applyTemplate(template);
                    PortraitGrade.invalidate();
                    CommsConfig.save();
                    this.filterTemplateInput = picked;
                    this.actionStatus = "已套用模板 " + picked;
                    break;
                }
            }
            this.init();
        }));
    }

    private void chatPage() {
        addToggleRowRaw("通讯框启用", CommsConfig.hudEnabled);
        addToggleRowRaw("玩家消息走原版", CommsConfig.vanillaPlayerChat);
        addToggleRowRaw("其它消息走原版", CommsConfig.vanillaOtherMessages);
        addToggleRowRaw("帧耗时调试", CommsConfig.debugTiming);
        addSliderRow("屏幕显示行数", CommsConfig.chatVisibleLines, 0, 30, "");
        addSliderRow("聊天界面行数", CommsConfig.chatScreenLines, 0, 30, "");
        addSliderRow("框宽", CommsConfig.boxWidth, 120, 900, "");
        addSliderRow("最大行数", CommsConfig.maxLines, 1, 12, "");
        addSliderRow("X 偏移", CommsConfig.centerXOffset, -400, 400, "");
        addSliderRow("Y 偏移", CommsConfig.topYOffset, 0, 400, "");
        addSliderRowDouble("背景透明度", CommsConfig.backgroundAlpha, 0.0, 1.0);
        addColorRow("背景颜色", CommsConfig.chatBackgroundColor);
        addSliderRow("右间距", CommsConfig.chatRightGap, 0, 240, "");
        addSliderRow("左间距", CommsConfig.chatLeftMargin, 0, 240, "");
        addSliderRow("名字最大宽", CommsConfig.chatNameMaxWidth, 20, 400, "");
        addSliderRow("符号间距", CommsConfig.chatSymbolPadding, 0, 16, "");
        addSliderRow("左右内边距", CommsConfig.chatPaddingX, 0, 8, "");
        addSliderRow("上下内边距", CommsConfig.chatPaddingY, 0, 8, "");
        addToggleRowRaw("边框", CommsConfig.chatBorderEnabled);
        addColorRow("边框颜色", CommsConfig.chatBorderColor);
        addSliderRow("边框粗细", CommsConfig.chatBorderThickness, 0, 2, "");
        String[] textEffects = {"原版阴影", "描边", "无"};
        addCycleRow("文字效果", textEffects[Math.max(0, Math.min(2, CommsConfig.chatTextEffect.get()))], () -> {
            CommsConfig.chatTextEffect.set((CommsConfig.chatTextEffect.get() + 1) % 3);
            CommsConfig.save();
            this.layoutPage();
        });
        addColorRow("描边颜色", CommsConfig.chatTextOutlineColor);
        addSliderRowDouble("单条停留", CommsConfig.lineHoldSeconds, 0.5, 60.0);
        addSliderRowDouble("停留下限", CommsConfig.holdMinSeconds, 0.0, 5.0);
        addSliderRowDouble("停留上限", CommsConfig.holdMaxSeconds, 1.0, 15.0);

        sectionLabel("音效");
        addToggleRowRaw("新消息音效", CommsConfig.blipEnabled);
        addCycleRow("出现音效", presetLabel(CommsConfig.newSoundPreset.get(), true), () -> pickPreset(true));
        addSliderRow("新·音量", CommsConfig.newSoundVolume, 0, 200, "");
        addSliderRow("新·音调", CommsConfig.newSoundPitch, 50, 200, "");
        addSliderRow("新·速度", CommsConfig.newSoundSpeed, 25, 400, "");
        addSliderRow("新·淡入", CommsConfig.newSoundFadeIn, 0, 200, "");
        addSliderRow("新·淡出", CommsConfig.newSoundFadeOut, 0, 400, "");
        addToggleRowRaw("消失音效", CommsConfig.goneSoundEnabled);
        addCycleRow("消失音效预设", presetLabel(CommsConfig.goneSoundPreset.get(), false), () -> pickPreset(false));
        addSliderRow("消·音量", CommsConfig.goneSoundVolume, 0, 200, "");
        addSliderRow("消·音调", CommsConfig.goneSoundPitch, 50, 200, "");
        addSliderRow("消·速度", CommsConfig.goneSoundSpeed, 25, 400, "");
        addSliderRow("消·淡入", CommsConfig.goneSoundFadeIn, 0, 200, "");
        addSliderRow("消·淡出", CommsConfig.goneSoundFadeOut, 0, 400, "");
        addSliderRowDouble("总音量", CommsConfig.soundVolume, 0.0, 1.0);
        addButton("试听两个音效", fieldX(), this.cursorY, fieldW(), () -> CommsState.playTestSounds());
        this.cursorY += ROW;
        addButton("打开音效目录（自定义 new_message / message_gone）", fieldX(), this.cursorY, fieldW(), () -> {
            try {
                net.minecraft.Util.getPlatform().openUri(CustomSounds.soundDir().toUri().toString());
            } catch (RuntimeException ignored) {
                // 打不开就算了
            }
        });
        this.cursorY += ROW;
    }

    private void managePage() {
        addToggleRowRaw("按发言者自动切卡", CommsConfig.autoCardByPlayer);
        addToggleRowRaw("形象框常驻", CommsConfig.portraitAlwaysVisible);
        addToggleRowRaw("形象框跟随聊天", CommsConfig.portraitFollowChat);
        addToggleRowRaw("NCR 兼容", CommsConfig.ncrCompat);

        sectionLabel("新建角色卡");
        EditBox create = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16,
                Component.literal("新卡名"));
        create.setMaxLength(48);
        create.setValue("");
        if (this.buildingWidgets && visible(this.cursorY)) {
            this.newCardBox = create;
        }
        addWidget(create);
        this.cursorY += ROW;
        addButton("用当前卡复制新建", fieldX(), this.cursorY, fieldW(), () -> {
            String input = this.newCardBox == null ? "" : this.newCardBox.getValue().trim();
            if (input.isEmpty() || PlayerCards.exists(input)) {
                return;
            }
            PlayerCards.copyCard(this.selected, input);
            CommsConfig.selectCard(input);
            this.selected = input;
            this.init();
        });
        this.cursorY += ROW;

        sectionLabel("当前卡");
        EditBox rename = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16, Component.literal("卡名"));
        rename.setMaxLength(48);
        rename.setValue(this.selected);
        if (this.buildingWidgets && visible(this.cursorY)) {
            this.cardNameBox = rename;
        }
        addWidget(rename);
        this.cursorY += ROW;

        int half = Math.max(80, (fieldW() - 6) / 2);
        addButton("重命名卡", fieldX(), this.cursorY, half, () -> {
            String input = this.cardNameBox == null ? "" : this.cardNameBox.getValue().trim();
            if (!input.isEmpty() && !input.equals(this.selected)
                    && PlayerCards.rename(this.selected, input)) {
                CommsConfig.selectCard(input);
                this.selected = input;
                this.init();
            }
        });
        addButton("删除此卡", fieldX() + half + 6, this.cursorY, half, () -> {
            if (!PlayerCards.DEFAULT_CARD.equals(this.selected) && PlayerCards.delete(this.selected)) {
                CommsConfig.selectCard(PlayerCards.DEFAULT_CARD);
                this.selected = PlayerCards.DEFAULT_CARD;
                this.init();
            }
        });
        this.cursorY += ROW;

        addButton("导出此卡", fieldX(), this.cursorY, half, () -> PlayerCards.exportTo(this.selected, null));
        addButton("导出配置", fieldX() + half + 6, this.cursorY, half, () -> CommsConfig.export());
        this.cursorY += ROW;

        sectionLabel("导入 / 目录");
        addButton("导入角色卡…", fieldX(), this.cursorY, fieldW(), this::importCardDialog);
        this.cursorY += ROW;
        addButton("打开角色卡目录", fieldX(), this.cursorY, fieldW(),
                () -> openPath(PlayerCards.dir()));
        this.cursorY += ROW;

        List<String> onlinePlayers = PlayerCards.onlinePlayerNames();
        if (!onlinePlayers.isEmpty()) {
            sectionLabel("在线玩家（点一下从默认卡建卡）");
            int shown = Math.min(onlinePlayers.size(), 12);
            for (int i = 0; i < shown; i++) {
                String onlineName = onlinePlayers.get(i);
                boolean hasCard = PlayerCards.exists(onlineName);
                addButton((hasCard ? "切换到 " : "＋ 建卡 ") + onlineName, fieldX(), this.cursorY, fieldW(), () -> {
                    if (!hasCard) {
                        PlayerCards.createFromDefault(onlineName);
                    }
                    CommsConfig.selectCard(onlineName);
                    this.selected = onlineName;
                    this.actionStatus = (hasCard ? "已切换到 " : "已建卡并切换到 ") + onlineName;
                    this.init();
                });
                this.cursorY += ROW;
            }
        }
        addStatusRow();

        addButton("打开配置目录", fieldX(), this.cursorY, fieldW(), () -> {
            try {
                net.minecraft.Util.getPlatform().openUri(AceChatFolders.rootDir().toUri().toString());
            } catch (RuntimeException ignored) {
                // 打不开就算了
            }
        });
        this.cursorY += ROW;

        // 旧版配置入口：保留下来方便对照/使用尚未迁移的功能
        addButton("旧版配置（对照用）", fieldX(), this.cursorY, fieldW(), () ->
                Minecraft.getInstance().setScreen(new CommsConfigScreen(this)));
        this.cursorY += ROW;
    }

    /** 从 export/cards/ 里挑一个 json 导入成角色卡。 */
    private void importCardDialog() {
        List<String> files = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(PlayerCards.exportDir())) {
            stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .forEach(path -> files.add(path.getFileName().toString()));
        } catch (Exception ignored) {
            // 没有导出目录
        }
        if (files.isEmpty()) {
            this.actionStatus = "导出目录里没有角色卡文件";
            this.layoutPage();
            return;
        }
        Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "导入角色卡（来自 export/cards）", files, picked -> {
            String cardName = picked.toLowerCase().endsWith(".json")
                    ? picked.substring(0, picked.length() - 5) : picked;
            boolean ok = PlayerCards.importFile(PlayerCards.exportDir().resolve(picked));
            if (ok) {
                this.reloadLists();
                this.selected = cardName;
                CommsConfig.selectCard(cardName);
                this.actionStatus = "已导入角色卡 " + cardName;
            } else {
                this.actionStatus = "导入失败（同名卡已存在或文件无效）：" + cardName;
            }
            this.init();
        }));
    }

    /** 剧本页：工程列表 + 新建/打开/复制/重命名/删除/导出/导入/目录。 */
    private void stagePage() {
        List<String> projects = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(StageScripts.dir())) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> projects.add(path.getFileName().toString().replace(".json", "")));
        } catch (Exception ignored) {
            // 没有工程
        }

        sectionLabel("工程");
        EditBox nameBox = new EditBox(this.font, fieldX(), this.cursorY, fieldW(), 16, Component.literal("工程名"));
        nameBox.setMaxLength(64);
        nameBox.setValue(this.stageNameInput);
        nameBox.setResponder(value -> this.stageNameInput = value);
        addWidget(nameBox);
        this.stageNameBox = nameBox;
        this.cursorY += ROW;

        int bw = Math.max(56, (fieldW() - 18) / 4);
        String[] actions1 = {"新建", "打开", "复制", "重命名"};
        for (int i = 0; i < actions1.length; i++) {
            final String action = actions1[i];
            addButton(action, fieldX() + i * (bw + 6), this.cursorY, bw, () -> stageAction(action));
        }
        this.cursorY += ROW;

        String deleteLabel = this.stageDeleteTarget.equals(this.stageSelected)
                && !this.stageSelected.isEmpty()
                && System.currentTimeMillis() - this.stageDeleteAt < 5000 ? "确认删除" : "删除";
        String[] actions2 = {deleteLabel, "导出", "导入", "目录"};
        for (int i = 0; i < actions2.length; i++) {
            final String action = i == 0 ? "删除" : actions2[i];
            addButton(actions2[i], fieldX() + i * (bw + 6), this.cursorY, bw, () -> stageAction(action));
        }
        this.cursorY += ROW;

        if (!this.stageStatus.isBlank()) {
            sectionLabel("状态：" + this.stageStatus);
        }

        sectionLabel("工程列表");
        if (projects.isEmpty()) {
            sectionLabel("（没有工程，输入名字后点「新建」）");
            return;
        }
        int openW = 44;
        for (String project : projects) {
            final String name = project;
            String label = (name.equals(this.stageSelected) ? "▶ " : "剧本：") + name;
            addButton(label, fieldX(), this.cursorY, Math.max(80, fieldW() - openW - 6), () -> {
                this.stageSelected = name;
                this.stageNameInput = name;
                this.stageStatus = "";
                this.layoutPage();
            });
            addButton("打开", fieldX() + fieldW() - openW, this.cursorY, openW, () -> openStage(name));
            this.cursorY += ROW;
        }
        addButton("打开剧本目录", fieldX(), this.cursorY + 4, fieldW(), this::openStageFolder);
        this.cursorY += ROW;
    }

    private void stageAction(String action) {
        String name = this.stageNameInput == null ? "" : this.stageNameInput.trim();
        this.stageStatus = "";
        switch (action) {
            case "新建" -> {
                if (StageScripts.createDefault(name)) {
                    this.stageSelected = name;
                    openStage(name);
                    return;
                }
                this.stageStatus = "新建失败（名字为空或已存在）";
            }
            case "打开" -> {
                openStage(name.isEmpty() ? this.stageSelected : name);
                return;
            }
            case "复制" -> {
                if (this.stageSelected.isEmpty()) {
                    this.stageStatus = "先在列表里选一个工程";
                } else if (StageScripts.copy(this.stageSelected, name)) {
                    this.stageSelected = name;
                    this.stageStatus = "已复制为 " + name;
                } else {
                    this.stageStatus = "复制失败（名字为空/已存在）";
                }
            }
            case "重命名" -> {
                if (this.stageSelected.isEmpty()) {
                    this.stageStatus = "先在列表里选一个工程";
                } else if (StageScripts.rename(this.stageSelected, name)) {
                    this.stageStatus = "已重命名为 " + name;
                    this.stageSelected = name;
                } else {
                    this.stageStatus = "重命名失败（名字为空/已存在）";
                }
            }
            case "删除" -> {
                if (this.stageSelected.isEmpty()) {
                    this.stageStatus = "先在列表里选一个工程";
                } else if (this.stageDeleteTarget.equals(this.stageSelected)
                        && System.currentTimeMillis() - this.stageDeleteAt < 5000) {
                    if (StageScripts.delete(this.stageSelected)) {
                        this.stageStatus = "已删除 " + this.stageSelected;
                        this.stageSelected = "";
                        this.stageNameInput = "";
                        this.stageDeleteTarget = "";
                    } else {
                        this.stageStatus = "删除失败";
                    }
                } else {
                    this.stageDeleteTarget = this.stageSelected;
                    this.stageDeleteAt = System.currentTimeMillis();
                    this.stageStatus = "再点一次「确认删除」：" + this.stageSelected;
                }
            }
            case "导出" -> {
                if (this.stageSelected.isEmpty()) {
                    this.stageStatus = "先选一个工程";
                } else {
                    java.nio.file.Path path = StageScripts.export(this.stageSelected, AceChatFolders.exportDir());
                    this.stageStatus = path == null ? "导出失败" : "已导出到 " + path.getFileName();
                }
            }
            case "导入" -> {
                importStageDialog();
                return;
            }
            default -> openStageFolder();
        }
        this.layoutPage();
    }

    private void openStage(String name) {
        String project = name == null ? "" : name.trim();
        if (project.isEmpty()) {
            this.stageStatus = "先写工程名或选一个工程";
            this.layoutPage();
            return;
        }
        if (!java.nio.file.Files.isRegularFile(StageScripts.dir().resolve(project + ".json"))
                && !StageScripts.createDefault(project)) {
            this.stageStatus = "打开失败：工程不存在且无法新建";
            this.layoutPage();
            return;
        }
        this.stageSelected = project;
        Minecraft.getInstance().setScreen(new StageCanvasScreen(this, project));
    }

    private void openStageFolder() {
        try {
            net.minecraft.Util.getPlatform().openUri(StageScripts.dir().toUri().toString());
        } catch (RuntimeException ignored) {
            // 打不开就算了
        }
    }

    /** 从 export/ 里挑一个 json 导入成新工程。 */
    private void importStageDialog() {
        List<String> files = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(AceChatFolders.exportDir())) {
            stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .forEach(path -> files.add(path.getFileName().toString()));
        } catch (Exception ignored) {
            // 没有导出目录
        }
        if (files.isEmpty()) {
            this.stageStatus = "导出目录里没有工程文件";
            this.layoutPage();
            return;
        }
        Minecraft.getInstance().setScreen(new ChoiceListScreen(this, "导入工程（来自 export/）", files, picked -> {
            String base = picked.toLowerCase().endsWith(".json")
                    ? picked.substring(0, picked.length() - 5) : picked;
            if (StageScripts.importFrom(base, AceChatFolders.exportDir())) {
                this.stageSelected = base;
                this.stageNameInput = base;
                this.stageStatus = "已导入 " + base;
            } else {
                this.stageStatus = "导入失败：" + base;
            }
            this.init();
        }));
    }

    // ───────────────────────── 音效预设 ─────────────────────────

    private static String presetLabel(String id, boolean appear) {
        String[] all = appear ? CommsSoundSynth.APPEAR_PRESETS : CommsSoundSynth.GONE_PRESETS;
        for (String preset : all) {
            if (preset.equals(id)) {
                return Component.translatable("config.acechat.sound.preset." + preset).getString();
            }
        }
        return id == null || id.isBlank() ? "（默认）" : id;
    }

    private void pickPreset(boolean appear) {
        String[] all = appear ? CommsSoundSynth.APPEAR_PRESETS : CommsSoundSynth.GONE_PRESETS;
        List<String> labels = new ArrayList<>();
        for (String preset : all) {
            labels.add(Component.translatable("config.acechat.sound.preset." + preset).getString());
        }
        Minecraft.getInstance().setScreen(new ChoiceListScreen(this, appear ? "出现音效" : "消失音效",
                labels, picked -> {
                    int index = labels.indexOf(picked);
                    if (index >= 0) {
                        if (appear) {
                            CommsConfig.newSoundPreset.set(all[index]);
                        } else {
                            CommsConfig.goneSoundPreset.set(all[index]);
                        }
                        CommsConfig.save();
                    }
                }));
    }

    // ───────────────────────── 行工具 ─────────────────────────

    private boolean visible(int y) {
        return y >= this.contentTop && y + 18 <= this.contentBottom;
    }

    private void addWidget(AbstractWidget widget) {
        if (this.buildingWidgets && visible(widget.getY())) {
            this.addRenderableWidget(widget);
        }
    }

    private int fieldX() {
        return this.panelX + 8;
    }

    private int fieldW() {
        return this.panelW - 34;
    }

    private void addToggleRow(String label, boolean value, Runnable action) {
        addButtonRaw(label + "：" + (value ? "开" : "关"), fieldX(), this.cursorY, fieldW(), () -> {
            action.run();
            this.layoutPage();
        });
        this.cursorY += ROW;
    }

    private void addToggleRowRaw(String label, CommsConfig.Value<Boolean> value) {
        addButtonRaw(label + "：" + (value.get() ? "开" : "关"), fieldX(), this.cursorY, fieldW(), () -> {
            value.set(!value.get());
            if (value == CommsConfig.gradeEnabled || value == CommsConfig.duotoneEnabled) {
                PortraitGrade.invalidate();
            }
            CommsConfig.save();
            this.layoutPage();
        });
        this.cursorY += ROW;
    }

    private void addCycleRow(String label, String value, Runnable action) {
        addButtonRaw(label + "：" + value, fieldX(), this.cursorY, fieldW(), action);
        this.cursorY += ROW;
    }

    private void addButton(String label, int x, int y, int w, Runnable action) {
        addButtonRaw(label, x, y, w, action);
    }

    /** 只读提示行（灰色按钮样式，点了不做事）。 */
    private void addInfoRow(String text) {
        Button info = Button.builder(Component.literal(text), b -> { })
                .bounds(fieldX(), this.cursorY, fieldW(), 16).build();
        info.active = false;
        addWidget(info);
        this.cursorY += ROW;
    }

    private void addStatusRow() {
        if (this.actionStatus == null || this.actionStatus.isBlank()) {
            return;
        }
        addInfoRow(trim(this.actionStatus, 70));
    }

    private static void openPath(java.nio.file.Path path) {
        if (path == null) {
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openUri(path.toUri().toString());
        } catch (RuntimeException ignored) {
            // 打不开就算了
        }
    }

    private void addButtonRaw(String label, int x, int y, int w, Runnable action) {
        Button button = Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, w, 16).build();
        addWidget(button);
    }

    private void addSliderRow(String label, CommsConfig.Value<Integer> value, int min, int max, String suffix) {
        CommsSlider slider = new CommsSlider(label, label, fieldX(), this.cursorY, fieldW(), 16,
                () -> value.get(), v -> value.set((int) Math.round(v)), min, max, true, suffix, false);
        addWidget(slider);
        this.cursorY += ROW;
    }

    private void addSliderRowDouble(String label, CommsConfig.Value<Double> value, double min, double max) {
        CommsSlider slider = new CommsSlider(label, label, fieldX(), this.cursorY, fieldW(), 16,
                value::get, value::set, min, max, false, "", false);
        addWidget(slider);
        this.cursorY += ROW;
    }

    private void addElemSlider(String label, CommsConfig.TextElement element, int min, int max,
                               java.util.function.ToIntFunction<CommsConfig.TextElement> getter,
                               java.util.function.ObjIntConsumer<CommsConfig.TextElement> setter) {
        CommsSlider slider = new CommsSlider(label, label, fieldX(), this.cursorY, fieldW(), 16,
                () -> getter.applyAsInt(element),
                v -> {
                    setter.accept(element, (int) Math.round(v));
                    CommsConfig.save();
                }, min, max, true, "", false);
        addWidget(slider);
        this.cursorY += ROW;
    }

    private void addColorRow(String label, CommsConfig.Value<Integer> value) {
        openColorButton(label + " …", value.get(), argb -> value.set(argb));
        this.cursorY += ROW;
    }

    // ───────────────────────── 内嵌取色面板 ─────────────────────────

    private void openColorButton(String label, int initial, java.util.function.IntConsumer apply) {
        addButtonRaw(label, fieldX(), this.cursorY, fieldW(), () -> openColor(initial, apply));
    }

    private void openColor(int argb, java.util.function.IntConsumer apply) {
        this.color[0] = (argb >> 16) & 0xFF;
        this.color[1] = (argb >> 8) & 0xFF;
        this.color[2] = argb & 0xFF;
        this.color[3] = (argb >>> 24) & 0xFF;
        if (this.color[3] == 0) {
            this.color[3] = 255;   // 全透明时先给个可见的默认值
        }
        this.colorApply = apply;
        this.colorX = Math.max(6, this.panelX - COLOR_W - 12);
        this.colorY = Math.max(TOP + 28, Math.min(this.height - COLOR_H - 10, 96));
        this.colorOpen = true;
        this.colorDrag = -1;
        applyColor();
    }

    private void closeColor() {
        commitColor();
        this.colorOpen = false;
        this.colorDrag = -1;
        this.colorApply = null;
    }

    /** 取色改完统一落盘：拖动中不写盘，避免每像素一次磁盘 IO。 */
    private void commitColor() {
        if (this.colorDirty) {
            this.colorDirty = false;
            CommsConfig.save();
        }
    }

    private int colorValue() {
        return ((this.color[3] & 0xFF) << 24) | ((this.color[0] & 0xFF) << 16)
                | ((this.color[1] & 0xFF) << 8) | (this.color[2] & 0xFF);
    }

    private void applyColor() {
        if (this.colorApply != null) {
            this.colorApply.accept(colorValue());
            this.colorDirty = true;
        }
    }

    private int colorBarX() {
        return this.colorX + 34;
    }

    private int colorBarW() {
        return COLOR_W - 46;
    }

    private int colorRowY(int row) {
        return this.colorY + 34 + row * 20;
    }

    private void colorFromMouse(double mouseX, int row) {
        double ratio = (mouseX - colorBarX()) / (double) Math.max(1, colorBarW());
        ratio = Math.max(0, Math.min(1, ratio));
        this.color[row] = (int) Math.round(ratio * 255);
        applyColor();
    }

    private void drawColorPopup(GuiGraphics graphics) {
        if (!this.colorOpen) {
            return;
        }
        int x = this.colorX;
        int y = this.colorY;
        graphics.fill(x - 2, y - 2, x + COLOR_W + 2, y + COLOR_H + 2, 0xFF101016);
        graphics.fill(x, y, x + COLOR_W, y + COLOR_H, 0xFFE9E2CE);
        graphics.renderOutline(x, y, COLOR_W, COLOR_H, 0xFF3A3A44);
        graphics.drawString(this.font, "调色", x + 6, y + 6, 0xFF2A2A30, false);
        graphics.drawString(this.font, String.format("#%02X%02X%02X%02X",
                this.color[3], this.color[0], this.color[1], this.color[2]), x + 60, y + 6, 0xFF555560, false);
        graphics.fill(x + COLOR_W - 40, y + 5, x + COLOR_W - 8, y + 17, colorValue() | 0xFF000000);

        String[] names = {"R", "G", "B", "A"};
        for (int i = 0; i < 4; i++) {
            int ry = colorRowY(i);
            graphics.drawString(this.font, names[i], x + 8, ry + 4, 0xFF2A2A30, false);
            int bx = colorBarX();
            int bw = colorBarW();
            graphics.fill(bx, ry, bx + bw, ry + 10, 0xFFB9B29F);
            int fill = (int) Math.round(bw * (this.color[i] / 255.0));
            graphics.fill(bx, ry, bx + fill, ry + 10,
                    i == 0 ? 0xFFC04040 : i == 1 ? 0xFF40A040 : i == 2 ? 0xFF4060C0 : 0xFF808080);
            graphics.fill(bx + fill - 1, ry - 2, bx + fill + 1, ry + 12, 0xFF2A2A30);
            graphics.drawString(this.font, String.valueOf(this.color[i]), bx + bw + 6, ry + 2, 0xFF2A2A30, false);
        }
        graphics.drawString(this.font, "点其它地方收起", x + 6, y + COLOR_H - 12, 0xFF77776A, false);
    }

    // ───────────────────────── 文字元素 ─────────────────────────

    private List<CommsConfig.TextElement> elements() {
        List<CommsConfig.TextElement> list = new ArrayList<>();
        list.add(CommsConfig.builtIn(CommsConfig.KIND_NAME));
        list.add(CommsConfig.builtIn(CommsConfig.KIND_VITALS));
        list.add(CommsConfig.builtIn(CommsConfig.KIND_FOOD));
        list.add(CommsConfig.builtIn(CommsConfig.KIND_ARMOR));
        list.addAll(CommsConfig.customElements());
        return list;
    }

    private static String builtInName(int index) {
        return switch (index) {
            case 0 -> "名字";
            case 1 -> "生命";
            case 2 -> "饥饿";
            default -> "护甲";
        };
    }

    private static boolean isVisible(CommsConfig.TextElement element) {
        return element.visible == null || element.visible;
    }

    private static String alignH(CommsConfig.TextElement element) {
        return switch (element.horizontal) {
            case LEFT -> "左";
            case CENTER -> "中";
            default -> "右";
        };
    }

    private static String alignV(CommsConfig.TextElement element) {
        return switch (element.vertical) {
            case TOP -> "上";
            case CENTER -> "中";
            default -> "下";
        };
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private String blendText() {
        return switch (CommsConfig.filterBlend.get()) {
            case 0 -> "正常";
            case 2 -> "滤色";
            case 3 -> "加法";
            default -> "正片叠底";
        };
    }

    private static String shortFilter(String id) {
        if (id == null || id.isBlank()) {
            return "（无）";
        }
        if (id.startsWith("builtin:")) {
            return switch (id) {
                case "builtin:scanlines" -> "干扰线·竖";
                case "builtin:scanlines_h" -> "干扰线·横";
                case "builtin:grain" -> "噪点";
                case "builtin:vignette" -> "暗角";
                case "builtin:duotone" -> "双色调";
                case "builtin:dots" -> "点阵";
                default -> id.substring(8);
            };
        }
        return id;
    }

    // ───────────────────────── 写回 ─────────────────────────

    private void writeCharacter() {
        if (this.selected == null || this.selected.isBlank()) {
            return;
        }
        CardCharacter.Data data = new CardCharacter.Data();
        data.displayName = this.displayName == null ? "" : this.displayName;
        data.boundPlayer = this.bound;
        data.skin = this.skinRef == null ? "" : this.skinRef;
        data.portrait = this.portraitRef == null ? "" : this.portraitRef;
        CardCharacter.write(this.selected, data);
        CardCharacter.clearCache();
        CardTextures.clearAll();
        PortraitSource.clear();
        PortraitGrade.invalidate();
        CommsConfig.refreshPortraitNow();
    }

    // ───────────────────────── 输入 ─────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= this.panelX && this.maxScroll > 0) {
            this.pageScroll[this.tab] -= (int) Math.signum(scrollY) * 2 * ROW;
            layoutPage();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.colorOpen) {
            boolean inside = mouseX >= this.colorX - 2 && mouseX <= this.colorX + COLOR_W + 2
                    && mouseY >= this.colorY - 2 && mouseY <= this.colorY + COLOR_H + 2;
            if (inside) {
                for (int row = 0; row < 4; row++) {
                    int ry = colorRowY(row);
                    if (mouseY >= ry - 4 && mouseY <= ry + 14) {
                        this.colorDrag = row;
                        colorFromMouse(mouseX, row);
                        return true;
                    }
                }
                return true;   // 点在面板里（非滑块）也不穿透
            }
            closeColor();      // 点其它地方：收起
            return true;
        }
        if (button == 0 && isOnBar(mouseX, mouseY)) {
            this.draggingBar = true;
            this.pageScroll[this.tab] = scrollFromMouse(mouseY);
            layoutPage();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.colorOpen && this.colorDrag >= 0 && button == 0) {
            colorFromMouse(mouseX, this.colorDrag);
            return true;
        }
        if (this.draggingBar && button == 0) {
            this.pageScroll[this.tab] = scrollFromMouse(mouseY);
            layoutPage();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.draggingBar = false;
            if (this.colorDrag >= 0) {
                commitColor();      // 一次拖动结束只写一次
            }
            this.colorDrag = -1;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private int barX() {
        return this.width - 12;
    }

    private boolean isOnBar(double mouseX, double mouseY) {
        return this.maxScroll > 0 && mouseX >= barX() - 2 && mouseX <= barX() + 8
                && mouseY >= this.contentTop && mouseY <= this.contentBottom;
    }

    private int scrollFromMouse(double mouseY) {
        int view = Math.max(1, this.contentBottom - this.contentTop);
        double ratio = (mouseY - this.contentTop) / (double) view;
        return Math.max(0, Math.min(this.maxScroll, (int) Math.round(ratio * this.maxScroll)));
    }

    // ───────────────────────── 绘制 ─────────────────────────

    /** 关掉原版背景：模糊/压暗会把我们自己画的预览和标签盖掉。 */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景由 render() 自己画
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        flushAutoSave();
        graphics.fill(0, 0, this.width, this.height, 0xF0101016);
        graphics.fill(0, 0, this.width, TOP - 4, 0xFF26262C);
        graphics.fill(0, TOP - 4, this.leftW, this.height - BOTTOM, 0xFF1D1D24);
        graphics.fill(this.panelX - 2, TOP - 4, this.width, this.height - BOTTOM, 0xFF202027);

        // 左上角：实际样式预览
        graphics.drawString(this.font, "实际样式预览", this.previewX, this.previewY - 12, 0xFFFFD27A, false);
        graphics.fill(this.previewX - 2, this.previewY - 2,
                this.previewX + this.previewW + 2, this.previewY + this.previewH + 2, 0xFF2C2C34);
        // 按 960×540 的虚拟屏幕等比缩进预览区：聊天框宽度、位置偏移才和实际游玩一致
        // 放大展示：形象框居中、聊天框在其下方（只看样式，和游戏内位置无关）
        try {
            CommsHud.renderShowcase(graphics, this.previewX, this.previewY, this.previewW, this.previewH);
        } catch (RuntimeException | LinkageError ignored) {
            // 预览失败不影响编辑
        }

        // 小比例屏幕示意
        drawMiniScreen(graphics);

        // 当前页内容标签
        for (int[] line : this.labelLines) {
            int y = line[0];
            if (y >= this.contentTop - 4 && y <= this.contentBottom) {
                graphics.drawString(this.font, this.labelTexts.get(line[2]), this.panelX + 8, y + 4,
                        line[1], false);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // 顶层：页签高亮、滚动条、标题、底栏（画在控件之后，保证不被背景盖住）
        drawTabAccent(graphics);
        drawScrollbar(graphics);
        drawColorPopup(graphics);
        graphics.drawCenteredString(this.font, "角色卡工坊 · SACS fork", this.width / 2, 8, 0xFFEDEDED);
        graphics.drawString(this.font, "滚轮滚动本页 · 点页签切换 · Esc 返回", 8, this.height - 12,
                0xFF9A9A9A, false);
    }

    private void drawTabAccent(GuiGraphics graphics) {
        int gridW = Math.min(this.panelW - 12, this.width - this.panelX - 22);
        int bw = Math.max(48, gridW / 3);
        for (int i = 0; i < TABS.length; i++) {
            if (i != this.tab) {
                continue;
            }
            int col = i % 3;
            int row = i / 3;
            int x = this.panelX + 4 + col * bw;
            int y = TOP + 2 + row * 17;
            graphics.fill(x, y + 14, Math.min(x + bw - 2, this.width - 14), y + 16, 0xFFFFD27A);
            return;
        }
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int x = barX();
        graphics.fill(x, this.contentTop, x + 4, this.contentBottom, 0xFF3A3A44);
        if (this.maxScroll <= 0) {
            return;
        }
        int view = Math.max(1, this.contentBottom - this.contentTop);
        int thumbH = Math.max(24, (int) ((long) view * view / (view + this.maxScroll)));
        int thumbY = this.contentTop
                + (int) ((long) (view - thumbH) * this.pageScroll[this.tab] / this.maxScroll);
        graphics.fill(x, thumbY, x + 4, thumbY + thumbH, 0xFFE8D9A8);
    }

    /** 16:9 屏幕示意：按「游戏 GUI 分辨率」等比缩放，形象框/聊天框按其真实尺寸换算。 */
    private void drawMiniScreen(GuiGraphics graphics) {
        graphics.drawString(this.font, "屏幕示意（相对位置）", this.miniX, this.miniY - 12, 0xFFFFD27A, false);
        graphics.fill(this.miniX, this.miniY, this.miniX + this.miniW, this.miniY + this.miniH, 0xFF101018);
        graphics.renderOutline(this.miniX, this.miniY, this.miniW, this.miniH, 0xFF6A6A78);

        // 直接复用游戏内真实布局：chatRect / portraitRect 一处算法两处用，
        // 示意里的 X 偏移、右间距、基线才和实际完全一致（2026-11-17 根因 7.2#8）。
        var window = Minecraft.getInstance().getWindow();
        int guiW = Math.max(1, window.getGuiScaledWidth());
        int guiH = Math.max(1, window.getGuiScaledHeight());
        float scale = Math.min(this.miniW / (float) guiW, this.miniH / (float) guiH);
        float usedW = guiW * scale;
        float usedH = guiH * scale;
        int ox = this.miniX + Math.round((this.miniW - usedW) * 0.5F);
        int oy = this.miniY + Math.round((this.miniH - usedH) * 0.5F);

        int[] portrait = CommsHud.portraitRect(guiW, guiH);
        int[] chat = CommsHud.chatRect(guiW, guiH, Math.max(1, CommsHud.previewLineCount(this.font, guiW)));
        drawMiniBox(graphics, portrait, ox, oy, scale, "形象", 0xFF63E66B);
        drawMiniBox(graphics, chat, ox, oy, scale, "聊天", 0xFF63E66B);

        graphics.drawString(this.font, "GUI " + guiW + "×" + guiH, this.miniX + 3,
                this.miniY + this.miniH - 10, 0xFF707078, false);
    }

    /** 把游戏内真实矩形按示意比例画出来（与 chatRect/portraitRect 同源）。 */
    private void drawMiniBox(GuiGraphics graphics, int[] rect, int ox, int oy,
                             float scale, String label, int outline) {
        int x = ox + Math.round(rect[0] * scale);
        int y = oy + Math.round(rect[1] * scale);
        int w = Math.max(2, Math.round(rect[2] * scale));
        int h = Math.max(2, Math.round(rect[3] * scale));
        graphics.fill(x, y, x + w, y + h, 0xFF23343F);
        graphics.renderOutline(x, y, w, h, outline);
        graphics.drawString(this.font, label, x + 2, y + 2, 0xFFBDE7C0, false);
    }

    @Override
    public void onClose() {
        flushAutoSaveNow();
        CommsHud.setPreviewForced(false);
        CommsConfig.setEditingScreen(false);
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void removed() {
        flushAutoSaveNow();
        CommsHud.setPreviewForced(false);
        CommsConfig.setEditingScreen(false);
        super.removed();
    }
}