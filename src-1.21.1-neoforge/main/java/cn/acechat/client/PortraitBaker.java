package cn.acechat.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步把皮肤正面 UV 拼成一张 2D 半身像贴图。
 * r9 修正：
 *  1) 支持任意分辨率皮肤（64 / 128 / 256 / 512 …），按 宽度/64 的比例换算 UV —— 装了 CustomSkinLoader 的 HD 皮肤不再错位；
 *  2) 修正左臂取样（正面 36,52、袖子 52,52），原版写成了 40,20（那是右臂侧面）导致半身像残缺；
 *  3) 画布改成贴合半身的 16×20（按皮肤比例放大），不再把 8×8 的头塞进 32×48 的大画布；
 *  4) 64×32 旧格式皮肤单独处理：有头和帽子层、没有外套和左臂，左臂用右臂镜像补齐；
 *  5) 帽子层 / 外层用标准 alpha 混合叠加，不再直接覆盖。
 */
public final class PortraitBaker {
   private static final Map<String, ResourceLocation> READY = new ConcurrentHashMap<>();
   private static final Map<String, Boolean> RUNNING = new ConcurrentHashMap<>();

   private PortraitBaker() {
   }

   public static ResourceLocation textureFor(Minecraft minecraft, PortraitSource source) {
      String key = source.sender() + "|" + source.skinTexture() + "|" + source.slim() + "|" + CommsConfig.portraitOuterLayer.get();
      ResourceLocation ready = READY.get(key);
      if (ready != null) {
         return ready;
      }

      if (RUNNING.putIfAbsent(key, Boolean.TRUE) == null) {
         CompletableFuture.runAsync(() -> bake(minecraft, source, key));
      }

      return null;
   }

   public static void clear() {
      READY.clear();
      RUNNING.clear();
   }

   private static void bake(Minecraft minecraft, PortraitSource source, String key) {
      NativeImage input = null;
      try {
         Resource resource = minecraft.getResourceManager().getResource(source.skinTexture()).orElse(null);
         if (resource == null) {
            return;
         }

         input = NativeImage.read(resource.open());
         int skinWidth = input.getWidth();
         int skinHeight = input.getHeight();
         if (skinWidth < 64 || skinHeight < 32) {
            return;
         }

         int scale = Math.max(1, Math.min(8, skinWidth / 64));
         boolean legacy = skinHeight < 64;
         int armWidth = source.slim() ? 3 : 4;
         int bustWidth = armWidth * 2 + 8;
         int bustHeight = 20;
         NativeImage bust = new NativeImage(bustWidth * scale, bustHeight * scale, true);
         int headX = armWidth;
         int armY = 8;

         // 头 + 帽子层
         copy(input, bust, 8, 8, 8, 8, headX, 0, false, scale, false);
         if (CommsConfig.portraitOuterLayer.get()) {
            copy(input, bust, 40, 8, 8, 8, headX, 0, true, scale, false);
         }

         // 身体 + 外套层
         copy(input, bust, 20, 20, 8, 12, headX, armY, false, scale, false);
         if (!legacy) {
            copy(input, bust, 20, 36, 8, 12, headX, armY, true, scale, false);
         }

         // 右臂（正面 44,20；袖子 44,36）
         int rightArmX = 0;
         int leftArmX = headX + 8;
         copy(input, bust, 44, 20, armWidth, 12, rightArmX, armY, false, scale, false);
         if (legacy) {
            copy(input, bust, 44, 20, armWidth, 12, leftArmX, armY, false, scale, true);
         } else {
            copy(input, bust, 36, 52, armWidth, 12, leftArmX, armY, false, scale, false);
            copy(input, bust, 44, 36, armWidth, 12, rightArmX, armY, true, scale, false);
            copy(input, bust, 52, 52, armWidth, 12, leftArmX, armY, true, scale, false);
         }

         ResourceLocation location = ResourceLocation.fromNamespaceAndPath("acechat", "baked/" + safe(source.sender()) + "_" + Integer.toHexString(key.hashCode())
         );
         NativeImage finished = bust;
         minecraft.execute(() -> {
            try {
               minecraft.getTextureManager().register(location, new DynamicTexture(finished));
               READY.put(key, location);
            } catch (RuntimeException ignored) {
               finished.close();
            }
         });
      } catch (IOException | RuntimeException ignored) {
         // 皮肤没准备好 / 离线玩家：保持 READY 为空，由 Steve/Alex 默认皮肤兜底。
      } finally {
         if (input != null) {
            input.close();
         }

         RUNNING.remove(key);
      }
   }

   /**
    * 把源贴图里 (u,v,w,h) 的区域（按 scale 放大）画到 target 的 (dx,dy)。
    * overlay=true 时按标准 alpha 混合叠加，flipX=true 时水平镜像。
    */
   private static void copy(NativeImage source, NativeImage target, int u, int v, int w, int h, int dx, int dy, boolean overlay, int scale, boolean flipX) {
      int sourceWidth = source.getWidth();
      int sourceHeight = source.getHeight();
      for (int y = 0; y < h * scale; y++) {
         for (int x = 0; x < w * scale; x++) {
            int sx = u * scale + (flipX ? w * scale - 1 - x : x);
            int sy = v * scale + y;
            if (sx < 0 || sy < 0 || sx >= sourceWidth || sy >= sourceHeight) {
               continue;
            }

            int tx = dx * scale + x;
            int ty = dy * scale + y;
            if (tx < 0 || ty < 0 || tx >= target.getWidth() || ty >= target.getHeight()) {
               continue;
            }

            int pixel = source.getPixelRGBA(sx, sy);
            if (overlay) {
               int alpha = (pixel >>> 24) & 255;
               if (alpha == 0) {
                  continue;
               }

               pixel = blend(target.getPixelRGBA(tx, ty), pixel);
            }

            target.setPixelRGBA(tx, ty, pixel);
         }
      }
   }

   /** 标准 source-over 混合；三个颜色通道用同一套公式，因此不依赖像素内部通道顺序。 */
   private static int blend(int dst, int src) {
      int sa = (src >>> 24) & 255;
      if (sa == 255) {
         return src;
      }

      int da = (dst >>> 24) & 255;
      int outA = sa + da * (255 - sa) / 255;
      if (outA == 0) {
         return 0;
      }

      int rb = mixChannel((src >>> 16) & 255, (dst >>> 16) & 255, sa, da, outA);
      int gb = mixChannel((src >>> 8) & 255, (dst >>> 8) & 255, sa, da, outA);
      int bb = mixChannel(src & 255, dst & 255, sa, da, outA);
      return (outA << 24) | (rb << 16) | (gb << 8) | bb;
   }

   private static int mixChannel(int sc, int dc, int sa, int da, int outA) {
      return (sc * sa + dc * da * (255 - sa) / 255) / outA;
   }

   private static String safe(UUID uuid) {
      return uuid.toString().replace('-', '_');
   }
}
