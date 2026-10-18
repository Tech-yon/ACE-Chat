package cn.acechat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 把玩家皮肤贴图拼成人物像（头像 / 头肩 / 半身 / 全身）或直接铺整张原图。
 *
 * 关键点：
 * 1) 贴图绘制统一走原版 {@link GuiGraphics#blit}（原版画玩家头像、物品图标用的就是它），
 *    不再自己搭着色器/批次，避免混合与采样上的意外；
 * 2) 缩放用 PoseStack（translate + scale），所以 blit 仍然用整数画布坐标，HD 皮肤靠 64 基准 UV 自动正确；
 * 3) 第二层皮肤（帽子/外套/袖子/裤子）在底层之后绘制，天然叠在上面。
 */
public final class SkinBustRenderer {
   private SkinBustRenderer() {
   }

   /**
    * @param layout 0=只画头像 1=头肩 2=半身 3=完整贴图（原图，不合成二层） 4=全身（完整人物，含二层）
    */
   public static void draw(GuiGraphics graphics, PortraitSource source, float x, float y, float width, float height, float alpha, int layout) {
      if (source == null || width <= 2.0F || height <= 2.0F) {
         return;
      }

      int mode = Math.max(0, Math.min(4, layout));
      if (source.customTexture() != null) {
         // 自定义立绘：整张按比例居中
         float scale = Math.min(width / Math.max(1, source.customWidth()), height / Math.max(1, source.customHeight()));
         float drawWidth = Math.max(1, source.customWidth()) * scale;
         float drawHeight = Math.max(1, source.customHeight()) * scale;
         float left = x + (width - drawWidth) * 0.5F;
         float top = y + (height - drawHeight) * 0.5F;
         drawScaled(graphics, source.customTexture(),
            Math.max(1, source.customWidth()), Math.max(1, source.customHeight()),
            left, top, scale, 0, 0, Math.max(1, source.customWidth()), Math.max(1, source.customHeight()), alpha);
         return;
      }

      ResourceLocation skin = source.skinTexture();
      if (skin == null) {
         return;
      }

      if (mode == 3) {
         // 完整贴图：整张原图（任意分辨率都对，因为用的是 64 基准的全幅 UV）
         float side = Math.min(width, height);
         float left = x + (width - side) * 0.5F;
         float top = y + (height - side) * 0.5F;
         drawScaled(graphics, skin, 64, 64, left, top, side / 64.0F, 0, 0, 64, 64, alpha);
         return;
      }

      boolean headOnly = mode == 0;
      boolean fullBody = mode == 4;
      boolean outer = CommsConfig.portraitOuterLayer.get();
      int armWidth = source.slim() ? 3 : 4;
      int torsoRows = mode == 1 ? 6 : 12;                 // 头肩只取肩膀那段，其余取整段躯干
      int legRows = fullBody ? 12 : 0;
      int canvasWidth = headOnly ? 8 : armWidth * 2 + 8;
      int canvasHeight = headOnly ? 8 : 8 + torsoRows + legRows;
      // 二层是「膨胀」画法（原版：帽子每边 +0.5 像素，外套/袖子/裤子每边 +0.25 像素），
      // 所以画布四周各留 1 像素余量，整个人物再按比例缩放到照片框里。
      float scale = Math.min(width / (canvasWidth + 1.0F), height / (canvasHeight + 1.0F));
      float originX = x + (width - (canvasWidth + 1) * scale) * 0.5F + 0.5F * scale;
      float originY = y + (height - (canvasHeight + 1) * scale) * 0.5F + 0.5F * scale;
      int headX = headOnly ? 0 : armWidth;
      int bodyTop = 8;
      int legTop = 8 + torsoRows;

      float[] tint = tintOf(alpha);
      PoseStack pose = graphics.pose();
      pose.pushPose();
      pose.translate(originX, originY, 0.0F);
      pose.scale(scale, scale, 1.0F);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(tint[0], tint[1], tint[2], tint[3]);
      try {
         // ---- 腿（先底层，再裤子二层）----
         if (fullBody) {
            blit(graphics, skin, headX, legTop, 4, 20, 4, legRows);
            blit(graphics, skin, headX + 4, legTop, 20, 52, 4, legRows);
            if (outer) {
               blitInflated(graphics, skin, headX, legTop, 4, legRows, 4, 36, 0.25F);
               blitInflated(graphics, skin, headX + 4, legTop, 4, legRows, 4, 52, 0.25F);
            }
         }

         // ---- 躯干（底层 → 外套二层）----
         if (!headOnly) {
            blit(graphics, skin, headX, bodyTop, 20, 20, 8, torsoRows);
            if (outer) {
               blitInflated(graphics, skin, headX, bodyTop, 8, torsoRows, 20, 36, 0.25F);
            }

            // ---- 右臂（底层 → 右袖二层）----
            blit(graphics, skin, 0, bodyTop, 44, 20, armWidth, torsoRows);
            if (outer) {
               blitInflated(graphics, skin, 0, bodyTop, armWidth, torsoRows, 44, 36, 0.25F);
            }

            // ---- 左臂（底层 → 左袖二层）----
            blit(graphics, skin, armWidth + 8, bodyTop, 36, 52, armWidth, torsoRows);
            if (outer) {
               blitInflated(graphics, skin, armWidth + 8, bodyTop, armWidth, torsoRows, 52, 52, 0.25F);
            }
         }

         // ---- 头（底层 → 帽子二层，帽子膨胀更大：每边 0.5；由「二层皮肤」总开关控制）----
         blit(graphics, skin, headX, 0, 8, 8, 8, 8);
         if (outer) {
            blitInflated(graphics, skin, headX, 0, 8, 8, 40, 8, 0.5F);
         }
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         pose.popPose();
      }
   }

   /** 底层皮肤：按画布像素 1:1 取 (u,v,w,h)。UV 以 64 布局为基准，HD 皮肤自动正确。 */
   private static void blit(GuiGraphics graphics, ResourceLocation skin, int dx, int dy, int u, int v, int w, int h) {
      graphics.blit(skin, dx, dy, u, v, w, h, 64, 64);
   }

   /**
    * 二层皮肤：和原版一样「膨胀」绘制——以 (baseX, baseY) 为底层左上角，
    * 每边向外扩 inflate 像素（帽子 0.5、外套/袖/裤 0.25），所以比底层宽出来的部分也能显示。
    */
   private static void blitInflated(GuiGraphics graphics, ResourceLocation skin, float baseX, float baseY,
                                    int w, int h, int u, int v, float inflate) {
      PoseStack pose = graphics.pose();
      pose.pushPose();
      pose.translate(baseX - inflate, baseY - inflate, 0.0F);
      pose.scale((w + inflate * 2.0F) / w, (h + inflate * 2.0F) / h, 1.0F);
      graphics.blit(skin, 0, 0, u, v, w, h, 64, 64);
      pose.popPose();
   }

   /** 任意贴图按 scale 缩放绘制（自定义立绘 / 整张原图）。 */
   private static void drawScaled(GuiGraphics graphics, ResourceLocation texture, int textureWidth, int textureHeight,
                                  float left, float top, float scale, int u, int v, int w, int h, float alpha) {
      float[] tint = tintOf(alpha);
      PoseStack pose = graphics.pose();
      pose.pushPose();
      pose.translate(left, top, 0.0F);
      pose.scale(scale, scale, 1.0F);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(tint[0], tint[1], tint[2], tint[3]);
      try {
         graphics.blit(texture, 0, 0, u, v, w, h, textureWidth, textureHeight);
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         pose.popPose();
      }
   }

   /** 染色(portraitTint) × 亮度(只做变暗) × 不透明度(portraitOpacity)。 */
   private static float[] tintOf(float alpha) {
      int tint = CommsConfig.portraitTint.get();
      float brightness = Math.max(0.1F, Math.min(1.0F, CommsConfig.portraitBrightness.get() / 100.0F));
      float r = Math.max(0.0F, Math.min(1.0F, ((tint >> 16) & 255) / 255.0F * brightness));
      float g = Math.max(0.0F, Math.min(1.0F, ((tint >> 8) & 255) / 255.0F * brightness));
      float b = Math.max(0.0F, Math.min(1.0F, (tint & 255) / 255.0F * brightness));
      float a = Math.max(0.0F, Math.min(1.0F, alpha * CommsConfig.portraitOpacity.get() / 100.0F));
      return new float[]{r, g, b, a};
   }
}

