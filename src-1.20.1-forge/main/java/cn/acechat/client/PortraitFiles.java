package cn.acechat.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
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
 * 自定义立绘：把 PNG 放进 {@code config/acechat/portraits/} 就能在形象框里当立绘用。
 *
 * <p>只认 **PNG**（带透明通道即可，建议 256×256 以上、竖构图）；界面里点「重新扫描立绘目录」后即可选到。
 * 图片会被拉伸铺满照片框（保持比例居中），并受「立绘缩放 / 立绘偏移」两个滑块控制。
 * 读取失败或没有立绘时自动回落到皮肤绘制，不会出现空白框。
 */
public final class PortraitFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");

    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Map<String, int[]> SIZES = new HashMap<>();
    private static List<String> cachedFiles;
    /** 每张贴图最近一次烘焙时间：同一 id 在 500ms 内不重复烘焙（防止参数抖动导致每帧重做）。 */
    private static final java.util.Map<String, Long> LAST_BAKE = new java.util.HashMap<>();
    private static final java.util.Map<String, ResourceLocation> LAST_TEXTURE = new java.util.HashMap<>();
    private static boolean bakeWarned;
    /** 烘焙次数（诊断用）。 */
    public static int bakeCount;
    private static boolean warned;

    private PortraitFiles() {
    }

    public static Path portraitDir() {
        return AceChatFolders.portraitsDir();
    }

    /** 目录里的 PNG 列表（按名字排序）。 */
    public static List<String> userFiles() {
        if (cachedFiles != null) {
            return cachedFiles;
        }

        List<String> result = new ArrayList<>();
        try {
            Path dir = portraitDir();
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
                LOGGER.warn("[acechat] 读取立绘目录失败（可忽略，皮肤绘制不受影响）", error);
            }
        }

        cachedFiles = result;
        return result;
    }

    /** 清空贴图缓存（「重载渲染」按钮用：下次绘制时重新读取与烘焙）。 */
    public static void clearCache() {
        LOADED.clear();
        SIZES.clear();
        LAST_BAKE.clear();
        LAST_TEXTURE.clear();
        rescan();
    }

    public static void rescan() {
        cachedFiles = null;
    }

    /**
     * 当前应该使用的立绘贴图：没开立绘 / 没有文件 / 读取失败都返回 null（调用方回落到皮肤）。
     * 必须在渲染线程调用。
     */
    public static ResourceLocation texture(Minecraft minecraft, String fileName) {
        String name = fileName;
        if (name == null || name.isEmpty()) {
            List<String> files = userFiles();
            if (files.isEmpty()) {
                return null;
            }

            name = files.get(0);
        }

        // 缓存键把「调色参数」算进去：参数一变就重新烘焙，所以立绘的调色会立即生效
        String key = name + "#" + CommsConfig.gradeKey();
        if (LOADED.containsKey(key)) {
            return LOADED.get(key);
        }

        // 时间闸门：同一张立绘 500ms 内只烘焙一次；期间若键又变了，先复用上一张，避免每帧重做
        long now = System.currentTimeMillis();
        Long last = LAST_BAKE.get(name);
        if (last != null && now - last < 500L) {
            ResourceLocation previous = LAST_TEXTURE.get(name);
            if (previous != null) {
                if (!bakeWarned) {
                    bakeWarned = true;
                    LOGGER.warn("[acechat] 立绘调色参数变化过于频繁，已临时复用上一张烘焙结果（每 500ms 最多重做一次）");
                }

                return previous;
            }
        }

        try {
            Path path = portraitDir().resolve(name);
            if (!Files.isRegularFile(path)) {
                return null;
            }

            NativeImage image;
            try (InputStream in = Files.newInputStream(path)) {
                image = NativeImage.read(in);
            }

            int width = image.getWidth();
            int height = image.getHeight();
            // 立绘调色：与皮肤调色同一套参数与算法
            PortraitGradeMath.grade(image, CommsConfig.gradeBrightness.get(), CommsConfig.gradeContrast.get(),
               CommsConfig.gradeSaturation.get(), CommsConfig.gradeHue.get(), CommsConfig.duotoneEnabled.get(),
               CommsConfig.duotoneDark.get(), CommsConfig.duotoneLight.get(), CommsConfig.duotoneAmount.get());
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = new ResourceLocation("acechat",
                    "portraits/" + name.replace('.', '_').toLowerCase(Locale.ROOT));
            minecraft.getTextureManager().register(location, texture);
            LOADED.put(name, location);
            SIZES.put(name, new int[]{width, height});
            return location;
        } catch (Exception error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[acechat] 读取立绘 " + name + " 失败（已回落到皮肤绘制）", error);
            }

            return null;
        }
    }

    /** 已经加载过的立绘尺寸（宽, 高）。 */
    public static int[] sizeOf(String fileName) {
        return SIZES.getOrDefault(fileName, new int[]{1, 1});
    }
}
