package cn.acechat.client;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * 可拖动滑块：拖动滑块本体、点击轨道、左右方向键（配合 Shift 加速）都能改值。
 * HUD 的位置、大小、透明度、亮度等数值项全部用它，替代原来的数值输入框。
 */
public class CommsSlider extends AbstractSliderButton {
   private final String labelKey;
   private final DoubleSupplier getter;
   private final DoubleConsumer setter;
   private final double min;
   private final double max;
   private final boolean integral;
   private final String suffix;
   private final boolean percent;
   /** 拖动中标记：拖动时实时应用效果，但只有松手才写盘（避免每像素一次磁盘 IO）。 */
   private boolean draggingNow;

   public CommsSlider(
      String labelKey,
      String tooltipKey,
      int x,
      int y,
      int width,
      int height,
      DoubleSupplier getter,
      DoubleConsumer setter,
      double min,
      double max,
      boolean integral,
      String suffix,
      boolean percent
   ) {
      super(x, y, width, height, Component.translatable(labelKey), ratio(getter.getAsDouble(), min, max));
      this.labelKey = labelKey;
      this.getter = getter;
      this.setter = setter;
      this.min = min;
      this.max = max;
      this.integral = integral;
      this.suffix = suffix == null ? "" : suffix;
      this.percent = percent;
      this.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
      this.updateMessage();
   }

   private static double ratio(double value, double min, double max) {
      if (max <= min) {
         return 0.0;
      } else {
         return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
      }
   }

   private String text(double value) {
      if (this.percent) {
         return Math.round(value) + "%";
      } else if (this.integral) {
         return Long.toString(Math.round(value)) + this.suffix;
      } else {
         return String.format(Locale.ROOT, "%.2f", value) + this.suffix;
      }
   }

   /** 外部改了配置（例如在预览区直接拖动）之后刷新滑块显示。 */
   public void refresh() {
      this.value = ratio(this.getter.getAsDouble(), this.min, this.max);
      this.updateMessage();
   }

   @Override
   protected void updateMessage() {
      if (this.getter == null) {
         return; // 父类构造期间会调用一次
      }

      this.setMessage(Component.translatable(this.labelKey).append(Component.literal("：" + this.text(this.getter.getAsDouble()))));
   }

   @Override
   public void onClick(double mouseX, double mouseY) {
      this.draggingNow = true;
      super.onClick(mouseX, mouseY);
   }

   @Override
   public void onRelease(double mouseX, double mouseY) {
      super.onRelease(mouseX, mouseY);
      this.draggingNow = false;
      this.applyValue();          // 收尾应用一次
      CommsConfig.save();         // 只有松手才写盘（含角色卡）
   }

   @Override
   protected void applyValue() {
      // 拖动过程中就应用（效果跟手变化）；只有落盘推迟到松手时（见 onRelease）

      if (this.setter == null) {
         return;
      }

      double d = this.min + this.value * (this.max - this.min);
      if (this.integral || this.percent) {
         d = Math.round(d);
      }

      d = Math.max(this.min, Math.min(this.max, d));
      this.setter.accept(d);
      this.updateMessage();
   }
}
