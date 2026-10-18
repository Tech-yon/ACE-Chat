package cn.acechat.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACE Chat 配置界面（r9 重写）。
 *  - 左侧一列是分区（二级菜单），右侧是行式布局：行高 20、行距 28（间隔 8 像素），
 *    一页放不下自动分页，任何两个控件都不会重叠，也不会再压到底部按钮上；
 *  - 所有数值项都是可拖动滑块（CommsSlider），不再用数值输入框；
 *  - 颜色项点开取色二级界面（A/R/G/B 滑块 + 十六进制 + 实时色块）；
 *  - “预览与拖动”分区里可以直接拖半身像/聊天框改位置，拖右下角小方块改大小；
 *  - 文字元素、玩家名字颜色都有可点选的列表，选中谁就编辑谁。
 */
public final class CommsConfigScreen extends Screen {
   private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
   private static boolean loggedFailure;
   private static final int ROW_H = 20;
   private static final int ROW_STEP = 28;
   private static final int SIDEBAR_W = 112;
   private static final int MARGIN = 8;
   private static final int LABEL_W = 104;
   private static final int TOP = 30;

   private static final String[] TABS = {
      "screen.acechat.cards",
      "screen.acechat.chat",
      "screen.acechat.portrait",
      "screen.acechat.preview",
      "screen.acechat.names",
      "screen.acechat.elements",
      "screen.acechat.filters",
      "screen.acechat.import_export",
      "screen.acechat.reset"
   };

   /** 侧边栏只显示这些顶层标签；其余（半身像/预览/名字/元素/滤镜）收进「角色卡」三级菜单。 */
   private static final int[] TOP_TABS = {0, 1, 7, 8};

   private static final String[] TAB_TOOLTIPS = {
      "config.acechat.tab.cards.tooltip",
      "config.acechat.tab.chat.tooltip",
      "config.acechat.tab.portrait.tooltip",
      "config.acechat.tab.preview.tooltip",
      "config.acechat.tab.names.tooltip",
      "config.acechat.tab.elements.tooltip",
      "config.acechat.tab.filters.tooltip",
      "config.acechat.tab.data.tooltip",
      "config.acechat.tab.reset.tooltip"
   };

   /** 新玩家加入名单时自动分配的配色，避免每个人都得手动选色。 */
   private static final int[] PALETTE = {
      0xFF4C9AFF, 0xFF4CE0C0, 0xFF55FF55, 0xFFFFFF55,
      0xFFFFAA00, 0xFFFF5555, 0xFFAA55FF, 0xFFFF55FF
   };

   /** 文字内容选项卡的三个预设（null = 自定义文字，输入框里自己写）。 */
   private static final String[] PRESETS = {
      "%player_name%",
      "生命 %health%/%max_health%  饥饿 %food%/%max_food%",
      null
   };

   private static boolean isPresetContent(String content) {
      return content != null && (content.equals(PRESETS[0]) || content.equals(PRESETS[1]));
   }

   private final Screen parent;
   private final List<EditBox> fields = new ArrayList<>();
   private final List<Consumer<String>> readers = new ArrayList<>();
   private final List<Label> labels = new ArrayList<>();
   private final List<Row> rows = new ArrayList<>();
   private final List<Runnable> live = new ArrayList<>();

   private int tab;
   private int page;
   private int pageCount = 1;
   /** 角色卡标签的层级：0 = 卡列表，1 = 五项功能。 */
   private int cardLevel = 0;
   /** 分组分隔线（x0, y, x1）：渲染时画成淡色细线。 */
   private final java.util.List<int[]> groupLines = new java.util.ArrayList<>();
   /** 新建角色卡时输入的名字。 */
   private String newCardName = "";
   /** 卡页面的导入文件选择框（只列 ACE_config/export/cards/ 里的 json）。 */
   private FilePickList cardImportList;
   /** 整体配置页的导入文件选择框（只列 ACE_config/export/ 里的 json）。 */
   private FilePickList configImportList;
   /** 翻页滑块实例（拖动时保留，不随重建销毁）。 */
   private CommsPageSlider pageSlider;
   private int selectedName;
   private int selectedElement;
   private int selectedCustom;
   private String templateName = "";
   private String status = "";
   private int statusTicks;

   private int contentX;
   private int contentW;
   private int contentTop;
   private int contentBottom;
   private int previewX;
   private int previewY;
   private int previewW;
   private int previewH;
   private int[] previewRects;

   private int dragMode;
   private int dragElementIndex = -1;
   private boolean dragElementScale;
   private double dragGrabX;
   private double dragGrabY;
   private double dragStartX;
   private double dragStartY;
   private int dragBaseInt1;
   private int dragBaseInt2;

   public CommsConfigScreen(Screen parent) {
      super(Component.translatable("screen.acechat.config"));
      this.parent = parent;
   }

   private record Label(int x, int y, Component text, int color) {
   }

   private interface RowSink {
      void render(int x, int y, int width);
   }

   /** 本页（当前渲染出来的那些行）绑定的「恢复默认」动作。 */
   private final List<Runnable> pageResets = new ArrayList<>();

   private record Row(int slots, RowSink sink) {
   }

   @Override
   protected void init() {
      this.clearWidgets();
      this.fields.clear();
      this.readers.clear();
      this.labels.clear();
      this.groupLines.clear();
      this.rows.clear();
      this.live.clear();
      AceChatFolders.ensure();   // 打开界面时确保素材目录存在
      this.contentX = MARGIN + SIDEBAR_W + 10;
      this.contentW = Math.max(140, this.width - this.contentX - MARGIN);
      this.contentTop = TOP;
      this.contentBottom = this.height - 44; // 与底部按钮栏（高 20 + 边距）留出安全间距

      // 侧边栏按钮间距按可用高度收缩：分类变多 / 窗口变矮时，绝不会压到底部的「返回」按钮
      int navTop = this.height - 34;
      int tabStep = Math.max(ROW_H + 2, Math.min(ROW_H + 6, (navTop - TOP) / Math.max(1, TOP_TABS.length)));
      // 卡片内的细节页（2..6）也算「角色卡」这一项被选中，视觉上不会跳走
      boolean inCards = this.tab == 0 || (this.tab >= 2 && this.tab <= 6);
      for (int slot = 0; slot < TOP_TABS.length; slot++) {
         final int index = TOP_TABS[slot];
         boolean active = index == 0 ? inCards : this.tab == index;
         String text = (active ? "▶ " : "") + Component.translatable(TABS[index]).getString();
         this.addRenderableWidget(
            Button.builder(Component.literal(text), b -> this.openTab(index))
               .bounds(MARGIN, TOP + slot * tabStep, SIDEBAR_W, ROW_H)
               .tooltip(Tooltip.create(Component.translatable(
                  index < TAB_TOOLTIPS.length ? TAB_TOOLTIPS[index] : TABS[index])))
               .build()
         );
      }

      // 构建内容时兜底：万一某个分区出问题，界面仍能打开，只记一条日志
      try {
         this.pageResets.clear();
         this.buildingRows = true;
         try {
            this.buildRows();
            int slotsPerPage = this.tab == 3 ? 2 : Math.max(3, (this.contentBottom - this.contentTop) / ROW_STEP);
            this.layoutRows(slotsPerPage);
         } finally {
            this.buildingRows = false;
         }
      } catch (RuntimeException | LinkageError error) {
         if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.error("[acechat] 构建配置界面内容失败，界面仍会打开但可能缺少部分控件", error);
         }
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.back"), b -> this.onClose())
            .bounds(MARGIN, this.height - 28, 80, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.navigation.back.tooltip")))
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.save"), b -> this.saveAndClose())
            .bounds(MARGIN + 86, this.height - 28, 110, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.navigation.save.tooltip")))
            .build()
      );
      if (this.pageCount > 1) {
         // 翻页滑块：拖动跟手，松手切页（比一页页翻快得多）
         int sliderWidth = Math.min(320, Math.max(120, this.width - MARGIN * 2 - 200));
         this.pageSlider = new CommsPageSlider(
            this.width - MARGIN - sliderWidth, this.height - 28, sliderWidth, ROW_H,
            this.pageCount, this.page, page -> {
               // 拖动时实时切页：只重建「行控件」，滑块实例保留 → 拖拽不会被打断
               int target = Math.max(0, Math.min(this.pageCount - 1, page));
               if (target != this.page) {
                  this.saveFields();
                  this.page = target;
                  this.rebuildRowsOnly();
               }
            });
         this.addRenderableWidget(this.pageSlider);
      }
   }

   private void openTab(int index) {
      this.saveFields();
      this.tab = index;
      this.page = 0;
      this.dragMode = 0;
      this.init();
   }

   private void changePage(int delta) {
      this.saveFields();
      this.page = Math.floorMod(this.page + delta, Math.max(1, this.pageCount));
      this.init();
   }

   /** 只重建「行控件」：翻页拖动时用，底部按钮与滑块都不会被销毁。 */
   private void rebuildRowsOnly() {
      for (var widget : this.rowWidgets) {
         this.removeWidget(widget);
      }

      this.rowWidgets.clear();
      this.fields.clear();
      this.readers.clear();
      this.labels.clear();
      this.groupLines.clear();
      this.rows.clear();
      this.live.clear();
      this.buildingRows = true;
      try {
         this.buildRows();
         int slotsPerPage = this.tab == 3 ? 2 : Math.max(3, (this.contentBottom - this.contentTop) / ROW_STEP);
         this.layoutRows(slotsPerPage);
      } catch (RuntimeException | LinkageError error) {
         LOGGER.warn("[acechat] 重建分页内容失败（界面仍可用）", error);
      } finally {
         this.buildingRows = false;
      }
   }

   /** 行控件登记（只追踪行创建出来的控件）。 */
   private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> rowWidgets = new java.util.ArrayList<>();
   private boolean buildingRows;

   @Override
   public <T extends net.minecraft.client.gui.components.events.GuiEventListener
         & net.minecraft.client.gui.components.Renderable
         & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
      T added = super.addRenderableWidget(widget);
      if (this.buildingRows && added instanceof net.minecraft.client.gui.components.AbstractWidget abstractWidget) {
         this.rowWidgets.add(abstractWidget);
      }

      return added;
   }

   private void layoutRows(int slotsPerPage) {
      List<List<Row>> pages = new ArrayList<>();
      List<Row> current = new ArrayList<>();
      int used = 0;
      for (Row row : this.rows) {
         // slots == 0 表示「强制从这里换页」
         if (row.slots() == 0) {
            if (!current.isEmpty()) {
               pages.add(current);
               current = new ArrayList<>();
               used = 0;
            }

            continue;
         }

         if (used + row.slots() > slotsPerPage && !current.isEmpty()) {
            pages.add(current);
            current = new ArrayList<>();
            used = 0;
         }

         current.add(row);
         used += row.slots();
      }

      if (!current.isEmpty()) {
         pages.add(current);
      }

      this.pageCount = Math.max(1, pages.size());
      this.page = Math.max(0, Math.min(this.page, this.pageCount - 1));
      int y = this.contentTop;
      for (Row row : pages.get(this.page)) {
         row.sink().render(this.contentX, y, this.contentW);
         y += row.slots() * ROW_STEP;
      }

      if (this.tab == 3) {
         this.previewX = this.contentX;
         this.previewY = y + 4;
         this.previewW = this.contentW;
         this.previewH = Math.max(40, this.contentBottom - this.previewY);
      }
   }

   // ------------------------------------------------------------------ 各分区的行

   private void buildRows() {
      switch (this.tab) {
         case 0 -> this.cardRows();
         case 1 -> this.chatRows();
         case 2 -> this.portraitRows();
         case 3 -> this.previewRows();
         case 4 -> this.namesRows();
         case 5 -> this.elementsRows();
         case 6 -> this.filtersRows();
         case 7 -> this.dataRows();
         default -> this.resetRows();
      }
   }

   /** 角色卡：一级 = 卡列表（★ 默认卡 / 在线玩家 / 本地已有卡），二级 = 五项功能。 */
   private void cardRows() {
      if (this.cardLevel == 0) {
         this.rowLabel("screen.acechat.cards.select");
         this.rowText("screen.acechat.cards.new_name", this.newCardName, value -> this.newCardName = value);
         this.addRow(1, (x, y, w) -> this.addRenderableWidget(
            Button.builder(Component.translatable("screen.acechat.cards.edit", CommsConfig.currentCard()), b -> {
               this.cardLevel = 1;
               this.page = 0;
               this.init();
            }).bounds(x, y, w, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("screen.acechat.cards.edit.tooltip"))).build()
         ));
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(80, (w - 12) / 3);
            String[] quick = {"open_cards", "new_card", "delete_card"};
            for (int i = 0; i < quick.length; i++) {
               final int which = i;
               String key = "screen.acechat.cards." + quick[i];
               this.addRenderableWidget(Button.builder(Component.translatable(key), b -> {
                  if (which == 0) {
                     if (!openFolder(cn.acechat.client.PlayerCards.dir())) {
                        this.notify("screen.acechat.open_dir.failed");
                     }
                  } else if (which == 1) {
                     String target = this.newCardName == null ? "" : this.newCardName.trim();
                     if (target.isEmpty()) {
                        this.notify("screen.acechat.cards.need_name");
                     } else if (cn.acechat.client.PlayerCards.exists(target)) {
                        this.notify("screen.acechat.cards.name_taken");
                     } else {
                        cn.acechat.client.PlayerCards.copyCard(CommsConfig.currentCard(), target);
                        CommsConfig.selectCard(target);
                        this.newCardName = "";
                        this.notify("screen.acechat.cards.created");
                     }

                  } else if (!cn.acechat.client.PlayerCards.delete(CommsConfig.activeCard.get())) {
                     this.notify("screen.acechat.cards.cant_delete_default");
                  } else {
                     CommsConfig.selectCard(cn.acechat.client.PlayerCards.DEFAULT_CARD);
                     this.notify("screen.acechat.cards.deleted");
                  }

                  this.init();
               }).bounds(x + i * (bw + 6), y, bw, ROW_H)
                  .tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build());
            }
         });

         // 第二排：重命名 / 导出到文件 / 从文件导入（卡文件直接读写，不经剪贴板）
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(80, (w - 6) / 2);
            String[] extra = {"rename_card", "export_card"};
            for (int i = 0; i < extra.length; i++) {
               final int which = i;
               String key = "screen.acechat.cards." + extra[i];
               this.addRenderableWidget(Button.builder(Component.translatable(key), b -> {
                  String input = this.newCardName == null ? "" : this.newCardName.trim();
                  if (which == 0) {
                     if (input.isEmpty()) {
                        this.notify("screen.acechat.cards.need_name");
                     } else if (cn.acechat.client.PlayerCards.rename(CommsConfig.currentCard(), input)) {
                        CommsConfig.selectCard(input);
                        this.newCardName = "";
                        this.notify("screen.acechat.cards.renamed");
                     } else {
                        this.notify("screen.acechat.cards.rename_failed");
                     }
                  } else if (which == 1) {
                     Path path = cn.acechat.client.PlayerCards.exportTo(CommsConfig.currentCard(), null);
                     this.notify(path == null ? "screen.acechat.cards.export_failed"
                        : Component.translatable("screen.acechat.cards.exported", path.toString()).getString());
                  } else {
                     int[] result = cn.acechat.client.PlayerCards.importFrom(null);
                     this.notify(result[0] == 0 && result[1] == 0
                        ? "screen.acechat.cards.import_none"
                        : Component.translatable("screen.acechat.cards.imported", result[0], result[1]).getString());
                  }

                  this.init();
               }).bounds(x + i * (bw + 6), y, bw, ROW_H)
                  .tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build());
            }
         });

      // 第三排：导入角色卡 —— 从 export/cards/ 的文件列表里挑一个（滚动选择框）
      this.rowLabel("screen.acechat.cards.import_hint");
      this.addRow(4, (x, y, w) -> {
         FilePickList list = new FilePickList(x, y, w, 4 * ROW_STEP - 2);
         list.refresh(cn.acechat.client.PlayerCards.exportDir());
         this.cardImportList = list;
         this.addRenderableWidget(list);
      });
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(80, (w - 6) / 2);
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.cards.import_selected"), b -> {
            String name = this.cardImportList == null ? null : this.cardImportList.selectedName();
            if (name == null) {
               this.notify("screen.acechat.cards.import_pick_first");
            } else {
               boolean ok = cn.acechat.client.PlayerCards.importFile(
                  cn.acechat.client.PlayerCards.exportDir().resolve(name));
               this.notify(ok ? Component.translatable("screen.acechat.cards.imported_one", name).getString()
                  : "screen.acechat.cards.import_failed");
               this.init();
            }
         }).bounds(x, y, bw, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.cards.import_selected.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.cards.reload_list"), b -> {
            if (this.cardImportList != null) {
               this.cardImportList.refresh(cn.acechat.client.PlayerCards.exportDir());
            }

            this.notify("screen.acechat.cards.list_reloaded");
         }).bounds(x + bw + 6, y, Math.max(80, w - bw - 6), ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.cards.reload_list.tooltip"))).build());
      });

         // ★ 默认卡
         this.cardButtonRow("★ " + Component.translatable("screen.acechat.cards.default").getString(),
            cn.acechat.client.PlayerCards.DEFAULT_CARD, 0xFFFFD27A);

         // 当前在线玩家（点一下：没有卡就从默认卡复制一张）
         java.util.List<String> online = cn.acechat.client.PlayerCards.onlinePlayerNames();
         if (!online.isEmpty()) {
            this.rowLabel("screen.acechat.cards.online");
            for (int start = 0; start < online.size(); start += 3) {
               final int from = start;
               this.addRow(1, (x, y, w) -> {
                  int bw = Math.max(64, (w - 12) / 3);
                  for (int i = from; i < Math.min(from + 3, online.size()); i++) {
                     String name = online.get(i);
                     boolean active = name.equals(CommsConfig.currentCard());
                     this.addRenderableWidget(Button.builder(
                        Component.literal((active ? "▶ " : "") + name), b -> {
                           cn.acechat.client.PlayerCards.createFromDefault(name);
                           CommsConfig.selectCard(name);
                           this.notify("screen.acechat.cards.selected");
                           this.init();
                        }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
                  }
               });
            }
         }

         // 本地已有卡（含离线玩家）
         this.rowLabel("screen.acechat.cards.local");
         java.util.List<String> local = cn.acechat.client.PlayerCards.list();
         for (int start = 0; start < local.size(); start += 3) {
            final int from = start;
            this.addRow(1, (x, y, w) -> {
               int bw = Math.max(64, (w - 12) / 3);
               for (int i = from; i < Math.min(from + 3, local.size()); i++) {
                  String name = local.get(i);
                  boolean active = name.equals(CommsConfig.currentCard());
                  this.addRenderableWidget(Button.builder(
                     Component.literal((active ? "▶ " : "") + name), b -> {
                        CommsConfig.selectCard(name);
                        this.notify("screen.acechat.cards.selected");
                        this.init();
                     }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
               }
            });
         }

         this.rowToggle("config.acechat.cards.auto", CommsConfig.autoCardByPlayer);
         return;
      }

      // 二级：五项功能
      this.rowLabel("screen.acechat.cards.functions");
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.cards.back"), b -> {
            this.cardLevel = 0;
            this.init();
         }).bounds(x, y, w, ROW_H).build()
      ));
      this.cardFunctionRow("screen.acechat.portrait", 2);
      this.cardFunctionRow("screen.acechat.preview", 3);
      this.cardFunctionRow("screen.acechat.elements", 5);
      this.cardFunctionRow("screen.acechat.names", 4);
      this.cardFunctionRow("screen.acechat.filters.overlay", 6);
      this.cardFunctionRow("screen.acechat.filters.grade", 6);
   }

   /** 一行一个卡按钮（默认卡用）。 */
   private void cardButtonRow(String label, String cardName, int color) {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.literal(label), b -> {
            CommsConfig.selectCard(cardName);
            this.notify("screen.acechat.cards.selected");
            this.init();
         }).bounds(x, y, w, ROW_H).build()
      ));
   }

   /** 二级功能按钮：跳到对应的原有标签（那里就是三级细节）。 */
   private void cardFunctionRow(String key, int targetTab) {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable(key), b -> {
            this.tab = targetTab;
            this.page = 0;
            this.init();
         }).bounds(x, y, w, ROW_H).build()
      ));
   }

   /** 详情标签顶部的一行：返回角色卡（显示当前卡名）。 */
   private void cardBackRow() {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.cards.back_to", CommsConfig.currentCard()), b -> {
            this.tab = 0;
            this.cardLevel = 1;
            this.page = 0;
            this.init();
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.cards.back_to.tooltip"))).build()
      ));
   }

   private void chatRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.CHAT);
      this.rowToggle("config.acechat.chat.enabled", CommsConfig.hudEnabled);
      this.rowToggle("config.acechat.chat.vanilla_player", CommsConfig.vanillaPlayerChat);
      this.rowToggle("config.acechat.chat.vanilla_other", CommsConfig.vanillaOtherMessages);
      this.rowSliderInt("config.acechat.chat.visible_lines", 0, 30, true, "", false, CommsConfig.chatVisibleLines);
      this.rowSliderInt("config.acechat.chat.screen_lines", 0, 30, true, "", false, CommsConfig.chatScreenLines);
      this.rowToggle("config.acechat.debug.timing", CommsConfig.debugTiming);
      this.rowSliderInt("config.acechat.chat.width", 120, 900, true, "", false, CommsConfig.boxWidth);
      this.rowSliderInt("config.acechat.chat.max_lines", 1, 12, true, "", false, CommsConfig.maxLines);
      this.rowSliderInt("config.acechat.chat.x", -400, 400, true, "", false, CommsConfig.centerXOffset);
      this.rowSliderInt("config.acechat.chat.y", 0, 400, true, "", false, CommsConfig.topYOffset);
      this.rowSliderPercent("config.acechat.chat.alpha", 0, 100, true, "", true, CommsConfig.backgroundAlpha);
      this.rowColor("config.acechat.chat.background", CommsConfig.chatBackgroundColor);
      this.rowColor("config.acechat.chat.name_color", CommsConfig.chatNameColor);
      this.rowSliderInt("config.acechat.chat.right_gap", 0, 240, true, " px", false, CommsConfig.chatRightGap);
      this.rowSliderInt("config.acechat.chat.left_margin", 0, 240, true, " px", false, CommsConfig.chatLeftMargin);
      this.rowSliderInt("config.acechat.chat.name_max_width", 20, 400, true, " px", false, CommsConfig.chatNameMaxWidth);
      this.rowSliderInt("config.acechat.chat.symbol_padding", 0, 16, true, " px", false, CommsConfig.chatSymbolPadding);
      this.rowColor("config.acechat.chat.symbol_color", CommsConfig.chatSymbolColor);
      this.rowSliderInt("config.acechat.chat.padding_x", 0, 8, true, "", false, CommsConfig.chatPaddingX);
      this.rowSliderInt("config.acechat.chat.padding_y", 0, 8, true, "", false, CommsConfig.chatPaddingY);
      this.rowToggle("config.acechat.chat.border", CommsConfig.chatBorderEnabled);
      this.rowColor("config.acechat.chat.border_color", CommsConfig.chatBorderColor);
      this.rowSliderInt("config.acechat.chat.border_thickness", 0, 2, true, "", false, CommsConfig.chatBorderThickness);
      this.rowSliderDouble("config.acechat.chat.hold", 1, 60, true, " 秒", false, CommsConfig.lineHoldSeconds);
      this.rowSliderDouble("config.acechat.chat.hold_min", 0, 5, true, " 秒", true, CommsConfig.holdMinSeconds);
      this.rowSliderDouble("config.acechat.chat.hold_max", 1, 15, true, " 秒", true, CommsConfig.holdMaxSeconds);
      this.rowToggle("config.acechat.chat.blip", CommsConfig.blipEnabled);
      // ---- 出现音预设 + 参数 ----
      this.rowLabel("screen.acechat.sound.appear_preset");
      for (int start = 0; start < CommsSoundSynth.APPEAR_PRESETS.length; start += 3) {
         final int from = start;
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(48, (w - 12) / 3);
            for (int i = from; i < Math.min(from + 3, CommsSoundSynth.APPEAR_PRESETS.length); i++) {
               String id = CommsSoundSynth.APPEAR_PRESETS[i];
               boolean active = id.equals(CommsConfig.newSoundPreset.get());
               this.addRenderableWidget(Button.builder(
                  Component.literal((active ? "▶ " : "") + Component.translatable("config.acechat.sound.preset." + id).getString()),
                  b -> {
                     CommsConfig.newSoundPreset.set(id);
                     CommsConfig.save();
                     this.init();
                  }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
            }
         });
      }
      this.rowSliderInt("config.acechat.sound.new_volume", 0, 200, true, "", true, CommsConfig.newSoundVolume);
      this.rowSliderInt("config.acechat.sound.new_pitch", 50, 200, true, "", true, CommsConfig.newSoundPitch);
      this.rowSliderInt("config.acechat.sound.new_speed", 25, 400, true, "", true, CommsConfig.newSoundSpeed);
      this.rowSliderInt("config.acechat.sound.new_fade_in", 0, 200, true, " ms", false, CommsConfig.newSoundFadeIn);
      this.rowSliderInt("config.acechat.sound.new_fade_out", 0, 400, true, " ms", false, CommsConfig.newSoundFadeOut);
      this.rowToggle("config.acechat.chat.gone_sound", CommsConfig.goneSoundEnabled);
      // ---- 消失音预设 + 参数 ----
      this.rowLabel("screen.acechat.sound.gone_preset");
      for (int start = 0; start < CommsSoundSynth.GONE_PRESETS.length; start += 3) {
         final int from = start;
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(48, (w - 12) / 3);
            for (int i = from; i < Math.min(from + 3, CommsSoundSynth.GONE_PRESETS.length); i++) {
               String id = CommsSoundSynth.GONE_PRESETS[i];
               boolean active = id.equals(CommsConfig.goneSoundPreset.get());
               this.addRenderableWidget(Button.builder(
                  Component.literal((active ? "▶ " : "") + Component.translatable("config.acechat.sound.preset." + id).getString()),
                  b -> {
                     CommsConfig.goneSoundPreset.set(id);
                     CommsConfig.save();
                     this.init();
                  }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
            }
         });
      }
      this.rowSliderInt("config.acechat.sound.gone_volume", 0, 200, true, "", true, CommsConfig.goneSoundVolume);
      this.rowSliderInt("config.acechat.sound.gone_pitch", 50, 200, true, "", true, CommsConfig.goneSoundPitch);
      this.rowSliderInt("config.acechat.sound.gone_speed", 25, 400, true, "", true, CommsConfig.goneSoundSpeed);
      this.rowSliderInt("config.acechat.sound.gone_fade_in", 0, 200, true, " ms", false, CommsConfig.goneSoundFadeIn);
      this.rowSliderInt("config.acechat.sound.gone_fade_out", 0, 400, true, " ms", false, CommsConfig.goneSoundFadeOut);
      this.rowSliderPercent("config.acechat.chat.volume", 0, 100, true, "", true, CommsConfig.soundVolume);
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.chat.test_sound"), b -> {
            CommsState.playTestSounds();
            this.notify("screen.acechat.chat.test_sound.done");
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.chat.test_sound.tooltip"))).build()
      ));
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.chat.open_sound_dir"), b -> {
            if (!openFolder(CustomSounds.soundDir())) {
               this.notify("screen.acechat.open_dir.failed");
            }
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.chat.open_sound_dir.tooltip"))).build()
      ));
      this.rowLabel("screen.acechat.chat.sound_hint");
   }

   private void portraitRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.PORTRAIT);

      // ============ 第 1 页：皮肤 ============
      this.rowGroupLabel("screen.acechat.group.skin");
      this.rowToggle("config.acechat.portrait.enabled", CommsConfig.portraitEnabled);
      this.rowToggle("config.acechat.portrait.always_visible", CommsConfig.portraitAlwaysVisible);
      this.rowToggle("config.acechat.portrait.follow_chat", CommsConfig.portraitFollowChat);
      this.rowGroupLabel("screen.acechat.group.skin_shape");
      this.rowToggle("config.acechat.portrait.outer_layer", CommsConfig.portraitOuterLayer);
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(this.photoModeText(), b -> {
            int next = (CommsConfig.portraitPhotoMode.get() + 1) % 5;
            CommsConfig.portraitPhotoMode.set(next);
            b.setMessage(this.photoModeText());
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.photo_mode.tooltip"))).build()
      ));
      this.rowSliderInt("config.acechat.portrait.photo_height", 30, 90, true, "", true, CommsConfig.portraitPhotoHeight);
      this.rowToggle("config.acechat.portrait.photo_divider", CommsConfig.portraitPhotoDivider);
      this.rowSliderInt("config.acechat.portrait.bust_scale", 40, 150, true, "", true, CommsConfig.portraitBustScale);
      this.rowSliderInt("config.acechat.portrait.skin_offset_x", -120, 120, true, " px", false, CommsConfig.skinOffsetX);
      this.rowSliderInt("config.acechat.portrait.skin_offset_y", -120, 120, true, " px", false, CommsConfig.skinOffsetY);
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.portrait.reload_skin"), b -> {
            CommsState.refreshPortrait(Minecraft.getInstance());
            this.notify("screen.acechat.portrait.reload_skin.done");
            this.init();
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.reload_skin.tooltip")))
            .build()
      ));
      this.rowStatus(this.skinStatusText());
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.portrait.reload_render"), b -> {
            cn.acechat.client.PortraitFiles.clearCache();
            cn.acechat.client.PortraitFilters.clearCache();
            cn.acechat.client.DeathPortraits.clearCache();
            cn.acechat.client.PortraitGrade.invalidate();
            cn.acechat.client.CommsHud.clearRenderCache();
            CommsState.refreshPortrait(Minecraft.getInstance());
            this.notify("screen.acechat.portrait.reload_render.done");
            this.init();
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.reload_render.tooltip"))).build()
      ));

      // ============ 第 2 页：立绘 ============
      this.rowPageBreak();
      this.rowGroupLabel("screen.acechat.group.art");
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(80, (w - 6) / 2);
         String[] keys = {"screen.acechat.portrait.mode_skin", "screen.acechat.portrait.mode_image"};
         for (int i = 0; i < 2; i++) {
            final boolean image = i == 1;
            boolean active = CommsConfig.portraitUseImage.get() == image;
            this.addRenderableWidget(Button.builder(
               Component.literal((active ? "\u25b6 " : "") + Component.translatable(keys[i]).getString()), b -> {
                  CommsConfig.portraitUseImage.set(image);
                  CommsConfig.save();
                  this.init();
               }).bounds(x + i * (bw + 6), y, bw, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.mode.tooltip",
                  PortraitFiles.portraitDir().toAbsolutePath().toString()))).build());
         }
      });
      this.rowSliderInt("config.acechat.portrait.image_scale", 10, 400, true, "", true, CommsConfig.portraitImageScale);
      this.rowSliderInt("config.acechat.portrait.image_offset_x", -200, 200, true, "", false, CommsConfig.portraitImageOffsetX);
      this.rowSliderInt("config.acechat.portrait.image_offset_y", -200, 200, true, "", false, CommsConfig.portraitImageOffsetY);
      this.rowLabel("screen.acechat.portrait.image_hint");
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.portrait.rescan"), b -> {
            PortraitFiles.rescan();
            this.notify("screen.acechat.portrait.rescanned");
            this.init();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.rescan.tooltip"))).build()
      ));
      List<String> portraits = PortraitFiles.userFiles();
      for (int start = 0; start < portraits.size(); start += 3) {
         final int from = start;
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(48, (w - 12) / 3);
            for (int i = from; i < Math.min(from + 3, portraits.size()); i++) {
               String file = portraits.get(i);
               boolean active = file.equals(CommsConfig.portraitImageName.get());
               this.addRenderableWidget(Button.builder(
                  Component.literal((active ? "\u25b6 " : "") + file), b -> {
                     CommsConfig.portraitImageName.set(file);
                     CommsConfig.portraitUseImage.set(true);
                     CommsConfig.save();
                     this.init();
                  }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
            }
         });
      }
      if (portraits.isEmpty()) {
         this.rowLabel("screen.acechat.portrait.image_empty");
      }
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.portrait.open_dir"), b -> {
            if (!openFolder(PortraitFiles.portraitDir())) {
               this.notify("screen.acechat.open_dir.failed");
            }
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.open_dir.tooltip"))).build()
      ));
      this.rowLabel("screen.acechat.portrait.image_hint2");

      // ============ 第 3 页：背景板 / 边框 / 显示效果 ============
      this.rowPageBreak();
      this.rowGroupLabel("screen.acechat.group.panel");
      this.rowToggle("config.acechat.portrait.backdrop", CommsConfig.portraitBackdropEnabled);
      this.rowColor("config.acechat.portrait.backdrop_color", CommsConfig.portraitBackdropColor);
      this.rowSliderInt("config.acechat.portrait.backdrop_alpha", 0, 100, true, "", true, CommsConfig.portraitBackdropAlpha);
      this.rowSliderInt("config.acechat.portrait.backdrop_padding", 0, 16, true, "", false, CommsConfig.portraitBackdropPadding);
      this.rowColor("config.acechat.portrait.panel_color", CommsConfig.portraitPanelColor);
      this.rowSliderInt("config.acechat.portrait.panel_alpha", 0, 100, true, "", true, CommsConfig.portraitPanelAlpha);
      this.rowColor("config.acechat.portrait.border_color", CommsConfig.portraitBorderColor);
      this.rowSliderInt("config.acechat.portrait.border_thickness", 0, 2, true, "", false, CommsConfig.portraitBorderThickness);
      this.rowGroupLabel("screen.acechat.group.effect");
      this.rowSliderInt("config.acechat.portrait.opacity", 0, 100, true, "", true, CommsConfig.portraitOpacity);
      this.rowSliderInt("config.acechat.portrait.brightness", 30, 100, true, "", true, CommsConfig.portraitBrightness);
      this.rowColor("config.acechat.portrait.tint", CommsConfig.portraitTint);

      // ============ 第 4 页：死亡覆盖 ============
      this.rowPageBreak();
      this.rowGroupLabel("screen.acechat.group.death");
      this.rowToggle("config.acechat.portrait.death_overlay", CommsConfig.deathOverlayEnabled);
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(80, (w - 6) / 2);
         boolean preset = DeathPortraits.PRESET_NO_SIGN.equals(CommsConfig.deathPreset.get());
         this.addRenderableWidget(Button.builder(
            Component.literal((preset ? "\u25b6 " : "") + Component.translatable("screen.acechat.portrait.death_preset").getString()),
            b -> {
               CommsConfig.deathPreset.set(DeathPortraits.PRESET_NO_SIGN);
               CommsConfig.save();
               this.init();
            }).bounds(x, y, bw, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.death_preset.tooltip"))).build());
         this.addRenderableWidget(Button.builder(
            Component.literal((preset ? "" : "\u25b6 ") + Component.translatable("screen.acechat.portrait.death_no_preset").getString()),
            b -> {
               CommsConfig.deathPreset.set("");
               CommsConfig.save();
               this.init();
            }).bounds(x + bw + 6, y, Math.max(80, w - bw - 6), ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.death_no_preset.tooltip"))).build());
      });
      this.rowSliderInt("config.acechat.portrait.death_max_size", 64, 1024, true, " px", false, CommsConfig.deathImageMaxSize);
      this.rowSliderInt("config.acechat.portrait.death_scale", 20, 400, true, " %", false, CommsConfig.deathImageScale);
      this.rowSliderInt("config.acechat.portrait.death_offset_x", -200, 200, true, " px", false, CommsConfig.deathImageOffsetX);
      this.rowSliderInt("config.acechat.portrait.death_offset_y", -200, 200, true, " px", false, CommsConfig.deathImageOffsetY);
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.portrait.open_deaths"), b -> {
            if (!openFolder(DeathPortraits.deathsDir())) {
               this.notify("screen.acechat.open_dir.failed");
            }
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.portrait.open_deaths.tooltip"))).build()
      ));
      this.rowLabel("screen.acechat.portrait.death_hint");

      // ============ 第 5 页：卡片布局与位置 ============
      this.rowPageBreak();
      this.rowGroupLabel("screen.acechat.group.layout");
      this.rowSliderInt("config.acechat.portrait.width", 80, 300, true, "", false, CommsConfig.portraitWidth);
      this.rowSliderInt("config.acechat.portrait.height", 90, 340, true, "", false, CommsConfig.portraitHeight);
      this.rowSliderDouble("config.acechat.portrait.scale", 0.15, 2.0, false, " \u00d7", false, CommsConfig.portraitScale);
      this.rowSliderPercent("config.acechat.portrait.ratio_x", 0, 100, true, "", true, CommsConfig.portraitXRatio);
      this.rowSliderPercent("config.acechat.portrait.ratio_y", 0, 100, true, "", true, CommsConfig.portraitYRatio);
      this.rowSliderInt("config.acechat.portrait.offset_x", -300, 300, true, "", false, CommsConfig.portraitXOffset);
      this.rowSliderInt("config.acechat.portrait.offset_y", -300, 300, true, "", false, CommsConfig.portraitYOffset);
   }

   private void previewRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.PREVIEW);
      this.addRow(1, (x, y, w) -> {
         String[] keys = {
            "screen.acechat.preview.drag_off", "screen.acechat.preview.drag_card",
            "screen.acechat.preview.drag_text", "screen.acechat.preview.drag_all"
         };
         int bw = Math.max(48, (w - 3 * 6) / 4);
         for (int i = 0; i < keys.length; i++) {
            final int target = i;
            boolean active = CommsConfig.previewDragTarget.get() == i;
            this.addRenderableWidget(Button.builder(
               Component.literal((active ? "▶ " : "") + Component.translatable(keys[i]).getString()), b -> {
                  CommsConfig.previewDragTarget.set(target);
                  CommsConfig.save();
                  this.init();
               }).bounds(x + i * (bw + 6), y, bw, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("config.acechat.preview.drag.tooltip"))).build());
         }
      });
      this.rowLabel("config.acechat.preview.hint");
      this.addRow(1, (x, y, w) -> {
         String[] keys = {
            "config.acechat.preview.anchor_tl", "config.acechat.preview.anchor_tr", "config.acechat.preview.anchor_bl",
            "config.acechat.preview.anchor_br", "config.acechat.preview.anchor_center"
         };
         double[][] ratios = {{0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}, {0.5, 0.5}};
         int buttonWidth = Math.max(40, (w - 4 * 6) / 5);
         for (int i = 0; i < keys.length; i++) {
            final double rx = ratios[i][0];
            final double ry = ratios[i][1];
            this.addRenderableWidget(
               Button.builder(Component.translatable(keys[i]), b -> {
                  CommsConfig.portraitXRatio.set(rx);
                  CommsConfig.portraitYRatio.set(ry);
                  CommsConfig.portraitXOffset.set(0);
                  CommsConfig.portraitYOffset.set(0);
                  this.init();
               }).bounds(x + i * (buttonWidth + 6), y, buttonWidth, ROW_H).build()
            );
         }
      });
   }

   private void namesRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.NAMES);
      // 第一行：复制 / 删除 / 清空
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(50, (w - 12) / 3);
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.names.copy"), b -> {
            if (!CommsConfig.nameColors.isEmpty()) {
               CommsConfig.NameColor src = CommsConfig.nameColors.get(this.clampIndex(this.selectedName, CommsConfig.nameColors.size()));
               CommsConfig.nameColors.add(new CommsConfig.NameColor(src.uuid, src.name, src.color, src.note));
               this.selectedName = CommsConfig.nameColors.size() - 1;
               CommsConfig.save();
               this.init();
            }
         }).bounds(x, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.names.copy.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.names.delete"), b -> {
            if (!CommsConfig.nameColors.isEmpty()) {
               CommsConfig.nameColors.remove(this.clampIndex(this.selectedName, CommsConfig.nameColors.size()));
               this.selectedName = Math.max(0, this.selectedName - 1);
               CommsConfig.save();
               this.init();
            }
         }).bounds(x + bw + 6, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.names.delete.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.names.clear"), b -> {
            CommsConfig.nameColors.clear();
            this.selectedName = 0;
            CommsConfig.save();
            this.notify("screen.acechat.names.cleared");
            this.init();
         }).bounds(x + (bw + 6) * 2, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.names.clear.tooltip"))).build());
      });

      // 在线玩家选项卡：点一下就加入名单（已在名单里的则直接选中）
      List<PlayerRef> online = onlinePlayers();
      this.rowLabel(online.isEmpty() ? "config.acechat.names.online_empty" : "config.acechat.names.online_hint");
      for (PlayerRef ref : online) {
         this.addRow(1, (x, y, w) -> {
            Button button = Button.builder(Component.empty(), b -> this.pickPlayer(ref)).bounds(x, y, w, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("config.acechat.names.tab.tooltip"))).build();
            this.addRenderableWidget(button);
            this.live.add(() -> button.setMessage(this.playerTabText(ref)));
         });
      }

      // 已配置的条目（也是选项卡：点一下选中编辑）
      if (!CommsConfig.nameColors.isEmpty()) {
         this.rowLabel("config.acechat.names.list_hint");
      }

      for (int i = 0; i < CommsConfig.nameColors.size(); i++) {
         final int index = i;
         this.addRow(1, (x, y, w) -> {
            Button button = Button.builder(Component.empty(), b -> {
               this.saveFields();
               this.selectedName = index;
               this.init();
            }).bounds(x, y, w, ROW_H).build();
            this.addRenderableWidget(button);
            this.live.add(() -> {
               if (index < CommsConfig.nameColors.size()) {
                  CommsConfig.NameColor entry = CommsConfig.nameColors.get(index);
                  button.setMessage(this.listText(index, entry.name, entry.color));
               }
            });
         });
      }

      if (CommsConfig.nameColors.isEmpty()) {
         this.rowLabel("screen.acechat.names.empty");
         return;
      }

      CommsConfig.NameColor selected = CommsConfig.nameColors.get(this.clampIndex(this.selectedName, CommsConfig.nameColors.size()));
      this.rowText("config.acechat.names.player", selected.name, v -> selected.name = v);
      this.rowText("config.acechat.names.uuid", selected.uuid, v -> selected.uuid = v);
      this.rowText("config.acechat.names.note", selected.note, v -> selected.note = v);
      this.rowColorValue("config.acechat.names.color", () -> selected.color, v -> selected.color = v);
   }

   /** 在线玩家（优先用连接里的玩家列表，单人游戏也能拿到自己）。 */
   private List<PlayerRef> onlinePlayers() {
      Minecraft minecraft = Minecraft.getInstance();
      List<PlayerRef> result = new ArrayList<>();
      if (minecraft == null) {
         return result;
      }

      if (minecraft.getConnection() != null) {
         for (var info : minecraft.getConnection().getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile != null && profile.getId() != null && profile.getName() != null) {
               result.add(new PlayerRef(profile.getId(), profile.getName()));
            }
         }
      } else if (minecraft.level != null) {
         for (var player : minecraft.level.players()) {
            result.add(new PlayerRef(player.getUUID(), player.getGameProfile().getName()));
         }
      }

      result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      return result;
   }

   private void pickPlayer(PlayerRef ref) {
      this.saveFields();
      int index = this.findEntry(ref);
      if (index < 0) {
         int color = PALETTE[CommsConfig.nameColors.size() % PALETTE.length];
         CommsConfig.nameColors.add(new CommsConfig.NameColor(ref.uuid().toString(), ref.name(), color, ""));
         index = CommsConfig.nameColors.size() - 1;
      }

      this.selectedName = index;
      CommsConfig.save();
      this.init();
   }

   private int findEntry(PlayerRef ref) {
      for (int i = 0; i < CommsConfig.nameColors.size(); i++) {
         CommsConfig.NameColor entry = CommsConfig.nameColors.get(i);
         if (entry.uuid != null && !entry.uuid.isBlank() && entry.uuid.equalsIgnoreCase(ref.uuid().toString())) {
            return i;
         }

         if (entry.name != null && entry.name.equalsIgnoreCase(ref.name())) {
            return i;
         }
      }

      return -1;
   }

   private Component playerTabText(PlayerRef ref) {
      boolean configured = this.findEntry(ref) >= 0;
      return Component.literal(ref.name() + "   ")
         .append(Component.translatable(configured ? "config.acechat.names.tab.configured" : "config.acechat.names.tab.add"));
   }

   private record PlayerRef(java.util.UUID uuid, String name) {
   }

   /** 滤镜模板：叠加滤镜 + 调色（烘焙）+ 模板保存/套用。 */
   /** 当前内置特效颜色按钮的显示文本（带色块字符与十六进制）。 */
   private net.minecraft.network.chat.Component effectColorText() {
      int argb = CommsConfig.effectColorFor(CommsConfig.filterName.get(), 0xFF000000);
      String hex = String.format("%08X", argb);
      return Component.translatable("config.acechat.filter.effect_color").copy()
         .append(Component.literal("：" + hex));
   }

   private void filtersRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.FILTERS);
      // ---- 叠加滤镜 ----
      this.rowLabel("screen.acechat.filters.overlay");
      this.rowToggle("config.acechat.filter.enabled", CommsConfig.filterEnabled);
      // 滤镜选项：每行 3 个（每个「行槽」只放一行按钮，避免和下面的控件叠在一起）
      List<String> options = new ArrayList<>();
      options.add("");
      options.addAll(java.util.Arrays.asList(PortraitFilters.BUILTIN_IDS));
      options.addAll(PortraitFilters.userFiles());
      for (int start = 0; start < options.size(); start += 3) {
         final int from = start;
         this.addRow(1, (x, y, w) -> {
            int bw = Math.max(48, (w - 12) / 3);
            for (int i = from; i < Math.min(from + 3, options.size()); i++) {
               String id = options.get(i);
               boolean active = id.equals(CommsConfig.filterName.get());
               Component label = Component.literal((active ? "▶ " : "") + this.filterLabel(id));
               this.addRenderableWidget(Button.builder(label, b -> {
                  CommsConfig.filterName.set(id);
                  CommsConfig.save();
                  this.init();
               }).bounds(x + (i - from) * (bw + 6), y, bw, ROW_H).build());
            }
         });
      }
      this.rowSliderInt("config.acechat.filter.alpha", 0, 100, true, "", true, CommsConfig.filterAlpha);
      this.rowGroupLabel("screen.acechat.group.filter_mask");
      this.rowColor("config.acechat.filter.tint", CommsConfig.filterTint);
      // 内置特效可以单独调色（选中的是内置滤镜时才显示）
      if (CommsConfig.filterName.get() != null && CommsConfig.filterName.get().startsWith("builtin:")) {
         this.addRow(1, (x, y, w) -> this.addRenderableWidget(
            Button.builder(this.effectColorText(), b -> Minecraft.getInstance().setScreen(
               new CommsColorScreen(this, "config.acechat.filter.effect_color",
                  CommsConfig.effectColorFor(CommsConfig.filterName.get(), 0xFF000000), argb -> {
                     CommsConfig.setEffectColor(CommsConfig.filterName.get(), argb);
                     CommsConfig.save();
                     b.setMessage(this.effectColorText());
                  }))).bounds(x, y, w, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("config.acechat.filter.effect_color.tooltip"))).build()
         ));
      }

      this.rowGroupLabel("screen.acechat.group.filter_grade");
      this.rowSliderInt("config.acechat.filter.brightness", 30, 180, true, "", true, CommsConfig.filterBrightness);
      this.rowSliderInt("config.acechat.filter.contrast", 30, 200, true, "", true, CommsConfig.filterContrast);
      this.rowSliderInt("config.acechat.filter.saturation", 0, 200, true, "", true, CommsConfig.filterSaturation);
      this.rowSliderInt("config.acechat.filter.hue", -180, 180, true, "\u00b0", false, CommsConfig.filterHue);
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(this.blendText(), b -> {
            CommsConfig.filterBlend.set((CommsConfig.filterBlend.get() + 1) % 4);
            b.setMessage(this.blendText());
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.filter.blend.tooltip"))).build()
      ));
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.filters.rescan"), b -> {
            PortraitFilters.rescan();
            this.notify("screen.acechat.filters.rescanned");
            this.init();
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.filter.rescan.tooltip", PortraitFilters.filtersDir().toAbsolutePath().toString())))
            .build()
      ));

      // ---- 调色（CPU 烘焙）----
      this.rowLabel("screen.acechat.filters.grade");
      this.rowToggle("config.acechat.grade.enabled", CommsConfig.gradeEnabled);
      this.rowSlider("config.acechat.grade.brightness", 30, 180, true, "", true, () -> (double) CommsConfig.gradeBrightness.get(), v -> this.setGrade(() -> CommsConfig.gradeBrightness.set((int) v)));
      this.rowSlider("config.acechat.grade.contrast", 30, 200, true, "", true, () -> (double) CommsConfig.gradeContrast.get(), v -> this.setGrade(() -> CommsConfig.gradeContrast.set((int) v)));
      this.rowSlider("config.acechat.grade.saturation", 0, 200, true, "", true, () -> (double) CommsConfig.gradeSaturation.get(), v -> this.setGrade(() -> CommsConfig.gradeSaturation.set((int) v)));
      this.rowSlider("config.acechat.grade.hue", -180, 180, true, "°", false, () -> (double) CommsConfig.gradeHue.get(), v -> this.setGrade(() -> CommsConfig.gradeHue.set((int) v)));
      this.rowToggle("config.acechat.grade.duotone", CommsConfig.duotoneEnabled);
      this.rowColor("config.acechat.grade.dark", CommsConfig.duotoneDark);
      this.rowColor("config.acechat.grade.light", CommsConfig.duotoneLight);
      this.rowSlider("config.acechat.grade.amount", 0, 100, true, "", true, () -> (double) CommsConfig.duotoneAmount.get(), v -> this.setGrade(() -> CommsConfig.duotoneAmount.set((int) v)));

      // ---- 模板 ----
      this.rowLabel("screen.acechat.filters.templates");
      this.rowText("config.acechat.filter.template_name", this.templateName, v -> this.templateName = v);
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(60, (w - 12) / 2);
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.filters.save"), b -> {
            String name = this.templateName == null ? "" : this.templateName.trim();
            if (name.isEmpty()) {
               name = "模板 " + (CommsConfig.filterTemplates.size() + 1);
            }

            final String templateName = name;
            CommsConfig.filterTemplates.removeIf(t -> t.name != null && t.name.equalsIgnoreCase(templateName));
            CommsConfig.filterTemplates.add(CommsConfig.captureTemplate(templateName));
            this.templateName = templateName;
            CommsConfig.save();
            this.notify("screen.acechat.filters.saved");
            this.init();
         }).bounds(x, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.filter.save.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.filters.delete"), b -> {
            String name = this.templateName == null ? "" : this.templateName.trim();
            CommsConfig.filterTemplates.removeIf(t -> t.name != null && t.name.equalsIgnoreCase(name));
            CommsConfig.save();
            this.notify("screen.acechat.filters.deleted");
            this.init();
         }).bounds(x + bw + 6, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.filter.delete.tooltip"))).build());
      });
      for (int i = 0; i < CommsConfig.filterTemplates.size(); i++) {
         CommsConfig.FilterTemplate template = CommsConfig.filterTemplates.get(i);
         this.addRow(1, (x, y, w) -> this.addRenderableWidget(
            Button.builder(Component.literal(template.name + "  ·  " + this.filterLabel(template.filterName)), b -> {
               this.templateName = template.name;
               CommsConfig.applyTemplate(template);
               this.notify("screen.acechat.filters.applied");
               this.init();
            }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.filter.template.tooltip"))).build()
         ));
      }

      if (CommsConfig.filterTemplates.isEmpty()) {
         this.rowLabel("screen.acechat.filters.empty");
      }
   }

   /** 调色参数改了要丢掉已烘焙的皮肤副本，下次绘制重新烘焙。 */
   private void setGrade(Runnable change) {
      change.run();
      PortraitGrade.invalidate();
   }

   /** 混合模式按钮文字。 */
   private Component blendText() {
      String[] keys = {"config.acechat.blend.normal", "config.acechat.blend.multiply", "config.acechat.blend.screen", "config.acechat.blend.add"};
      int index = Math.max(0, Math.min(keys.length - 1, CommsConfig.filterBlend.get()));
      return Component.translatable("config.acechat.filter.blend")
         .copy().append(Component.literal("：" + Component.translatable(keys[index]).getString()));
   }

   /** 滤镜名（内置的走语言键，用户 PNG 直接显示文件名）。 */
   private String filterLabel(String id) {
      if (id == null || id.isEmpty()) {
         return Component.translatable("config.acechat.filter.none").getString();
      }

      if (id.startsWith("builtin:")) {
         String key = "config.acechat.filter." + id.substring("builtin:".length());
         String translated = Component.translatable(key).getString();
         return translated.equals(key) ? id : translated;
      }

      return id;
   }

   /**
    * 文字元素：三个固定内置（玩家名称 / 生命 / 饥饿）只能改「是否显示 / 缩放 / 位置」，
    * 自定义元素可以任意增删、编辑内容、复制。生命与饥饿同属「生命 / 饱食度」这一节。
    */
   private void elementsRows() {
      this.cardBackRow();
      this.rowResetButtons(CommsConfig.Section.ELEMENTS);

      CommsConfig.TextElement name = CommsConfig.builtIn(CommsConfig.KIND_NAME);
      CommsConfig.TextElement vitals = CommsConfig.builtIn(CommsConfig.KIND_VITALS);

      // ---------- 玩家名称（固定） ----------
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.elements.reset_positions"), b -> {
            int count = CommsConfig.resetElementPositions();
            this.notify(Component.translatable("screen.acechat.elements.reset_positions.done", count).getString());
            this.init();
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("config.acechat.elements.reset_positions.tooltip"))).build()
      ));
      this.rowLabel("screen.acechat.elements.name_section");
      this.rowElementToggle("config.acechat.elements.show", name);
      this.rowElementSlider("config.acechat.elements.size", 6, 24, name, e -> e.size, (e, v) -> e.size = v);
      this.rowElementAlign("config.acechat.elements.horizontal", name, true);
      this.rowElementAlign("config.acechat.elements.vertical", name, false);
      this.rowElementSlider("config.acechat.elements.x", -300, 300, name, e -> e.x, (e, v) -> e.x = v);
      this.rowElementSlider("config.acechat.elements.y", -300, 300, name, e -> e.y, (e, v) -> e.y = v);

      // ---------- 生命 / 饱食度（固定） ----------
      this.rowLabel("screen.acechat.elements.vitals_section");
      this.rowElementToggle("config.acechat.elements.show", vitals);
      this.rowElementSlider("config.acechat.elements.size", 6, 24, vitals, e -> e.size, (e, v) -> e.size = v);
      this.rowElementAlign("config.acechat.elements.horizontal", vitals, true);
      this.rowElementAlign("config.acechat.elements.vertical", vitals, false);
      this.rowElementSlider("config.acechat.elements.x", -300, 300, vitals, e -> e.x, (e, v) -> e.x = v);
      this.rowElementSlider("config.acechat.elements.y", -300, 300, vitals, e -> e.y, (e, v) -> e.y = v);

      // ---------- 饥饿（固定，与生命同属这一节，默认在生命下面一行） ----------
      CommsConfig.TextElement food = CommsConfig.builtIn(CommsConfig.KIND_FOOD);
      this.rowLabel("screen.acechat.elements.food_section");
      this.rowElementToggle("config.acechat.elements.show", food);
      this.rowElementSlider("config.acechat.elements.size", 6, 24, food, e -> e.size, (e, v) -> e.size = v);
      this.rowElementAlign("config.acechat.elements.horizontal", food, true);
      this.rowElementAlign("config.acechat.elements.vertical", food, false);
      this.rowElementSlider("config.acechat.elements.x", -300, 300, food, e -> e.x, (e, v) -> e.x = v);
      this.rowElementSlider("config.acechat.elements.y", -300, 300, food, e -> e.y, (e, v) -> e.y = v);

      // ---------- 自定义文字元素 ----------
      this.rowLabel("screen.acechat.elements.custom_section");
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(60, (w - 12) / 3);
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.elements.add"), b -> {
            CommsConfig.TextElement created = new CommsConfig.TextElement("自定义文字", CommsConfig.Horizontal.LEFT,
               CommsConfig.Vertical.BOTTOM, 6, -4, 9, 0xFFFFFFFF, true, 160);
            created.kind = CommsConfig.KIND_CUSTOM;
            created.visible = Boolean.TRUE;
            CommsConfig.textElements.add(created);
            this.selectedCustom = CommsConfig.customElements().size() - 1;
            CommsConfig.save();
            this.init();
         }).bounds(x, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.elements.add.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.elements.copy"), b -> {
            List<CommsConfig.TextElement> customs = CommsConfig.customElements();
            if (!customs.isEmpty()) {
               CommsConfig.TextElement source = customs.get(this.clampIndex(this.selectedCustom, customs.size()));
               CommsConfig.TextElement duplicate = source.copy();
               duplicate.kind = CommsConfig.KIND_CUSTOM;
               CommsConfig.textElements.add(duplicate);
               this.selectedCustom = CommsConfig.customElements().size() - 1;
               CommsConfig.save();
               this.init();
            }
         }).bounds(x + bw + 6, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.elements.copy.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.elements.delete"), b -> {
            List<CommsConfig.TextElement> customs = CommsConfig.customElements();
            if (!customs.isEmpty()) {
               CommsConfig.textElements.remove(customs.get(this.clampIndex(this.selectedCustom, customs.size())));
               this.selectedCustom = Math.max(0, this.selectedCustom - 1);
               CommsConfig.save();
               this.init();
            }
         }).bounds(x + (bw + 6) * 2, y, bw, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.elements.delete.tooltip"))).build());
      });

      List<CommsConfig.TextElement> customs = CommsConfig.customElements();
      if (customs.isEmpty()) {
         this.rowLabel("screen.acechat.elements.empty");
         return;
      }

      // 自定义元素列表（点一下选中）
      for (int i = 0; i < customs.size(); i++) {
         final int index = i;
         CommsConfig.TextElement element = customs.get(i);
         this.addRow(1, (x, y, w) -> {
            Button button = Button.builder(Component.empty(), b -> {
               this.selectedCustom = index;
               this.init();
            }).bounds(x, y, w, ROW_H).build();
            this.addRenderableWidget(button);
            this.live.add(() -> {
               List<CommsConfig.TextElement> current = CommsConfig.customElements();
               if (index < current.size()) {
                  CommsConfig.TextElement item = current.get(index);
                  boolean active = index == this.clampIndex(this.selectedCustom, current.size());
                  button.setMessage(Component.literal((active ? "▶ " : "") + (index + 1) + ". " + item.content));
               }
            });
         });
      }

      CommsConfig.TextElement selected = customs.get(this.clampIndex(this.selectedCustom, customs.size()));
      // 内容可自由编辑（这就是「客户端自定义编辑内容」）
      this.rowText("config.acechat.elements.content", selected.content, v -> selected.content = v);
      this.rowElementToggle("config.acechat.elements.show", selected);
      this.rowElementSlider("config.acechat.elements.size", 6, 24, selected, e -> e.size, (e, v) -> e.size = v);
      this.rowElementAlign("config.acechat.elements.horizontal", selected, true);
      this.rowElementAlign("config.acechat.elements.vertical", selected, false);
      this.rowElementSlider("config.acechat.elements.x", -300, 300, selected, e -> e.x, (e, v) -> e.x = v);
      this.rowElementSlider("config.acechat.elements.y", -300, 300, selected, e -> e.y, (e, v) -> e.y = v);
      this.rowElementSlider("config.acechat.elements.max_width", 20, 400, selected, e -> e.maxWidth, (e, v) -> e.maxWidth = v);
      this.rowColorValue("config.acechat.elements.color", () -> selected.color, v -> selected.color = v);
   }


   /** 在系统资源管理器里打开目录（失败返回 false）。 */
   private static boolean openFolder(java.nio.file.Path path) {
      try {
         java.nio.file.Files.createDirectories(path);
         java.io.File file = path.toAbsolutePath().toFile();
         if (java.awt.Desktop.isDesktopSupported()
            && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            java.awt.Desktop.getDesktop().open(file);
            return true;
         }

         String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
         if (os.contains("win")) {
            new ProcessBuilder("explorer.exe", file.getAbsolutePath()).start();
            return true;
         }

         if (os.contains("mac")) {
            new ProcessBuilder("open", file.getAbsolutePath()).start();
            return true;
         }

         new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
         return true;
      } catch (Exception | LinkageError error) {
         LOGGER.warn("[acechat] 打开目录失败: " + path, error);
         return false;
      }
   }

   /**
    * 分区顶部的两个恢复按钮（同一行槽内并排，不会与其它控件重叠）：
    * <ul>
    *   <li><b>全部恢复默认</b>：只在分区第一页显示，恢复整个分区的所有页设置；</li>
    *   <li><b>单页恢复默认</b>：每一页都显示，只恢复当前页面上的选项。</li>
    * </ul>
    */
   private void rowResetButtons(CommsConfig.Section section) {
      final boolean firstPage = this.page <= 0;
      this.addRow(1, (x, y, w) -> {
         if (firstPage) {
            int bw = Math.max(72, (w - 6) / 2);
            this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.reset.section_all"), b -> {
               CommsConfig.resetSection(section);
               CommsConfig.save();
               this.notify("screen.acechat.reset.section_done");
               this.init();
            }).bounds(x, y, bw, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("screen.acechat.reset.section_all.tooltip"))).build());
            this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.reset.page"), b -> this.resetCurrentPage())
               .bounds(x + bw + 6, y, Math.max(72, w - bw - 6), ROW_H)
               .tooltip(Tooltip.create(Component.translatable("screen.acechat.reset.page.tooltip"))).build());
         } else {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.reset.page"), b -> this.resetCurrentPage())
               .bounds(x, y, w, ROW_H)
               .tooltip(Tooltip.create(Component.translatable("screen.acechat.reset.page.tooltip"))).build());
         }
      });
   }

   /** 只恢复「当前页面」上的选项：执行本页各行登记的重置动作。 */
   private void resetCurrentPage() {
      int applied = 0;
      for (Runnable reset : this.pageResets) {
         try {
            reset.run();
            applied++;
         } catch (RuntimeException ignored) {
            // 单个选项失败不影响其它选项
         }
      }

      CommsConfig.save();
      this.notify(Component.translatable("screen.acechat.reset.page_done", applied).getString());
      this.init();
   }

   /** 分区级「恢复本页默认」按钮（每个设置页第一行都有）。 */
   private void rowSectionReset(CommsConfig.Section section, String key) {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable(key), b -> {
            CommsConfig.resetSection(section);
            this.selectedElement = 0;
            this.selectedCustom = 0;
            this.notify("screen.acechat.reset.section_done");
            this.init();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build()
      ));
   }

   /** 绑定到「某个文字元素字段」的开关。 */
   private void rowElementToggle(String key, CommsConfig.TextElement element) {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(this.toggleText(key, CommsConfig.isVisible(element)), b -> {
            element.visible = !CommsConfig.isVisible(element);
            b.setMessage(this.toggleText(key, CommsConfig.isVisible(element)));
            CommsConfig.save();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build()
      ));
   }

   /** 绑定到「某个文字元素整数字段」的滑块。 */
   private void rowElementSlider(String key, int min, int max, CommsConfig.TextElement element,
                                 java.util.function.ToIntFunction<CommsConfig.TextElement> getter,
                                 java.util.function.ObjIntConsumer<CommsConfig.TextElement> setter) {
      this.rowSlider(key, min, max, true, "", false,
         () -> (double) getter.applyAsInt(element),
         v -> setter.accept(element, (int) v));
   }

   /** 绑定到「某个文字元素对齐字段」的循环按钮。 */
   private void rowElementAlign(String key, CommsConfig.TextElement element, boolean horizontal) {
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(this.alignText(key, element, horizontal), b -> {
            if (horizontal) {
               element.horizontal = CommsConfig.Horizontal.values()[(element.horizontal.ordinal() + 1) % 3];
            } else {
               element.vertical = CommsConfig.Vertical.values()[(element.vertical.ordinal() + 1) % 3];
            }

            b.setMessage(this.alignText(key, element, horizontal));
            CommsConfig.save();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build()
      ));
   }

   private Component alignText(String key, CommsConfig.TextElement element, boolean horizontal) {
      String align = horizontal ? element.horizontal.name().toLowerCase(java.util.Locale.ROOT)
                                : element.vertical.name().toLowerCase(java.util.Locale.ROOT);
      return Component.translatable(key).copy()
         .append(Component.literal("：" + Component.translatable("config.acechat.align." + align).getString()));
   }

   private void dataRows() {
      this.rowLabel("config.acechat.data.hint");
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.data.export"), b -> {
            Path path = CommsConfig.export();
            this.notify(path == null ? "screen.acechat.data.failed" : "screen.acechat.data.exported");
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.data.export.tooltip"))).build()
      ));
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.data.copy"), b -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
               minecraft.keyboardHandler.setClipboard(CommsConfig.exportString());
            }

            this.notify("screen.acechat.data.copied");
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.data.copy.tooltip"))).build()
      ));
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.data.import_clipboard"), b -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
               boolean ok = CommsConfig.importString(minecraft.keyboardHandler.getClipboard());
               this.notify(ok ? "screen.acechat.data.imported" : "screen.acechat.data.failed");
               this.init();
            }
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.data.import_clipboard.tooltip"))).build()
      ));
      this.rowText("config.acechat.data.import_json", "", v -> {
         // 只有看起来是完整配置（含 version 字段）才尝试导入，避免边打字边导入把配置重置
         if (v != null && !v.isBlank() && v.contains("\"version\"")) {
            boolean ok = CommsConfig.importString(v);
            this.notify(ok ? "screen.acechat.data.imported" : "screen.acechat.data.failed");
         }
      });
      // 整体配置的导入：从 export/ 的文件列表里挑一个（滚动选择框）
      this.rowLabel("screen.acechat.data.import_hint");
      this.addRow(4, (x, y, w) -> {
         FilePickList list = new FilePickList(x, y, w, 4 * ROW_STEP - 2);
         list.refresh(cn.acechat.client.AceChatFolders.exportDir());
         this.configImportList = list;
         this.addRenderableWidget(list);
      });
      this.addRow(1, (x, y, w) -> {
         int bw = Math.max(80, (w - 6) / 2);
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.data.import_selected"), b -> {
            String name = this.configImportList == null ? null : this.configImportList.selectedName();
            if (name == null) {
               this.notify("screen.acechat.data.import_pick_first");
            } else {
               boolean ok = CommsConfig.importFile(cn.acechat.client.AceChatFolders.exportDir().resolve(name));
               this.notify(ok ? Component.translatable("screen.acechat.data.imported_one", name).getString()
                  : "screen.acechat.data.failed");
               this.init();
            }
         }).bounds(x, y, bw, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.data.import_selected.tooltip"))).build());
         this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.data.reload_list"), b -> {
            if (this.configImportList != null) {
               this.configImportList.refresh(cn.acechat.client.AceChatFolders.exportDir());
            }

            this.notify("screen.acechat.data.list_reloaded");
         }).bounds(x + bw + 6, y, Math.max(80, w - bw - 6), ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.data.reload_list.tooltip"))).build());
      });
      // 直接打开模组配置文件夹（ACE_config/，导入导出的 json 就在它的 export/ 里）
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.data.open_config_dir"), b -> {
            if (!openFolder(cn.acechat.client.AceChatFolders.rootDir())) {
               this.notify("screen.acechat.open_dir.failed");
            }
         }).bounds(x, y, w, ROW_H)
            .tooltip(Tooltip.create(Component.translatable("screen.acechat.data.open_config_dir.tooltip"))).build()
      ));
      this.rowLabel("screen.acechat.data.cards_hint");
   }

   private void resetRows() {
      this.rowLabel("config.acechat.reset.hint");
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.reset.elements"), b -> {
            CommsConfig.resetElements();
            this.selectedElement = 0;
            this.notify("screen.acechat.reset.done");
            this.init();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.reset.elements.tooltip"))).build()
      ));
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(Component.translatable("screen.acechat.reset.all"), b -> {
            CommsConfig.saveAndResetDefaults();
            this.selectedElement = 0;
            this.selectedName = 0;
            this.notify("screen.acechat.reset.done");
            this.init();
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable("config.acechat.reset.all.tooltip"))).build()
      ));
   }

   // ------------------------------------------------------------------ 行工具

   private void addRow(int slots, RowSink sink) {
      this.rows.add(new Row(slots, sink));
   }

   private void rowLabel(String key) {
      this.addRow(1, (x, y, w) -> this.labels.add(new Label(x, y + 6, Component.translatable(key), 0xA8A8A8)));
   }

   /** 直接显示一段普通文字（语言键之外的内容，例如当前贴图诊断信息）。 */
   private void rowStatus(String text) {
      this.addRow(1, (x, y, w) -> this.labels.add(new Label(x, y + 6, Component.literal(text), 0x8FD8A0)));
   }

   private void rowToggle(String key, CommsConfig.Value<Boolean> value) {
      this.pageResets.add(() -> value.reset());
      this.addRow(1, (x, y, w) -> this.addRenderableWidget(
         Button.builder(this.toggleText(key, value.get()), b -> {
            value.set(!value.get());
            b.setMessage(this.toggleText(key, value.get()));
         }).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build()
      ));
   }

   private void rowSlider(String key, double min, double max, boolean integral, String suffix, boolean percent,
                          java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter) {
      this.rowSlider(key, min, max, integral, suffix, percent, getter, setter, null);
   }

   /** 绑定 CommsConfig 整数值的滑块（自带「单页恢复默认」支持）。 */
   private void rowSliderInt(String key, double min, double max, boolean integral, String suffix, boolean percent,
                             CommsConfig.Value<Integer> value) {
      this.rowSlider(key, min, max, integral, suffix, percent,
         () -> value.get().doubleValue(), v -> value.set((int) Math.round(v)), () -> value.reset());
   }

   /** 绑定 CommsConfig 小数值的滑块。 */
   private void rowSliderDouble(String key, double min, double max, boolean integral, String suffix, boolean percent,
                                CommsConfig.Value<Double> value) {
      this.rowSlider(key, min, max, integral, suffix, percent,
         () -> value.get(), v -> value.set(v), () -> value.reset());
   }

   /** 绑定 CommsConfig 小数值、界面按 ×100 百分比显示的滑块。 */
   private void rowSliderPercent(String key, double min, double max, boolean integral, String suffix, boolean percent,
                                 CommsConfig.Value<Double> value) {
      this.rowSlider(key, min, max, integral, suffix, percent,
         () -> value.get() * 100.0, v -> value.set(v / 100.0), () -> value.reset());
   }

   private void rowSlider(String key, double min, double max, boolean integral, String suffix, boolean percent,
                          java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter,
                          Runnable reset) {
      this.addRow(1, (x, y, w) -> {
         if (reset != null) {
            this.pageResets.add(reset);
         }

         this.addRenderableWidget(
            new CommsSlider(key, key + ".tooltip", x, y, w, ROW_H, getter, setter, min, max, integral, suffix, percent)
         );
      });
   }

   private void rowColor(String key, CommsConfig.Value<Integer> value) {
      this.pageResets.add(() -> value.reset());
      this.rowColorValue(key, value::get, value::set);
   }

   private void rowColorValue(String key, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
      this.addRow(1, (x, y, w) -> {
         Button button = Button.builder(this.colorText(key, getter.getAsInt()), b ->
            Minecraft.getInstance().setScreen(new CommsColorScreen(this, key, getter.getAsInt(), argb -> {
               setter.accept(argb);
               b.setMessage(this.colorText(key, argb));
            }))
         ).bounds(x, y, w, ROW_H).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build();
         this.addRenderableWidget(button);
      });
   }

   /** 分组提示：淡色分隔线 + 文字，用来区分同一页里不同的模块。 */
   /** 分组提示：淡色分隔线 + 文字，用来区分同一页里不同的模块。 */
   private void rowGroupLabel(String key) {
      this.addRow(1, (x, y, w) -> {
         this.labels.add(new Label(x, y + 6, Component.translatable(key), 0x9AA6B2));
         this.groupLines.add(new int[]{x, y - 5, x + w});
      });
   }

   /** 强制从此处换页（用于把不同模块放到各自的页）。 */
   private void rowPageBreak() {
      this.addRow(0, (x, y, w) -> { });
   }

   private void rowText(String key, String value, Consumer<String> reader) {
      this.addRow(1, (x, y, w) -> {
         this.labels.add(new Label(x, y + 6, Component.translatable(key), 0xE0E0E0));
         EditBox box = new EditBox(this.font, x + LABEL_W, y, Math.max(60, w - LABEL_W), ROW_H, Component.translatable(key));
         box.setValue(value == null ? "" : value);
         box.setMaxLength(512);
         box.setHint(Component.translatable(key));
         box.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
         box.setResponder(reader);
         this.addRenderableWidget(box);
         this.fields.add(box);
         this.readers.add(reader);
      });
   }

   private Component toggleText(String key, boolean on) {
      return Component.translatable(key + ".value", Component.translatable(on ? "config.acechat.on" : "config.acechat.off"));
   }

   /** 照片内容的三段式按钮：头像 / 头肩 / 半身 / 完整贴图 / 全身。 */
   private Component photoModeText() {
      String key = switch (CommsConfig.portraitPhotoMode.get()) {
         case 0 -> "config.acechat.photo.head";
         case 2 -> "config.acechat.photo.bust";
         case 3 -> "config.acechat.photo.full";
         case 4 -> "config.acechat.photo.fullbody";
         default -> "config.acechat.photo.shoulders";
      };
      return Component.translatable("config.acechat.portrait.photo_mode")
         .copy().append(Component.literal("：" + Component.translatable(key).getString()));
   }

   /**
    * 半身像这一页直接标出「当前用的是哪张贴图」：
    * 如果是默认 Steve/Alex（二层是空的），就会明确写出来——这正是「看不到二层皮肤」最常见的原因。
    */
   private String skinStatusText() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return "贴图：尚未进入世界";
      }

      PortraitSource current = CommsState.portrait();
      UUID id = current != null ? current.sender() : minecraft.player.getUUID();
      String whose = current != null ? current.name() : minecraft.player.getGameProfile().getName();
      PortraitSource resolved = PortraitSource.resolve(minecraft, id, whose);
      ResourceLocation skin = resolved.skinTexture();
      String location = skin == null ? "无" : skin.toString();
      boolean fallback = PortraitSource.isDefaultSkin(skin);
      boolean loaded = skin != null && minecraft.getTextureManager().getTexture(skin) != null;
      return "贴图：" + location + (fallback ? "（默认皮肤·二层为空）" : "（真实皮肤）")
         + " · " + (resolved.slim() ? "slim" : "classic")
         + " · " + (loaded ? "已加载" : "未加载")
         + " · 二层=" + (CommsConfig.portraitOuterLayer.get() ? "开" : "关");
   }

   private Component colorText(String key, int argb) {
      return Component.translatable(key).copy().append(Component.literal("：" + String.format("#%08X", argb)));
   }

   private Component listText(int index, String name, int argb) {
      String label = index + 1 + ". " + (name == null || name.isBlank() ? "?" : name);
      return Component.literal(label + "   " + String.format("#%08X", argb));
   }

   private Component elementText(int index, CommsConfig.TextElement element) {
      return Component.literal(index + 1 + ". " + element.content);
   }

   private Component horizontalText(CommsConfig.TextElement element) {
      return Component.translatable("config.acechat.elements.horizontal")
         .copy().append(Component.literal("：" + Component.translatable("config.acechat.align." + element.horizontal.name().toLowerCase(Locale.ROOT)).getString()));
   }

   private Component verticalText(CommsConfig.TextElement element) {
      return Component.translatable("config.acechat.elements.vertical")
         .copy().append(Component.literal("：" + Component.translatable("config.acechat.align." + element.vertical.name().toLowerCase(Locale.ROOT)).getString()));
   }

   private Component shadowText(CommsConfig.TextElement element) {
      return Component.translatable("config.acechat.elements.shadow")
         .copy().append(Component.literal("：" + Component.translatable(element.shadow ? "config.acechat.on" : "config.acechat.off").getString()));
   }

   private int clampIndex(int index, int size) {
      return Math.max(0, Math.min(index, size - 1));
   }

   private void notify(String key) {
      this.status = Component.translatable(key).getString();
      this.statusTicks = 120;
   }

   private void saveFields() {
      for (int i = 0; i < this.fields.size(); i++) {
         this.readers.get(i).accept(this.fields.get(i).getValue());
      }

      CommsConfig.save();
   }

   private void saveAndClose() {
      this.saveFields();
      this.onClose();
   }

   // ------------------------------------------------------------------ 渲染

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      for (int[] line : this.groupLines) {
         graphics.fill(line[0], line[1], line[2], line[1] + 1, 0x40FFFFFF);
      }

      try {
         for (Runnable updater : this.live) {
            updater.run();
         }
      } catch (RuntimeException | LinkageError error) {
         if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.error("[acechat] 刷新列表文字失败，已跳过", error);
         }
      }

      this.renderBackground(graphics, 0, 0, 0.0F);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
      if (this.pageCount > 1) {
         String pageText = (this.page + 1) + " / " + this.pageCount;
         // 页码提示放在滑块【上方】，左端与滑块对齐（随滑块位置同步偏移）
         int pageTextX = this.pageSlider != null ? this.pageSlider.getX() : MARGIN;
         int pageTextY = this.pageSlider != null ? this.pageSlider.getY() - 11 : this.height - 34;
         graphics.drawString(this.font, pageText, pageTextX, pageTextY, 0xC8C8C8, false);
      }

      if (this.tab == 3) {
         try {
            this.renderPreview(graphics, mouseX, mouseY);
         } catch (RuntimeException | LinkageError error) {
            if (!loggedFailure) {
               loggedFailure = true;
               LOGGER.error("[acechat] 绘制预览区失败，已跳过（其它分区不受影响）", error);
            }
         }
      }

      super.render(graphics, mouseX, mouseY, partialTick);
      for (Label label : this.labels) {
         graphics.drawString(this.font, label.text(), label.x(), label.y(), label.color(), false);
      }

      if (this.statusTicks > 0) {
         graphics.drawString(this.font, this.status, MARGIN, this.height - 44, 0x90FF90, false);
      }
   }

   private void renderPreview(GuiGraphics graphics, int mouseX, int mouseY) {
      graphics.fill(this.previewX - 1, this.previewY - 1, this.previewX + this.previewW + 1, this.previewY + this.previewH + 1, 0xFF000000);
      graphics.fill(this.previewX, this.previewY, this.previewX + this.previewW, this.previewY + this.previewH, 0xFF26455E);
      int lineCount = Math.max(1, CommsHud.previewLineCount(this.font, this.previewW));
      this.previewRects = CommsHud.previewRects(this.previewW, this.previewH, lineCount);
      CommsHud.renderPreview(graphics, this.previewX, this.previewY, this.previewW, this.previewH);
      int[] rects = this.previewRects;
      this.outline(graphics, rects[0], rects[1], rects[2], rects[3], mouseX, mouseY, this.dragMode == 1 || this.dragMode == 2);
      this.outline(graphics, rects[4], rects[5], rects[6], rects[7], mouseX, mouseY, this.dragMode == 3 || this.dragMode == 4);
      int hx = this.previewX + rects[4] + rects[6] - 6;
      int hy = this.previewY + rects[5] + rects[7] - 6;
      graphics.fill(hx, hy, hx + 6, hy + 6, 0xFFFFFF00);

      // 文字元素：画出各自的矩形与右下角缩放把手，方便直接拖
      int[] elementRects = CommsHud.lastElementRects();
      for (int i = 0; i + 3 < elementRects.length; i += 4) {
         int ex = elementRects[i];
         int ey = elementRects[i + 1];
         int ew = elementRects[i + 2];
         int eh = elementRects[i + 3];
         boolean active = i / 4 == this.dragElementIndex;
         boolean hover = mouseX >= ex && mouseX < ex + ew && mouseY >= ey && mouseY < ey + eh;
         int color = active ? 0xFFFF00 : (hover ? 0xFFFFAA : 0x9AA0A6);
         this.frame(graphics, ex, ey, ex + ew, ey + eh, color);
         graphics.fill(ex + ew - 5, ey + eh - 5, ex + ew, ey + eh, 0xFFFFFF00);
      }

      graphics.drawString(this.font, Component.translatable("config.acechat.preview.drag_hint"), this.previewX + 4, this.previewY + this.previewH - 22, 0xE6E6E6, false);
   }

   private void outline(GuiGraphics graphics, int x, int y, int w, int h, int mouseX, int mouseY, boolean active) {
      int left = this.previewX + x;
      int top = this.previewY + y;
      boolean hover = mouseX >= left && mouseX < left + w && mouseY >= top && mouseY < top + h;
      int color = active ? 0xFFFF00 : (hover ? 0xFFFFAA : 0x808080);
      this.frame(graphics, left, top, left + w, top + h, color);
   }

   private void frame(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
      graphics.fill(x0, y0, x1, y0 + 1, color);
      graphics.fill(x0, y1 - 1, x1, y1, color);
      graphics.fill(x0, y0, x0 + 1, y1, color);
      graphics.fill(x1 - 1, y0, x1, y1, color);
   }

   // ------------------------------------------------------------------ 拖动

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int dragTarget = CommsConfig.previewDragTarget.get();
      if (this.tab == 3 && button == 0 && dragTarget != 0 && this.previewRects != null
         && mouseX >= this.previewX && mouseX < this.previewX + this.previewW
         && mouseY >= this.previewY && mouseY < this.previewY + this.previewH) {
         // 先看有没有点在某个文字元素上（拖元素改位置，拖右下角改字号）
         // 只在允许「拖文字」时才做这轮判定 —— 否则整卡范围内随手一按就会抓到文字元素
         int[] elementRects = (dragTarget == 2 || dragTarget == 3) ? CommsHud.lastElementRects() : new int[0];
         for (int i = 0; i + 3 < elementRects.length; i += 4) {
            int ex = elementRects[i];
            int ey = elementRects[i + 1];
            int ew = elementRects[i + 2];
            int eh = elementRects[i + 3];
            if (mouseX >= ex && mouseX < ex + ew && mouseY >= ey && mouseY < ey + eh) {
               int index = i / 4;
               if (index < CommsConfig.textElements.size()) {
                  this.dragElementIndex = index;
                  this.dragElementScale = mouseX >= ex + ew - 12 && mouseY >= ey + eh - 12;
                  this.dragGrabX = mouseX - ex;
                  this.dragGrabY = mouseY - ey;
                  this.dragBaseInt1 = CommsConfig.textElements.get(index).size;
                  this.dragStartX = mouseX;
                  this.dragStartY = mouseY;
                  this.selectedElement = index;
                  return true;
               }
            }
         }

         if (dragTarget != 1 && dragTarget != 3) {
            return true;   // 只允许拖文字时，按到空白处不启动卡片拖动
         }

         int[] rects = this.previewRects;
         int portraitLeft = this.previewX + rects[4];
         int portraitTop = this.previewY + rects[5];
         int chatLeft = this.previewX + rects[0];
         int chatTop = this.previewY + rects[1];
         if (mouseX >= portraitLeft && mouseX < portraitLeft + rects[6] && mouseY >= portraitTop && mouseY < portraitTop + rects[7]) {
            boolean corner = mouseX >= portraitLeft + rects[6] - 10 && mouseY >= portraitTop + rects[7] - 10;
            this.dragMode = corner ? 4 : 3;
         } else if (mouseX >= chatLeft && mouseX < chatLeft + rects[2] && mouseY >= chatTop && mouseY < chatTop + rects[3]) {
            boolean rightEdge = mouseX >= chatLeft + rects[2] - 8;
            this.dragMode = rightEdge ? 2 : 1;
         } else {
            this.dragMode = 0;
         }

         if (this.dragMode != 0) {
            this.dragStartX = mouseX;
            this.dragStartY = mouseY;
            this.dragBaseInt1 = CommsConfig.centerXOffset.get();
            this.dragBaseInt2 = CommsConfig.topYOffset.get();
            if (this.dragMode == 2) {
               this.dragBaseInt1 = CommsConfig.boxWidth.get();
            } else if (this.dragMode == 4) {
               this.dragBaseInt1 = CommsConfig.portraitWidth.get();
               this.dragBaseInt2 = CommsConfig.portraitHeight.get();
            }

            return true;
         }
      }

      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      // 文字元素：拖动改位置（自动切成左/上对齐，坐标直接跟鼠标），拖右下角改字号
      if (this.dragElementIndex >= 0 && this.dragElementIndex < CommsConfig.textElements.size()) {
         CommsConfig.TextElement element = CommsConfig.textElements.get(this.dragElementIndex);
         if (this.dragElementScale) {
            int delta = (int) Math.round((mouseY - this.dragStartY) / 3.0);
            element.size = Math.max(6, Math.min(24, this.dragBaseInt1 + delta));
         } else {
            int[] area = CommsHud.lastInnerArea();
            element.horizontal = CommsConfig.Horizontal.LEFT;
            element.vertical = CommsConfig.Vertical.TOP;
            element.x = (int) Math.round(mouseX - this.dragGrabX - area[0]);
            element.y = (int) Math.round(mouseY - this.dragGrabY - area[1]);
         }

         this.refreshSliders();
         return true;
      }

      if (this.dragMode == 0) {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }

      int dx = (int) Math.round(mouseX - this.dragStartX);
      int dy = (int) Math.round(mouseY - this.dragStartY);
      switch (this.dragMode) {
         case 1 -> {
            CommsConfig.centerXOffset.set(this.dragBaseInt1 + dx);
            CommsConfig.topYOffset.set(Math.max(0, this.dragBaseInt2 + dy));
         }
         case 2 -> CommsConfig.boxWidth.set(this.dragBaseInt1 + dx * 2);
         case 3 -> {
            int[] current = CommsHud.portraitRect(this.previewW, this.previewH);
            CommsConfig.portraitXOffset.set(0);
            CommsConfig.portraitYOffset.set(0);
            double denomX = Math.max(1, this.previewW - current[2]);
            double denomY = Math.max(1, this.previewH - current[3]);
            CommsConfig.portraitXRatio.set((mouseX - this.previewX) / denomX);
            CommsConfig.portraitYRatio.set((mouseY - this.previewY) / denomY);
         }
         case 4 -> {
            CommsConfig.portraitWidth.set(this.dragBaseInt1 + dx);
            CommsConfig.portraitHeight.set(this.dragBaseInt2 + dy);
         }
         default -> {
         }
      }

      this.refreshSliders();
      return true;
   }

   @Override
   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.dragElementIndex >= 0) {
         this.dragElementIndex = -1;
         this.dragElementScale = false;
         CommsConfig.save();
         this.init();
         return true;
      }

      if (this.dragMode != 0) {
         this.dragMode = 0;
         CommsConfig.save();
         this.init();
         return true;
      }

      return super.mouseReleased(mouseX, mouseY, button);
   }

   private void refreshSliders() {
      for (var widget : this.children()) {
         if (widget instanceof CommsSlider slider) {
            slider.refresh();
         }
      }
   }

   @Override
   public void tick() {
      super.tick();
      if (this.statusTicks > 0) {
         this.statusTicks--;
      }
   }

   @Override
   public void onClose() {
      this.saveFields();
      Minecraft.getInstance().setScreen(this.parent);
   }

   static List<String> splitList(String value) {
      if (value == null || value.isBlank()) {
         return List.of();
      }

      return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
   }
}
