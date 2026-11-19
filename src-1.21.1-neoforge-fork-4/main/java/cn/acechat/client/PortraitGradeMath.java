package cn.acechat.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 调色算法（共用）：亮度 / 对比度 / 饱和度 / 色相 / 双色调。
 *
 * <p>皮肤调色、立绘调色、滤镜自身调色都调用这里，保证三处效果一致。
 * 像素格式是 {@code 0xAABBGGRR}（低字节 = R），与 {@link NativeImage#getPixelRGBA} 一致。
 */
public final class PortraitGradeMath {
    private PortraitGradeMath() {
    }

    /** 是否是一组「什么都不改」的参数（用来跳过整张贴图的遍历）。 */
    public static boolean isIdentity(int brightness, int contrast, int saturation, int hue, boolean duotone) {
        return brightness == 100 && contrast == 100 && saturation == 100 && hue == 0 && !duotone;
    }

    /**
     * 整卡乘法色调（0xAABBGGRR，白色 = 不改）：把调色里的【亮度】与【色相】折成一个乘法色，
     * 供卡片底色/边框/滤镜层使用 —— 这样「对整个形象框调色」也有效果。
     * 对比度/饱和度无法用乘法表达（需要后期着色器），只对皮肤/立绘/滤镜贴图烘焙生效。
     */
    public static int cardTintArgb(int brightness, int hue) {
        if (brightness == 100 && hue == 0) {
            return 0xFFFFFFFF;
        }

        float scale = Math.max(0.05F, Math.min(2.0F, brightness / 100.0F));
        int r = Math.round(255.0F * scale);
        int g = Math.round(255.0F * scale);
        int b = Math.round(255.0F * scale);
        if (hue != 0) {
            // 色相偏移：在 HSV 里把白色的色相转过去（饱和度很低，只做轻微偏色）
            int rgb = java.awt.Color.HSBtoRGB(((hue % 360) + 360) % 360 / 360.0F, 0.22F, 1.0F);
            float mix = 0.55F;
            r = Math.round(r * (1.0F - mix) + ((rgb >> 16) & 0xFF) * scale * mix);
            g = Math.round(g * (1.0F - mix) + ((rgb >> 8) & 0xFF) * scale * mix);
            b = Math.round(b * (1.0F - mix) + (rgb & 0xFF) * scale * mix);
        }

        return 0xFF000000 | (clamp255(b) << 16) | (clamp255(g) << 8) | clamp255(r);
    }

    /** 就地修改整张贴图。 */
    public static void grade(NativeImage image, int brightness, int contrast, int saturation, int hue,
                             boolean duotone, int duotoneDark, int duotoneLight, int duotoneAmount) {
        if (isIdentity(brightness, contrast, saturation, hue, duotone)) {
            return;
        }

        float brightnessFactor = brightness / 100.0F;
        float contrastFactor = contrast / 100.0F;
        float saturationFactor = saturation / 100.0F;
        float amount = Math.max(0.0F, Math.min(1.0F, duotoneAmount / 100.0F));

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getPixelRGBA(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }

                int r = argb & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = (argb >>> 16) & 0xFF;
                float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                float newHue = (hsb[0] * 360.0F + hue) % 360.0F;
                if (newHue < 0.0F) {
                    newHue += 360.0F;
                }

                int rgb = java.awt.Color.HSBtoRGB(newHue / 360.0F,
                        clamp01(hsb[1] * saturationFactor), clamp01(hsb[2] * brightnessFactor));
                int nr = (rgb >> 16) & 0xFF;
                int ng = (rgb >> 8) & 0xFF;
                int nb = rgb & 0xFF;
                nr = clamp255(Math.round((nr - 128) * contrastFactor + 128));
                ng = clamp255(Math.round((ng - 128) * contrastFactor + 128));
                nb = clamp255(Math.round((nb - 128) * contrastFactor + 128));

                if (duotone && amount > 0.0F) {
                    // 双色调：按亮度在「暗色 / 亮色」之间取色，再按 amount 与原色混合
                    float luminance = (0.299F * nr + 0.587F * ng + 0.114F * nb) / 255.0F;
                    int dr = duotoneDark & 0xFF;
                    int dg = (duotoneDark >>> 8) & 0xFF;
                    int db = (duotoneDark >>> 16) & 0xFF;
                    int lr = duotoneLight & 0xFF;
                    int lg = (duotoneLight >>> 8) & 0xFF;
                    int lb = (duotoneLight >>> 16) & 0xFF;
                    int mr = Math.round(dr + (lr - dr) * luminance);
                    int mg = Math.round(dg + (lg - dg) * luminance);
                    int mb = Math.round(db + (lb - db) * luminance);
                    nr = clamp255(Math.round(nr + (mr - nr) * amount));
                    ng = clamp255(Math.round(ng + (mg - ng) * amount));
                    nb = clamp255(Math.round(nb + (mb - nb) * amount));
                }

                image.setPixelRGBA(x, y, (a << 24) | (nb << 16) | (ng << 8) | nr);
            }
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int clamp255(long value) {
        return (int) Math.max(0L, Math.min(255L, value));
    }
}
