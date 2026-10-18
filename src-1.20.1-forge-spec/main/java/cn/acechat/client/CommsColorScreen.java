package cn.acechat.client;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 取色二级界面：A / R / G / B 四个可拖动滑块 + 十六进制输入 + 实时色块。
 * 改动实时回写（配置界面里的预览会立刻跟着变），点“取消”恢复进入前的颜色。
 */
public final class CommsColorScreen extends Screen {
   private final Screen parent;
   private final String titleKey;
   private final IntConsumer onChange;
   private final int original;
   private int color;
   private EditBox hexBox;
   private boolean syncing;

   public CommsColorScreen(Screen parent, String titleKey, int initial, IntConsumer onChange) {
      super(Component.translatable("screen.acechat.color"));
      this.parent = parent;
      this.titleKey = titleKey;
      this.onChange = onChange;
      this.original = initial;
      this.color = initial;
   }

   @Override
   protected void init() {
      this.clearWidgets();
      int left = Math.max(12, this.width / 2 - 150);
      int y = 46;
      this.addRenderableWidget(new CommsSlider("config.acechat.color.alpha", "config.acechat.color.alpha.tooltip",
         left, y, 300, 20, () -> (this.color >>> 24) & 255, v -> this.setChannel(24, (int) v), 0, 255, true, "", false));
      y += 28;
      this.addRenderableWidget(new CommsSlider("config.acechat.color.red", "config.acechat.color.red.tooltip",
         left, y, 300, 20, () -> (this.color >> 16) & 255, v -> this.setChannel(16, (int) v), 0, 255, true, "", false));
      y += 28;
      this.addRenderableWidget(new CommsSlider("config.acechat.color.green", "config.acechat.color.green.tooltip",
         left, y, 300, 20, () -> (this.color >> 8) & 255, v -> this.setChannel(8, (int) v), 0, 255, true, "", false));
      y += 28;
      this.addRenderableWidget(new CommsSlider("config.acechat.color.blue", "config.acechat.color.blue.tooltip",
         left, y, 300, 20, () -> this.color & 255, v -> this.setChannel(0, (int) v), 0, 255, true, "", false));
      y += 30;
      this.hexBox = new EditBox(this.font, left, y, 180, 20, Component.translatable("config.acechat.color.hex"));
      this.hexBox.setValue(hexOf(this.color));
      this.hexBox.setMaxLength(9);
      this.hexBox.setResponder(this::onHexChanged);
      this.addRenderableWidget(this.hexBox);
      this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.color.ok"), b -> this.onClose())
         .bounds(left + 190, y, 110, 20).build());
      int bottom = this.height - 30;
      this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.color.reset"), b -> this.apply(0xFFFFFFFF))
         .bounds(left, bottom, 90, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.color.cancel"), b -> {
         this.onChange.accept(this.original);
         this.minecraft.setScreen(this.parent);
      }).bounds(left + 100, bottom, 90, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("screen.acechat.back"), b -> this.onClose())
         .bounds(left + 200, bottom, 100, 20).build());
   }

   private void setChannel(int shift, int value) {
      int v = Math.max(0, Math.min(255, value));
      this.color = (this.color & ~(255 << shift)) | (v << shift);
      this.apply(this.color);
   }

   private void apply(int argb) {
      this.color = argb;
      this.onChange.accept(argb);
      this.syncHexBox();
   }

   /**
    * 把当前颜色写回十六进制输入框。
    * 注意：EditBox.setValue() 会【同步】回调 responder，如果不加防重入锁，就会和 onHexChanged 互相调用，
    * 形成无限递归并 StackOverflowError（2026-10-09 22:49 的崩溃就是这个原因）。
    */
   private void syncHexBox() {
      if (this.hexBox == null || this.hexBox.isFocused()) {
         return; // 正在输入时不要覆盖用户的内容
      }

      String text = hexOf(this.color);
      if (text.equalsIgnoreCase(this.hexBox.getValue())) {
         return; // 内容相同就不 setValue，避免多余回调
      }

      this.syncing = true;
      try {
         this.hexBox.setValue(text);
      } finally {
         this.syncing = false;
      }
   }

   private void onHexChanged(String text) {
      if (this.syncing) {
         return; // 这次回调是 syncHexBox 自己触发的，忽略，绝不再往下调 apply
      }

      try {
         String s = text.trim();
         if (s.startsWith("#")) {
            s = s.substring(1);
         }

         if (s.length() != 6 && s.length() != 8) {
            return;
         }

         long parsed = Long.parseLong(s, 16);
         int argb = (int) (s.length() == 6 ? (0xFF000000L | parsed) : parsed);
         this.color = argb;
         this.onChange.accept(argb);
      } catch (RuntimeException ignored) {
         // 输入还不完整，忽略即可
      }
   }

   private static String hexOf(int argb) {
      return String.format("%08X", argb);
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
      graphics.drawCenteredString(this.font, Component.translatable(this.titleKey), this.width / 2, 26, 0xA0A0A0);
      int left = Math.max(12, this.width / 2 - 150);
      // 色块：先画棋盘格底，再叠当前颜色，透明也能看出来
      int swatchX = left + 190;
      int swatchY = 46;
      int swatchW = 110;
      int swatchH = 20;
      for (int i = 0; i < swatchW; i += 8) {
         for (int j = 0; j < swatchH; j += 8) {
            boolean dark = ((i / 8) + (j / 8)) % 2 == 0;
            graphics.fill(swatchX + i, swatchY + j, swatchX + Math.min(swatchW, i + 8), swatchY + Math.min(swatchH, j + 8), dark ? 0xFF404040 : 0xFF808080);
         }
      }

      graphics.fill(swatchX, swatchY, swatchX + swatchW, swatchY + swatchH, this.color);
      graphics.drawString(this.font, Component.translatable("config.acechat.color.current", hexOf(this.color)), swatchX, swatchY + 26, 0xE0E0E0, false);
      super.render(graphics, mouseX, mouseY, partialTick);
   }

   @Override
   public void onClose() {
      this.onChange.accept(this.color);
      this.minecraft.setScreen(this.parent);
   }
}
