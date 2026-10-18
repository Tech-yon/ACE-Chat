package cn.acechat.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 形象框的「调色」：亮度 / 对比度 / 饱和度 / 色相 / 双色调。
 *
 * <p>这些是逐像素运算，没法靠混合模式实现，所以做法是<b>把皮肤贴图烘焙一份调过色的副本</b>：
 * 一次烘焙（后台线程做像素运算）→ 注册成 GPU 纹理 → 之后每帧照常画，零额外开销。
 *
 * <p>皮肤像素来源：先按 {@code SkinManager.getTextures(profile)} 拿皮肤 URL 下载（换肤模组加载的皮肤同样适用），
 * 拿不到就退回资源管理器里的贴图。两条路都不通时保持原样（叠加滤镜仍然可用），只记一条日志。
 *
 * <p>注意：NativeImage 的 {@code getPixelRGBA/setPixelRGBA} 整数布局是 {@code 0xAABBGGRR}（低字节是 R），
 * 这一点是实测确认过的。
 */
public final class PortraitGrade {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static boolean logged;

    private PortraitGrade() {
    }

    /** 参数变了：丢掉已烘焙的纹理（下次绘制重新烘焙）。 */
    public static void invalidate() {
        CACHE.clear();
        PENDING.clear();
        FAILED.clear();
    }

    /**
     * 返回应该用来绘制的皮肤贴图：开了调色就用烘焙好的副本，否则/还没烘焙好就用原贴图。
     * 调用发生在渲染线程，真正的烘焙在后台线程，完成后再回到渲染线程注册纹理。
     */
    public static ResourceLocation gradedOrOriginal(Minecraft minecraft, PortraitSource source) {
        ResourceLocation original = source.skinTexture();
        if (original == null || !CommsConfig.gradeEnabled.get()) {
            return original;
        }

        String key = original + "|" + source.slim() + "|" + CommsConfig.gradeKey();
        ResourceLocation baked = CACHE.get(key);
        if (baked != null) {
            return baked;
        }

        if (!FAILED.contains(key) && PENDING.add(key)) {
            startBake(minecraft, source, key);
        }

        return original;
    }

    private static void startBake(Minecraft minecraft, PortraitSource source, String key) {
        UUID id = source.sender();
        String name = source.name();
        GameProfile profile = profileOf(minecraft, id);
        ResourceLocation location = source.skinTexture();
        int brightness = CommsConfig.gradeBrightness.get();
        int contrast = CommsConfig.gradeContrast.get();
        int saturation = CommsConfig.gradeSaturation.get();
        int hue = CommsConfig.gradeHue.get();
        boolean duotone = CommsConfig.duotoneEnabled.get();
        int dark = CommsConfig.duotoneDark.get();
        int light = CommsConfig.duotoneLight.get();
        int amount = CommsConfig.duotoneAmount.get();

        Thread thread = new Thread(() -> {
            NativeImage image = download(minecraft, profile, name);
            if (image == null) {
                // 1.21：本机/缓存的皮肤没有 URL，直接读已加载的那张皮肤贴图（它是 DynamicTexture）
                image = fromLoadedTexture(minecraft, location);
            }

            if (image == null) {
                image = fromResourceManager(minecraft, location);
            }

            if (image == null) {
                FAILED.add(key);
                if (!logged) {
                    logged = true;
                    LOGGER.warn("[acechat] 取不到皮肤像素，调色暂不可用（叠加滤镜不受影响）");
                }

                return;
            }

            apply(image, brightness, contrast, saturation, hue, duotone, dark, light, amount);
            NativeImage result = image;
            String textureKey = Integer.toHexString(key.hashCode());
            Minecraft.getInstance().execute(() -> register(textureKey, result, key));
        }, "acechat-grade");
        thread.setDaemon(true);
        thread.start();
    }

    private static void register(String textureKey, NativeImage image, String cacheKey) {
        try {
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("acechat", "graded/" + textureKey);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            CACHE.put(cacheKey, location);
        } catch (RuntimeException | LinkageError error) {
            FAILED.add(cacheKey);
            image.close();
            LOGGER.warn("[acechat] 注册调色贴图失败（该皮肤将保持原色）", error);
        }
    }

    private static GameProfile profileOf(Minecraft minecraft, UUID id) {
        try {
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(id);
            if (info != null && info.getProfile() != null) {
                return info.getProfile();
            }
        } catch (RuntimeException ignored) {
        }

        try {
            if (minecraft.player != null && minecraft.player.getUUID().equals(id)) {
                return minecraft.player.getGameProfile();
            }

            if (minecraft.level != null) {
                for (var player : minecraft.level.players()) {
                    if (player.getUUID().equals(id)) {
                        return player.getGameProfile();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        return null;
    }

    /** 拿到皮肤原始 PNG：优先用换肤模组解析出的皮肤地址下载。 */
    private static NativeImage download(Minecraft minecraft, GameProfile profile, String name) {
        if (profile == null) {
            return null;
        }

        try {
            // 1.21：SkinManager 直接返回 PlayerSkin
            net.minecraft.client.resources.PlayerSkin textures = minecraft.getSkinManager().getInsecureSkin(profile);
            net.minecraft.client.resources.PlayerSkin skin = textures;
            String url = skin == null ? null : skin.textureUrl();
            if (url == null || url.isBlank()) {
                return null;
            }

            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("User-Agent", "ACEChat/1.0");
            try (InputStream in = connection.getInputStream()) {
                return NativeImage.read(in);
            }
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * 兜底（1.21 专用）：直接从贴图管理器读已加载的皮肤像素。
     *
     * <p>1.21 里玩家皮肤是以 {@code DynamicTexture} 注册的，所以
     * {@link #download}（本机皮肤没有 URL）和 {@link #fromResourceManager}（资源管理器里没有这张动态贴图）
     * 都可能拿不到像素 —— 结果就是「取不到皮肤像素，调色暂不可用」。这里补上第三条路。
     *
     * <p>注意：返回的是<b>复制</b>，因为原贴图正在绘制，调色会改像素。
     */
    private static NativeImage fromLoadedTexture(Minecraft minecraft, ResourceLocation location) {
        if (minecraft == null || location == null) {
            return null;
        }

        try {
            net.minecraft.client.renderer.texture.AbstractTexture texture =
                    minecraft.getTextureManager().getTexture(location);
            if (!(texture instanceof net.minecraft.client.renderer.texture.DynamicTexture dynamic)) {
                return null;
            }

            NativeImage pixels = dynamic.getPixels();
            if (pixels == null || pixels.getWidth() <= 0 || pixels.getHeight() <= 0) {
                return null;
            }

            NativeImage copy = new NativeImage(pixels.getWidth(), pixels.getHeight(), false);
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    copy.setPixelRGBA(x, y, pixels.getPixelRGBA(x, y));
                }
            }

            return copy;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 兜底：默认皮肤 / 资源包里的皮肤可以直接从资源管理器读。 */
    private static NativeImage fromResourceManager(Minecraft minecraft, ResourceLocation location) {
        try {
            var resource = minecraft.getResourceManager().getResource(location).orElse(null);
            if (resource == null) {
                return null;
            }

            try (InputStream in = resource.open()) {
                return NativeImage.read(in);
            }
        } catch (Exception error) {
            return null;
        }
    }

    /** 逐像素调色。通道布局：0xAABBGGRR（低字节 R）。 */
    static void apply(NativeImage image, int brightness, int contrast, int saturation, int hue,
                      boolean duotone, int dark, int light, int amount) {
        float bright = brightness / 100.0F;
        float contrastFactor = contrast / 100.0F;
        float sat = saturation / 100.0F;
        float hueShift = (float) Math.toRadians(hue);
        float duotoneMix = Math.max(0.0F, Math.min(1.0F, amount / 100.0F));

        float cos = (float) Math.cos(hueShift);
        float sin = (float) Math.sin(hueShift);
        float darkR = (dark >> 16) & 255, darkG = (dark >> 8) & 255, darkB = dark & 255;
        float lightR = (light >> 16) & 255, lightG = (light >> 8) & 255, lightB = light & 255;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = image.getPixelRGBA(x, y);
                int alpha = (value >>> 24) & 255;
                if (alpha == 0) {
                    continue;
                }

                float b = (value >> 16) & 255;
                float g = (value >> 8) & 255;
                float r = value & 255;

                // 亮度
                r *= bright;
                g *= bright;
                b *= bright;

                // 对比度（绕 128 缩放）
                r = (r - 128.0F) * contrastFactor + 128.0F;
                g = (g - 128.0F) * contrastFactor + 128.0F;
                b = (b - 128.0F) * contrastFactor + 128.0F;

                // 饱和度（向亮度靠拢/远离，Rec.709 权重）
                float luma = 0.2126F * r + 0.7152F * g + 0.0722F * b;
                r = luma + (r - luma) * sat;
                g = luma + (g - luma) * sat;
                b = luma + (b - luma) * sat;

                // 色相旋转（绕 RGB 立方体的灰轴）
                if (hue != 0) {
                    float nr = (0.213F + cos * 0.787F - sin * 0.213F) * r
                            + (0.715F - cos * 0.715F - sin * 0.715F) * g
                            + (0.072F - cos * 0.072F + sin * 0.928F) * b;
                    float ng = (0.213F - cos * 0.213F + sin * 0.143F) * r
                            + (0.715F + cos * 0.285F + sin * 0.140F) * g
                            + (0.072F - cos * 0.072F - sin * 0.283F) * b;
                    float nb = (0.213F - cos * 0.213F - sin * 0.787F) * r
                            + (0.715F - cos * 0.715F + sin * 0.715F) * g
                            + (0.072F + cos * 0.928F + sin * 0.072F) * b;
                    r = nr;
                    g = ng;
                    b = nb;
                }

                // 双色调：按亮度映射到「暗色 → 亮色」渐变
                if (duotone && duotoneMix > 0.0F) {
                    float t = Math.max(0.0F, Math.min(1.0F, (0.2126F * r + 0.7152F * g + 0.0722F * b) / 255.0F));
                    float tr = darkR + (lightR - darkR) * t;
                    float tg = darkG + (lightG - darkG) * t;
                    float tb = darkB + (lightB - darkB) * t;
                    r += (tr - r) * duotoneMix;
                    g += (tg - g) * duotoneMix;
                    b += (tb - b) * duotoneMix;
                }

                int nr = clamp(r);
                int ng = clamp(g);
                int nb = clamp(b);
                image.setPixelRGBA(x, y, (alpha << 24) | (nb << 16) | (ng << 8) | nr);
            }
        }
    }

    private static int clamp(float value) {
        int rounded = Math.round(value);
        return Math.max(0, Math.min(255, rounded));
    }

    private static String toLowerCase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
