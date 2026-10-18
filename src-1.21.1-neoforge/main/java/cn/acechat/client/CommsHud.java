package cn.acechat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ACE Chat 静态 HUD：顶部玩家聊天 + 右上角半身像。
 * r9 调整：
 *  1) 只要开启显示，半身像就一直可见（没人发言时显示自己的半身像），不再“什么都不显示”；
 *  2) 背景／面板的透明度只由专门的“不透明度”设置决定，不再和颜色里的 alpha 二次相乘（原来会变成纯黑）；
 *  3) 亮度、染色只作用于半身像贴图，不再把文字一起洗白；
 *  4) 文字元素支持独立字号，位置按“屏幕比例 + 像素微调”计算，方便在预览里拖动。
 */
public final class CommsHud {
   private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
   private static long lastTimingLog;
   /** 文字诊断/报错限流用。 */
   private static long textDiagAt;
   /** 立绘贴图查找的帧内记忆：输入没变就不重复查（参数变化自动失效）。 */
   private static String cachedArtKey;
   private static ResourceLocation cachedArt;
   private static final int TEXT_MAIN = 0xFFF3FCFF;
   private static final int LINE_HEIGHT = 10;
   private static boolean loggedFailure;
   // 聊天折行缓存（内容版本 / 折行宽度 / 结果）
   private static int cachedVersion = -1;
   private static int cachedWrap = -1;
   private static List<FormattedCharSequence> cachedLines = List.of();
   // 最近一次绘制的文字元素矩形 / 卡片内区（配置界面预览拖拽用）
   private static int[] elementRects = new int[0];
   private static int[] lastInnerArea = new int[4];

   private CommsHud() {
   }

   public static void render(GuiGraphics graphics) {
      long timingStart = CommsConfig.debugTiming.get() ? System.nanoTime() : 0L;
      // 按当前显示的玩家自动切换角色卡（每帧只在目标卡变化时才真正应用）
      CommsState.Entry shownEntry = CommsState.displayedEntry();
      if (shownEntry != null) {
         CommsConfig.autoApplyCardFor(shownEntry.name());
      }
      // 渲染路径整体兜底：万一出问题只记一条日志、这一帧不画，绝不让 HUD 把游戏搞崩。
      try {
         renderInternal(graphics);
      } catch (RuntimeException | LinkageError error) {
         if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.error("[acechat] 绘制通讯 HUD 时出错，已跳过本帧绘制（后续同样问题不再刷屏）", error);
         }
      }
      CommsConfig.restoreEditingCard();   // 画完还原「正在编辑的卡」

      if (CommsConfig.debugTiming.get()) {
         long spent = System.nanoTime() - timingStart;
         if (spent > 3_000_000L) {
            long now = System.currentTimeMillis();
            if (now - lastTimingLog > 1000L) {
               lastTimingLog = now;
               LOGGER.info("[acechat] HUD 本帧耗时 {} ms（立绘烘焙累计 {} 次，滤镜生成累计 {} 次）",
                  String.format("%.1f", spent / 1_000_000.0), PortraitFiles.bakeCount, PortraitFilters.bakeCount);
            }
         }
      }
   }

   private static void renderInternal(GuiGraphics graphics) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!CommsConfig.hudEnabled.get() || !CommsState.isSessionEnabled() || minecraft.level == null || minecraft.player == null) {
         return;
      }

      // 同一时刻只播出一条：只取「当前播出」的那条，其它排队的等自己上场（这里不再遍历多条）
      CommsState.Entry shown = CommsState.displayed();
      PortraitSource source = portraitOrSelf(minecraft);
      // 「形象框常驻」优先于「跟随聊天栏隐藏」：开了常驻就一直显示（显示自己的形象）
      boolean showPortrait = CommsConfig.portraitEnabled.get() && source != null
         && (CommsConfig.portraitAlwaysVisible.get() || !CommsConfig.portraitFollowChat.get() || shown != null);
      if (shown == null && !showPortrait) {
         return;
      }

      int screenWidth = graphics.guiWidth();
      int screenHeight = graphics.guiHeight();
      try (ImmediateGui draw = new ImmediateGui(graphics)) {
         if (showPortrait) {
            int[] rect = portraitRect(screenWidth, screenHeight);
            drawPortrait(draw, graphics, minecraft.font, source, rect[0], rect[1], rect[2], rect[3]);
         }

         if (shown != null) {
            // 先定有效宽度（给形象框让位），再按它折行，最后定位 → 放不下就自动多行
            int boxWidth = chatEffectiveWidth(screenWidth);
            List<FormattedCharSequence> lines = chatLines(minecraft.font, shown, boxWidth);
            int[] rect = chatRect(screenWidth, screenHeight, lines.size(), boxWidth);
            drawChat(draw, graphics, minecraft.font, lines, rect[0], rect[1], rect[2], rect[3]);
         }
      }
   }

   /** 配置界面里的实时预览：把 area 当成一整块“屏幕”来画。 */
   public static void renderPreview(GuiGraphics graphics, int areaX, int areaY, int areaWidth, int areaHeight) {
      try {
         renderPreviewInternal(graphics, areaX, areaY, areaWidth, areaHeight);
      } catch (RuntimeException | LinkageError error) {
         if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.error("[acechat] 绘制配置界面预览时出错，已跳过预览（配置界面本身仍可用）", error);
         }
      }
   }

   private static void renderPreviewInternal(GuiGraphics graphics, int areaX, int areaY, int areaWidth, int areaHeight) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return;
      }

      PortraitSource source = portraitOrSelf(minecraft);
      int boxWidth = chatEffectiveWidth(areaWidth);
      List<FormattedCharSequence> lines = previewLines(minecraft.font, areaWidth);
      try (ImmediateGui draw = new ImmediateGui(graphics)) {
         if (CommsConfig.portraitEnabled.get() && source != null) {
            int[] rect = portraitRect(areaWidth, areaHeight);
            drawPortrait(draw, graphics, minecraft.font, source, areaX + rect[0], areaY + rect[1], rect[2], rect[3]);
         }

         if (CommsConfig.hudEnabled.get()) {
            int[] rect = chatRect(areaWidth, areaHeight, lines.size(), boxWidth);
            drawChat(draw, graphics, minecraft.font, lines, areaX + rect[0], areaY + rect[1], rect[2], rect[3]);
         }
      }
   }

   /** 预览区里各部件的位置：{聊天框 x, y, w, h, 半身像 x, y, w, h}（相对预览区左上角）。 */
   public static int[] previewRects(int areaWidth, int areaHeight, int chatLines) {
      int[] chat = chatRect(areaWidth, areaHeight, chatLines);
      int[] portrait = portraitRect(areaWidth, areaHeight);
      return new int[]{chat[0], chat[1], chat[2], chat[3], portrait[0], portrait[1], portrait[2], portrait[3]};
   }

   public static int previewLineCount(Font font, int areaWidth) {
      return previewLines(font, areaWidth).size();
   }

   /**
    * 预览里也【只画一条】示例消息，排版函数与上限跟真实运行时完全一致，
    * 这样预览出来的框高就是实际高度（运行时同样是「同一时刻只显示一条」）。
    */
   private static List<FormattedCharSequence> previewLines(Font font, int areaWidth) {
      int wrap = Math.max(1, chatEffectiveWidth(areaWidth) - CommsConfig.chatPaddingX.get() * 2);
      Minecraft minecraft = Minecraft.getInstance();
      String name = minecraft.player == null ? "PilotA" : minecraft.player.getGameProfile().getName();
      String body = Component.translatable("text.comms.preview_body").getString();
      return buildLines(font, nameFor(font, name), CommsConfig.nameStyleFor(null, name), body, wrap);
   }

   /**
    * 只折行「当前播出」的那一条（聊天区是单条轮播）。
    *
    * <p>折行规则见 {@link ChatLayout}：第一行 = 名字 +「&lt;」+ 正文开头，最后一行 = 正文结尾 +「&gt;」，
    * 中间按字符填满换行；单行宽度用「有效宽度」（已扣掉与形象框的间距和两侧边距、以及左右内边距）。
    */
   private static List<FormattedCharSequence> chatLines(Font font, CommsState.Entry entry, int boxWidth) {
      int wrap = Math.max(1, boxWidth - CommsConfig.chatPaddingX.get() * 2);
      int version = CommsState.version();
      // 换行结果按「内容版本 + 折行宽度」缓存：内容不变时每帧直接复用，不再重复折行
      if (version == cachedVersion && wrap == cachedWrap) {
         return cachedLines;
      }

      String display = nameFor(font, CommsConfig.displayName(entry.name()));
      Style nameStyle = CommsConfig.nameStyleFor(entry.sender() == null ? null : entry.sender().toString(), entry.name());
      cachedLines = buildLines(font, display, nameStyle, entry.text(), wrap);
      cachedVersion = version;
      cachedWrap = wrap;
      return cachedLines;
   }

   /**
    * 「名字最大宽度」设置：超过就截断成「…」。
    * 之后 {@link ChatLayout} 还会再按「这一行实际放不放得下」收一次（更窄的框也保证正文能同行）。
    */
   private static String nameFor(Font font, String display) {
      String name = display == null ? "" : display;
      int maxName = Math.max(20, CommsConfig.chatNameMaxWidth.get());
      return font.width(name) <= maxName ? name : ChatLayout.shorten(name, font::width, maxName);
   }

   /**
    * 按 {@link ChatLayout} 排好版，再把每一行拼成带样式的组件：
    * 名字用名单色、「&lt;&gt;」用符号色、正文白色；只有第一行带名字与「&lt;」，只有最后一行带「&gt;」。
    */
   private static List<FormattedCharSequence> buildLines(Font font, String name, Style nameStyle, String body, int wrap) {
      String pad = CommsConfig.chatSymbolPad();
      String open = "<<<" + pad;
      String close = pad + ">>>";
      ChatLayout.Result result = ChatLayout.layout(name, body, open, close,
         font::width, wrap, Math.max(1, CommsConfig.maxLines.get()));
      List<FormattedCharSequence> lines = new ArrayList<>(result.lines().size() + 1);

       // 第一行：只有玩家名字（整行居中由绘制处负责）；
       // 名字也受「聊天框宽度」约束：太长的名字按框宽截断成「…」，免得顶出框外。
       String shownName = font.width(result.name()) <= wrap
          ? result.name()
          : ChatLayout.shorten(result.name(), font::width, wrap);
       lines.add(Component.literal(shownName)
          .withStyle(nameStyle == null ? Style.EMPTY : nameStyle)
          .getVisualOrderText());
      for (ChatLayout.Line line : result.lines()) {
         MutableComponent component = Component.empty();
         if (line.first()) {
            // 符号与玩家名字同色（用户 2026-10-14 要求）
             component.append(Component.literal(open)
                .withStyle(nameStyle == null ? Style.EMPTY : nameStyle));
         }

         component.append(Component.literal(line.body()).withStyle(CommsConfig.chatBodyStyle()));
         if (line.ellipsis()) {
            component.append(Component.literal("…").withStyle(CommsConfig.chatBodyStyle()));
         }

         if (line.close()) {
            component.append(Component.literal(close)
                .withStyle(nameStyle == null ? Style.EMPTY : nameStyle));
         }

         lines.add(component.getVisualOrderText());
      }

      return lines;
   }

   private static PortraitSource portraitOrSelf(Minecraft minecraft) {
      PortraitSource source = CommsState.portrait();
      // 只有开了「形象框常驻」才在自己没发言时顶上自己的形象
      if (source == null && CommsConfig.portraitAlwaysVisible.get() && minecraft.player != null) {
         source = PortraitSource.resolve(minecraft, minecraft.player.getUUID(), minecraft.player.getGameProfile().getName());
      }

      return source;
   }

   /** 形象框左边缘（只与区域宽度有关）：聊天框要靠它来让位。 */
   static int portraitLeftEdge(int areaWidth) {
      float scale = CommsConfig.portraitScale.get().floatValue();
      int width = Math.max(40, Math.round(CommsConfig.portraitWidth.get() * scale));
      float ratioX = CommsConfig.portraitXRatio.get().floatValue();
      int x = Math.round(ratioX * Math.max(0, areaWidth - width)) + CommsConfig.portraitXOffset.get();
      return clamp(x, 0, Math.max(0, areaWidth - width));
   }

   /**
    * 聊天框的<b>有效宽度</b>：
    * 先取配置宽度，再保证「右端与形象框之间留出『与形象框间距』」、且「左右各留出『边距』」。
    * 缩窄之后文字会自动按这个宽度折行（见 chatLines），所以放不下的内容会分成多行显示。
    */
   static int chatEffectiveWidth(int areaWidth) {
      int preferred = Math.max(60, CommsConfig.boxWidth.get());
      int margin = Math.max(0, CommsConfig.chatLeftMargin.get());
      int available = areaWidth - margin * 2;
      if (CommsConfig.portraitEnabled.get()) {
         int rightLimit = portraitLeftEdge(areaWidth) - Math.max(0, CommsConfig.chatRightGap.get());
         available = Math.min(available, rightLimit - margin);
      }

      return Math.max(60, Math.min(preferred, Math.max(60, available)));
   }

   static int[] chatRect(int screenWidth, int screenHeight, int lineCount) {
      return chatRect(screenWidth, screenHeight, lineCount, chatEffectiveWidth(screenWidth));
   }

   /** 按给定宽度算聊天框位置：右端不许越过「形象框 - 间距」，左右也不越过边距。 */
   static int[] chatRect(int screenWidth, int screenHeight, int lineCount, int width) {
      int height = lineCount * LINE_HEIGHT + CommsConfig.chatPaddingY.get() * 2;
      int margin = Math.max(0, CommsConfig.chatLeftMargin.get());
      int x = screenWidth / 2 + CommsConfig.centerXOffset.get() - width / 2;
      if (CommsConfig.portraitEnabled.get()) {
         int rightLimit = portraitLeftEdge(screenWidth) - Math.max(0, CommsConfig.chatRightGap.get());
         if (x + width > rightLimit) {
            x = rightLimit - width;
         }
      }

      x = clamp(x, margin, Math.max(margin, screenWidth - width - margin));
      int y = 6 + CommsConfig.topYOffset.get();
      return new int[]{x, clamp(y, 0, Math.max(0, screenHeight - height)), width, height};
   }

   static int[] portraitRect(int screenWidth, int screenHeight) {
      float scale = CommsConfig.portraitScale.get().floatValue();
      int width = Math.max(40, Math.round(CommsConfig.portraitWidth.get() * scale));
      int height = Math.max(40, Math.round(CommsConfig.portraitHeight.get() * scale));
      float ratioX = CommsConfig.portraitXRatio.get().floatValue();
      float ratioY = CommsConfig.portraitYRatio.get().floatValue();
      int x = Math.round(ratioX * Math.max(0, screenWidth - width)) + CommsConfig.portraitXOffset.get();
      int y = Math.round(ratioY * Math.max(0, screenHeight - height)) + CommsConfig.portraitYOffset.get();
      return new int[]{clamp(x, 0, Math.max(0, screenWidth - width)), clamp(y, 0, Math.max(0, screenHeight - height)), width, height};
   }

   private static void drawChat(ImmediateGui draw, GuiGraphics graphics, Font font, List<FormattedCharSequence> lines, int left, int top, int width, int height) {
      int border = CommsConfig.chatBorderEnabled.get() ? CommsConfig.chatBorderThickness.get() : 0;
      // 全部直角（按需求去掉所有圆角）
      if (border > 0) {
         draw.rect(left, top, left + width, top + height, CommsConfig.chatBorderColor.get());
      }

      draw.rect(
         left + border, top + border, left + width - border, top + height - border,
         withAlpha(CommsConfig.chatBackgroundColor.get(), CommsConfig.backgroundAlpha.get().floatValue())
      );
      // 多行时【左对齐成一段】：以「最宽的那一行」居中为基准，其余行都从同一条左边缘开始。
      // （旧代码是每行各自居中 —— 短行被推进去，看起来就是「名字居中、<> 和正文错位」。）
      // 每一行各自居中：名字一行居中、正文各行居中（<<< / >>> 紧贴正文两端）。
      int y = top + CommsConfig.chatPaddingY.get();
      for (FormattedCharSequence line : lines) {
         int lineWidth = font.width(line);
         int lineLeft = left + Math.max(0, (width - lineWidth) / 2);
         graphics.drawString(font, line, lineLeft, y, TEXT_MAIN, true);
         y += LINE_HEIGHT;
      }
   }

   /**
    * 证件卡式半身像面板（参照用户给的 ACE 档案卡）：
    *   外框（直角）→ 上半「照片框」（背景板 + 人物）→ 一条细分隔线 → 下半文字区（大字名字 + 两行小字）。
    * 所有矩形都是直角；文字元素仍相对整块内区定位，垂直对齐 BOTTOM 的几行自然落在卡片下半部分。
    */
   /**
    * 死亡覆盖层：盖住背景框（整块内部区域）。
    *
    * <p>优先用 {@code config/acechat/deaths/<玩家名>.png}（只读 1:1 方图），
    * 没有文件时用内置预设 {@code builtin:no_signal}：黑色方格 + 居中白色「NO SIGN」。
    * 缩放与偏移由「覆盖图缩放 / X 偏移 / Y 偏移」控制；叠加滤镜在调用方之后统一作用于整卡。
    */
   private static void drawDeathOverlay(ImmediateGui draw, GuiGraphics graphics, PortraitSource source,
                                        float x, float y, float width, float height) {
      String name = CommsConfig.displayName(source.name());
      ResourceLocation texture = DeathPortraits.textureFor(Minecraft.getInstance(), name);
      float scale = Math.max(0.2F, Math.min(4.0F, CommsConfig.deathImageScale.get() / 100.0F));
      float offsetX = CommsConfig.deathImageOffsetX.get();
      float offsetY = CommsConfig.deathImageOffsetY.get();

      if (texture != null) {
         int[] size = DeathPortraits.sizeOf(DeathPortraits.fileNameFor(name));
         float side = Math.min(width, height) * scale;
         float leftAt = x + (width - side) * 0.5F + offsetX;
         float topAt = y + (height - side) * 0.5F + offsetY;
         PoseStack pose = graphics.pose();
         pose.pushPose();
         pose.translate(leftAt, topAt, 0.0F);
         pose.scale(side / Math.max(1.0F, size[0]), side / Math.max(1.0F, size[1]), 1.0F);
         graphics.blit(texture, 0, 0, 0, 0, Math.max(1, size[0]), Math.max(1, size[1]),
            Math.max(1, size[0]), Math.max(1, size[1]));
         pose.popPose();
         return;
      }

      if (!DeathPortraits.PRESET_NO_SIGN.equals(CommsConfig.deathPreset.get())) {
         return;   // 既没有文件也没开预设 → 不覆盖
      }

      // 内置预设：黑色方格铺满背景框（缩放/偏移生效）+ 居中白色 NO SIGN + 轻微扫描线
      float side = Math.min(width, height) * scale;
      float leftAt = x + (width - side) * 0.5F + offsetX;
      float topAt = y + (height - side) * 0.5F + offsetY;
      float startX = Math.max(x, leftAt);
      float startY = Math.max(y, topAt);
      float right = Math.min(x + width, leftAt + side);
      float bottom = Math.min(y + height, topAt + side);
      draw.rect(startX, startY, right, bottom, 0xFF000000);
      for (float line = startY + 3.0F; line < bottom; line += 4.0F) {
         draw.rect(startX, line, right, line + 1.0F, 0x14FFFFFF);
      }

      Font font = Minecraft.getInstance().font;
      String label = "NO SIGN";
      float textWidth = font.width(label);
      draw.flush();
      graphics.drawString(font, label,
         (int) ((startX + right) * 0.5F - textWidth * 0.5F),
         (int) ((startY + bottom) * 0.5F - 4.0F), 0xFFFFFFFF, false);
   }

   /** 把绘制范围限制在「背景板」内：超出的像素不渲染（立绘/皮肤/死亡覆盖共用）。 */
   private static void clipToFrame(GuiGraphics graphics, float x, float y, float width, float height) {
      int x0 = (int) Math.floor(x);
      int y0 = (int) Math.floor(y);
      int x1 = (int) Math.ceil(x + width);
      int y1 = (int) Math.ceil(y + height);
      if (x1 > x0 && y1 > y0) {
         graphics.enableScissor(x0, y0, x1, y1);
      }
   }

   /** 清空 HUD 自己的渲染缓存（「重载渲染」按钮用）。 */
   public static void clearRenderCache() {
      cachedArtKey = null;
      cachedArt = null;
   }

   /** 把「调色」折成的乘法色调套到整张卡片上（底色/边框/照片/滤镜都受益）。 */
   private static void applyCardTint() {
      int tint = CommsConfig.gradeEnabled.get()
         ? PortraitGradeMath.cardTintArgb(CommsConfig.gradeBrightness.get(), CommsConfig.gradeHue.get())
         : 0xFFFFFFFF;
      RenderSystem.setShaderColor(((tint >>> 16) & 0xFF) / 255.0F,
         ((tint >>> 8) & 0xFF) / 255.0F, (tint & 0xFF) / 255.0F, 1.0F);
   }

   /** 恢复原色（文字元素前调用，保证文字不被整卡色调影响）。 */

   /**
    * 把「颜色蒙版 × 整卡调色」乘进文字颜色（白 = 不改色）。
    *
    * <p>以前是用 {@code RenderSystem.setShaderColor} 在画文字时染色，但能不能作用到文字取决于渲染管线，
    * 实测看不到效果 —— 所以改成直接把颜色乘进元素自己的颜色里，确定生效。
    */
   static int applyElementTint(int color, int tint) {
      if (tint == 0xFFFFFFFF || tint == 0) {
         return color;
      }

      int r = (((color >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF)) / 255;
      int g = (((color >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF)) / 255;
      int b = ((color & 0xFF) * (tint & 0xFF)) / 255;
      return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
   }

   /** 当前的「颜色蒙版 × 整卡调色」（白 = 不改色）。 */
   private static int elementTint() {
      int cardTint = CommsConfig.gradeEnabled.get()
         ? PortraitGradeMath.cardTintArgb(CommsConfig.gradeBrightness.get(), CommsConfig.gradeHue.get())
         : 0xFFFFFFFF;
      int mask = CommsConfig.filterEnabled.get() ? CommsConfig.filterTint.get() : 0xFFFFFFFF;
      return applyElementTint(mask, cardTint);
   }

   private static void clearCardTint() {
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
   }

   /** 立绘：保持比例居中铺进照片框，再按「立绘缩放 / 偏移」调整。 */
   private static void drawPortraitImage(GuiGraphics graphics, ResourceLocation texture, String name,
                                         float x, float y, float width, float height) {
      int[] size = PortraitFiles.sizeOf(name);
      float textureWidth = Math.max(1, size[0]);
      float textureHeight = Math.max(1, size[1]);
      float scale = Math.min(width / textureWidth, height / textureHeight)
         * Math.max(0.1F, Math.min(4.0F, CommsConfig.portraitImageScale.get() / 100.0F));
      float drawWidth = textureWidth * scale;
      float drawHeight = textureHeight * scale;
      float left = x + (width - drawWidth) * 0.5F + CommsConfig.portraitImageOffsetX.get();
      float top = y + (height - drawHeight) * 0.5F + CommsConfig.portraitImageOffsetY.get();

      PoseStack pose = graphics.pose();
      pose.pushPose();
      pose.translate(left, top, 0.0F);
      pose.scale(drawWidth / textureWidth, drawHeight / textureHeight, 1.0F);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      try {
         graphics.blit(texture, 0, 0, 0, 0, (int) textureWidth, (int) textureHeight, (int) textureWidth, (int) textureHeight);
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         pose.popPose();
      }
   }
   private static void drawPortrait(ImmediateGui draw, GuiGraphics graphics, Font font, PortraitSource source, int left, int top, int width, int height) {
      applyCardTint();
      int border = CommsConfig.portraitBorderThickness.get();
      float panelAlpha = CommsConfig.portraitPanelAlpha.get() / 100.0F;
      if (border > 0) {
         draw.rect(left, top, left + width, top + height, CommsConfig.portraitBorderColor.get());
      }

      draw.rect(
         left + border, top + border, left + width - border, top + height - border,
         withAlpha(CommsConfig.portraitPanelColor.get(), panelAlpha)
      );

      int padding = Math.max(0, CommsConfig.portraitBackdropPadding.get());
      int areaX = left + border + padding;
      int areaY = top + border + padding;
      int areaWidth = Math.max(8, width - (border + padding) * 2);
      int areaHeight = Math.max(8, height - (border + padding) * 2);
      lastInnerArea = new int[]{areaX, areaY, areaWidth, areaHeight};

      // 照片框（上半，直角）
      int percent = Math.max(30, Math.min(90, CommsConfig.portraitPhotoHeight.get()));
      int photoHeight = Math.max(8, Math.round(areaHeight * (percent / 100.0F)));
      if (CommsConfig.portraitBackdropEnabled.get()) {
         draw.rect(
            areaX, areaY, areaX + areaWidth, areaY + photoHeight,
            withAlpha(CommsConfig.portraitBackdropColor.get(), CommsConfig.portraitBackdropAlpha.get() / 100.0F)
         );
      }

      // 人物：只画在照片框内并居中（绘制走原版 blit，所以先把纯色批次提交掉）
      draw.flush();
      float bustScale = Math.max(0.2F, Math.min(1.5F, CommsConfig.portraitBustScale.get() / 100.0F));
      float innerWidth = Math.max(4.0F, areaWidth - 2.0F);
      float innerHeight = Math.max(4.0F, photoHeight - 2.0F);
      float bustWidth = innerWidth * bustScale;
      float bustHeight = innerHeight * bustScale;
      // 死亡覆盖：该玩家在发言期间死亡 → 用 deaths/<玩家名>.png 或内置 NO SIGN 预设盖住背景框
      if (source != null && CommsState.deathOverlayActive(source.sender())) {
         drawDeathOverlay(draw, graphics, source, areaX, areaY, innerWidth, innerHeight);
      draw.flush();   // 文字是另一套批次，先提交再画
         clearCardTint();
         clearCardTint();
      drawElementsSafe(graphics, font, source, areaX, areaY, areaWidth, areaHeight);
         draw.flush();
         applyCardTint();
         applyCardTint();
      PortraitFilters.draw(graphics, Minecraft.getInstance(), left, top, width, height);
         return;
      }

      // 立绘模式：照片框里画 config/acechat/portraits/ 里的 PNG（缩放/偏移可控），拿不到就回落到皮肤
      ResourceLocation artTexture = null;
      if (CommsConfig.portraitUseImage.get()) {
         String artKey = CommsConfig.portraitImageName.get() + "|" + CommsConfig.gradeKey();
         if (artKey.equals(cachedArtKey)) {
            artTexture = cachedArt;
         } else {
            artTexture = PortraitFiles.texture(Minecraft.getInstance(), CommsConfig.portraitImageName.get());
            cachedArtKey = artKey;
            cachedArt = artTexture;
         }
      }
      if (artTexture != null) {
         draw.flush();   // 先提交当前批次，否则裁剪会打断绘制
      clipToFrame(graphics, areaX, areaY, innerWidth, innerHeight);
         try {
            drawPortraitImage(graphics, artTexture, CommsConfig.portraitImageName.get(),
               areaX + 1.0F, areaY + 1.0F, innerWidth, innerHeight);
         } finally {
            graphics.disableScissor();
         }
      draw.flush();   // 文字是另一套批次，先提交再画
         clearCardTint();
         clearCardTint();
      drawElementsSafe(graphics, font, source, areaX, areaY, areaWidth, areaHeight);
         draw.flush();
         applyCardTint();
         applyCardTint();
      PortraitFilters.draw(graphics, Minecraft.getInstance(), left, top, width, height);
         return;
      }

      // 调色（CPU 烘焙）开了就用烘焙好的皮肤副本；还没烘焙好时先画原图
      ResourceLocation graded = PortraitGrade.gradedOrOriginal(Minecraft.getInstance(), source);
      PortraitSource art = graded == null || graded.equals(source.skinTexture())
         ? source
         : new PortraitSource(source.sender(), source.name(), graded, source.capeTexture(), source.slim(),
            source.customTexture(), source.customWidth(), source.customHeight());
      float skinX = CommsConfig.skinOffsetX.get();
      float skinY = CommsConfig.skinOffsetY.get();
      draw.flush();   // 先提交当前批次，否则裁剪会打断绘制
      clipToFrame(graphics, areaX, areaY, innerWidth, innerHeight);
      try {
         SkinBustRenderer.draw(
            graphics, art,
            areaX + 1.0F + (innerWidth - bustWidth) * 0.5F + skinX,
            areaY + 1.0F + (innerHeight - bustHeight) * 0.5F + skinY,
            bustWidth, bustHeight, 1.0F, CommsConfig.portraitPhotoMode.get()
         );
      } finally {
         graphics.disableScissor();
      }



      // 照片框与文字区之间的细分隔线（直角，贯通内区宽度）
      if (CommsConfig.portraitPhotoDivider.get()) {
         int lineY = areaY + photoHeight;
         draw.rect(areaX, lineY, areaX + areaWidth, lineY + 1, CommsConfig.portraitBorderColor.get());
      }

      draw.flush();   // 文字是另一套批次，先提交再画
      clearCardTint();
      drawElementsSafe(graphics, font, source, areaX, areaY, areaWidth, areaHeight);

      // 叠加滤镜：铺满【整个形象框】（面板 + 照片框 + 文字区），所以最后画。
      // 注意：blit 用的是 Tesselator 的共享 builder，画之前必须先把纯色批次提交掉，否则会 "Already building!"
      draw.flush();
      applyCardTint();
      PortraitFilters.draw(graphics, Minecraft.getInstance(), left, top, width, height);
   }

   /** 安全调用文字元素绘制：出错也不会让整段文字消失，并打印一次原因。 */
   private static void drawElementsSafe(GuiGraphics graphics, Font font, PortraitSource source,
         int x, int y, int width, int height) {
      if (!CommsConfig.debugTiming.get()) {
         drawElements(graphics, font, source, x, y, width, height);
         return;
      }

      try {
         drawElements(graphics, font, source, x, y, width, height);
      } catch (RuntimeException | LinkageError error) {
         long now = System.currentTimeMillis();
         if (now - textDiagAt > 1000L) {
            textDiagAt = now;
            LOGGER.error("[acechat] 绘制文字元素失败（玩家=" + source.name() + "，元素 "
               + CommsConfig.textElements.size() + " 个，区域=" + width + "x" + height + "）", error);
         }
      }
   }

   private static void drawElements(GuiGraphics graphics, Font font, PortraitSource source, int x, int y, int width, int height) {
      String name = source.name();
      String uuid = source.sender().toString();
      int count = CommsConfig.textElements.size();
      int[] rects = new int[count * 4];
      int index = 0;
      for (CommsConfig.TextElement element : CommsConfig.textElements) {
         if (!CommsConfig.isVisible(element)) {
            index++;   // 隐藏的元素占位，保证拖拽用的矩形索引与元素列表一致
            continue;
         }

         String resolved = CommsConfig.resolve(element.content, name, uuid);
         if (resolved.isEmpty()) {
            index++;
            continue;
         }

         String text = resolved;
         int max = Math.max(1, element.maxWidth);
         while (font.width(text) > max && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
         }

         if (!text.equals(resolved)) {
            text = text + "…";
         }

         float sizeScale = Math.max(0.5F, Math.min(3.0F, element.size / 9.0F));
         int textWidth = Math.round(font.width(text) * sizeScale);
         int textHeight = Math.round(9 * sizeScale);
         int tx = switch (element.horizontal) {
            case LEFT -> x + element.x;
            case CENTER -> x + (width - textWidth) / 2 + element.x;
            case RIGHT -> x + width - textWidth + element.x;
         };
         int tinted = applyElementTint(element.color, elementTint());
         int ty = switch (element.vertical) {
            case TOP -> y + element.y;
            case CENTER -> y + (height - textHeight) / 2 + element.y;
            case BOTTOM -> y + height - textHeight + element.y;
         };
         // 记录实际绘制出来的矩形（预览里拖拽/缩放元素要用它）
         rects[index * 4] = tx;
         rects[index * 4 + 1] = ty;
         rects[index * 4 + 2] = Math.max(4, textWidth);
         rects[index * 4 + 3] = Math.max(6, textHeight);
         index++;
         if (sizeScale == 1.0F) {
            graphics.drawString(font, text, tx, ty, tinted, element.shadow);
         } else {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(tx, ty, 0.0F);
            pose.scale(sizeScale, sizeScale, 1.0F);
            graphics.drawString(font, text, 0, 0, tinted, element.shadow);
            pose.popPose();
         }
      }

      elementRects = rects;
   }

   /** 最近一次绘制时各文字元素的矩形（x, y, w, h 依次排列），供配置界面预览拖拽用。 */
   static int[] lastElementRects() {
      return elementRects;
   }

   /** 最近一次绘制时半身像卡片的内区（x, y, w, h），供预览坐标换算。 */
   static int[] lastInnerArea() {
      return lastInnerArea;
   }

   /** 只改 alpha 通道：透明度由“不透明度”设置唯一决定，避免和颜色里的 alpha 相乘变成纯黑。 */
   static int withAlpha(int color, float alpha) {
      int a = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
      return (color & 0x00FFFFFF) | (a << 24);
   }

   /** 兼容旧调用：乘法式改 alpha（保留给需要的场景）。 */
   static int multiplyAlpha(int color, float alpha) {
      return (color & 0x00FFFFFF) | (Math.round(((color >>> 24) & 255) * Math.max(0.0F, Math.min(1.0F, alpha))) << 24);
   }

   static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}
