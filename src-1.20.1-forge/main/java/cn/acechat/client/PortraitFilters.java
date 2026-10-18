package cn.acechat.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 形象框的「叠加滤镜」：扫描线、噪点、暗角、双色调、点阵这些质感层。
 *
 * <p>两种来源：
 * <ul>
 *   <li><b>内置</b>（代码直接生成贴图，不需要任何文件）：扫描线 / 噪点 / 暗角 / 双色调 / 点阵；</li>
 *   <li><b>自定义</b>：把任意带透明通道的 PNG 放进 {@code config/acechat/filters/}，界面里就能选到。</li>
 * </ul>
 *
 * <p>绘制时按「照片框」大小拉伸，用 {@code RenderSystem.blendFunc} 切换正常 / 正片叠底 / 滤色 / 加法，
 * 每帧只多画一个四边形。
 */
public final class PortraitFilters {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final int SIZE = 64;

    /** 内置滤镜 id。 */
    public static final String[] BUILTIN_IDS = {"builtin:scanlines", "builtin:scanlines_h", "builtin:grain", "builtin:vignette", "builtin:duotone", "builtin:dots"};

    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Map<String, Integer> SIZES = new HashMap<>();
    private static List<String> cachedFiles;
    private static boolean warned;
    /** 同一个滤镜 500ms 内只生成一次，避免参数抖动导致每帧重建贴图。 */
    private static final Map<String, Long> LAST_BAKE = new HashMap<>();
    private static final Map<String, ResourceLocation> LAST_TEXTURE = new HashMap<>();
    private static boolean bakeWarned;
    /** 生成次数（诊断用）。 */
    public static int bakeCount;

    private PortraitFilters() {
    }

    /** 扫描 config/acechat/filters 目录里的 PNG（界面刷新用）。 */
    public static List<String> userFiles() {
        if (cachedFiles != null) {
            return cachedFiles;
        }

        List<String> result = new ArrayList<>();
        try {
            Path dir = filtersDir();
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                            .sorted()
                            .forEach(p -> result.add(p.getFileName().toString()));
                }
            }
        } catch (Exception error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[acechat] 读取滤镜目录失败（可以忽略，内置滤镜仍可用）", error);
            }
        }

        cachedFiles = result;
        return result;
    }

    /** 清空滤镜贴图缓存（「重载渲染」按钮用）。 */
    public static void clearCache() {
        LOADED.clear();
        SIZES.clear();
        LAST_BAKE.clear();
        LAST_TEXTURE.clear();
        rescan();
    }

    /** 让下次调用重新扫描目录。 */
    public static void rescan() {
        cachedFiles = null;
    }

    public static Path filtersDir() {
        return AceChatFolders.filtersDir();
    }

    /** 缓存键：把颜色与调色参数一起算进去，参数一变就重新生成。 */
    private static String cacheKey(String id) {
        return id + "#" + CommsConfig.effectColorFor(id, defaultEffectColor(id))
                + "#" + CommsConfig.filterBrightness.get() + "#" + CommsConfig.filterContrast.get()
                + "#" + CommsConfig.filterSaturation.get() + "#" + CommsConfig.filterHue.get();
    }

    /** 内置特效的默认颜色（0xAABBGGRR）。 */
    private static int defaultEffectColor(String filterId) {
        return switch (filterId) {
            case "builtin:dots" -> 0xFFFFFFFF;
            case "builtin:duotone" -> 0xFFB86663;
            case "builtin:grain" -> 0xFFC8C8C8;
            default -> 0xFF000000;
        };
    }

    /** 取得（必要时生成/加载）滤镜贴图。必须在渲染线程调用。 */
    private static ResourceLocation texture(Minecraft minecraft, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }

        String key = cacheKey(id);
        ResourceLocation cached = LOADED.get(key);
        if (cached != null) {
            return cached;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_BAKE.get(id);
        if (last != null && now - last < 500L) {
            ResourceLocation previous = LAST_TEXTURE.get(id);
            if (previous != null) {
                if (!bakeWarned) {
                    bakeWarned = true;
                    LOGGER.warn("[acechat] 滤镜参数变化过于频繁，已临时复用上一张滤镜贴图（每 500ms 最多重做一次）");
                }

                return previous;
            }
        }

        try {
            NativeImage image = id.startsWith("builtin:")
                    ? generate(id.substring("builtin:".length()), CommsConfig.effectColorFor(id, defaultEffectColor(id)))
                    : readFile(id);
            if (image == null) {
                return null;
            }

            grade(image);   // 滤镜自身的亮度/对比度/饱和度/色相（烘焙，不影响其它模块）

            int width = image.getWidth();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = new ResourceLocation("acechat",
                    "filters/" + Math.abs(key.hashCode()) + "_" + id.replace(':', '_').replace('.', '_').toLowerCase(Locale.ROOT));
            minecraft.getTextureManager().register(location, texture);
            LOADED.put(key, location);
            bakeCount++;
            LAST_BAKE.put(id, System.currentTimeMillis());
            LAST_TEXTURE.put(id, location);
            SIZES.put(key, width);
            return location;
        } catch (Exception error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[acechat] 加载滤镜 " + id + " 失败（已跳过该滤镜）", error);
            }

            return null;
        }
    }

    private static NativeImage readFile(String fileName) throws Exception {
        Path path = filtersDir().resolve(fileName);
        if (!Files.isRegularFile(path)) {
            return null;
        }

        try (InputStream in = Files.newInputStream(path)) {
            return NativeImage.read(in);
        }
    }

    /** 程序生成内置滤镜（0xAABBGGRR 通道顺序，和 NativeImage 的 get/setPixelRGBA 一致）。 */
    private static NativeImage generate(String kind, int color) {
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        int seed = 0x5EED;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = switch (kind) {
                    // 干扰线：颜色可单独调（默认黑），透明度沿用原来的 0x46，观感不变
                    case "scanlines" -> (x % 4 == 0) ? ((0x46 << 24) | (color & 0x00FFFFFF)) : 0x00000000;
                    case "scanlines_h" -> (y % 4 == 0) ? ((0x46 << 24) | (color & 0x00FFFFFF)) : 0x00000000;
                    case "grain" -> {
                        seed = seed * 1103515245 + 12345;
                        int noise = (seed >>> 16) & 0xFF;
                        if (noise < 150) {
                            yield 0x00000000;
                        }
                        int value = 200 + (noise % 56);
                        yield (0x28 << 24) | (color & 0x00FFFFFF);
                    }
                    case "vignette" -> {
                        double dx = (x + 0.5D) / SIZE * 2.0D - 1.0D;
                        double dy = (y + 0.5D) / SIZE * 2.0D - 1.0D;
                        double distance = Math.min(1.0D, Math.sqrt(dx * dx + dy * dy) / 1.35D);
                        int alpha = (int) Math.round(Math.pow(distance, 2.2D) * 210.0D);
                        yield (alpha << 24) | (color & 0x00FFFFFF);
                    }
                    case "duotone" -> {
                        int alpha = (int) Math.round(30.0D + (y / (double) SIZE) * 120.0D);
                        yield (alpha << 24) | (color & 0x00FFFFFF);
                    }
                    case "dots" -> ((x % 8 == 0) && (y % 8 == 0)) ? ((0x32 << 24) | (color & 0x00FFFFFF)) : 0x00000000;
                    default -> 0x00000000;
                };
                image.setPixelRGBA(x, y, argb);
            }
        }

        return image;
    }

    /** 滤镜自身的调色（与皮肤/立绘调色共用同一套算法）。 */
    private static void grade(NativeImage image) {
        PortraitGradeMath.grade(image, CommsConfig.filterBrightness.get(), CommsConfig.filterContrast.get(),
            CommsConfig.filterSaturation.get(), CommsConfig.filterHue.get(), false, 0, 0, 0);
    }

    /**
     * 把当前选中的滤镜铺到给定矩形上（照片框）。
     * 混合模式：0 正常 1 正片叠底 2 滤色 3 加法。
     */
    public static void draw(GuiGraphics graphics, Minecraft minecraft, float x, float y, float width, float height) {
        if (!CommsConfig.filterEnabled.get() || width <= 2.0F || height <= 2.0F) {
            return;
        }

        String id = CommsConfig.filterName.get();
        ResourceLocation texture = texture(minecraft, id);
        if (texture == null) {
            return;
        }

        int size = SIZES.getOrDefault(cacheKey(id), SIZE);
        float alpha = Math.max(0.0F, Math.min(1.0F, CommsConfig.filterAlpha.get() / 100.0F));
        if (alpha <= 0.0F) {
            return;
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale(width / size, height / size, 1.0F);
        RenderSystem.enableBlend();
        switch (CommsConfig.filterBlend.get()) {
            case 1 -> RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.SRC_COLOR);
            case 2 -> RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR);
            case 3 -> RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            default -> RenderSystem.defaultBlendFunc();
        }

        // 颜色蒙版 × 整卡调色色调（两级相乘，白 = 不改色）
        int filterTint = CommsConfig.filterTint.get();
        int cardTint = CommsConfig.gradeEnabled.get()
                ? PortraitGradeMath.cardTintArgb(CommsConfig.gradeBrightness.get(), CommsConfig.gradeHue.get())
                : 0xFFFFFFFF;
        RenderSystem.setShaderColor((((filterTint >>> 16) & 0xFF) * (((cardTint >>> 16) & 0xFF)) / 255.0F) / 255.0F,
                (((filterTint >>> 8) & 0xFF) * (((cardTint >>> 8) & 0xFF)) / 255.0F) / 255.0F,
                ((filterTint & 0xFF) * (cardTint & 0xFF) / 255.0F) / 255.0F, alpha);
        try {
            graphics.blit(texture, 0, 0, 0, 0, size, size, size, size);
        } catch (RuntimeException | LinkageError error) {
            // 兜底：滤镜画不出来也不能连累整个 HUD / 配置界面
            if (!warned) {
                warned = true;
                LOGGER.warn("[acechat] 绘制叠加滤镜失败（已跳过该滤镜）", error);
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
        }

        pose.popPose();
    }
}
