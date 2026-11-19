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
 * 死亡覆盖图：把图片放进 {@code config/acechat/deaths/}，文件名为「玩家名.png」。
 *
 * <p>规则：
 * <ul>
 *   <li><b>只读 1:1 方图</b>：宽高不相等会被忽略，并在日志里提示一次；</li>
 *   <li>读取/显示尺寸上限由「读取分辨率」设置决定（64~1024，默认 256）；</li>
 *   <li>内置预设 {@code builtin:no_signal}：程序生成的黑色方格 + 居中白色「NO SIGN」，
 *       会<b>刚好铺满背景框</b>（不依赖任何文件）。</li>
 * </ul>
 */
public final class DeathPortraits {
    /** 内置预设 id。 */
    public static final String PRESET_NO_SIGN = "builtin:no_signal";

    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Map<String, int[]> SIZES = new HashMap<>();
    private static final List<String> NON_SQUARE_LOGGED = new ArrayList<>();
    private static List<String> cachedFiles;
    private static boolean warned;

    private DeathPortraits() {
    }

    public static Path deathsDir() {
        return AceChatFolders.deathsDir();
    }

    /** 目录里的 PNG 文件名（排序）。 */
    public static List<String> userFiles() {
        if (cachedFiles != null) {
            return cachedFiles;
        }

        List<String> result = new ArrayList<>();
        try {
            Path dir = deathsDir();
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
                LOGGER.warn("[acechat] 读取死亡覆盖图目录失败（可忽略）", error);
            }
        }

        cachedFiles = result;
        return result;
    }

    /** 清空死亡覆盖图缓存（「重载渲染」按钮用）。 */
    public static void clearCache() {
        LOADED.clear();
        SIZES.clear();
        rescan();
    }

    public static void rescan() {
        cachedFiles = null;
    }

    /** 玩家名 → 目录里的文件名，例如 "PilotA" → "PilotA.png"（找不到返回 null）。 */
    public static String fileNameFor(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }

        for (String file : userFiles()) {
            if (file.equalsIgnoreCase(playerName + ".png")) {
                return file;
            }
        }

        return null;
    }

    /** 取玩家对应的死亡覆盖贴图；没有文件时返回 null（调用方改用内置预设）。必须在渲染线程调用。 */
    public static ResourceLocation textureFor(Minecraft minecraft, String playerName) {
        String file = fileNameFor(playerName);
        if (file == null) {
            return null;
        }

        if (LOADED.containsKey(file)) {
            return LOADED.get(file);
        }

        try {
            Path path = deathsDir().resolve(file);
            if (!Files.isRegularFile(path)) {
                return null;
            }

            NativeImage image;
            try (InputStream in = Files.newInputStream(path)) {
                image = NativeImage.read(in);
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int cap = Math.max(64, Math.min(1024, CommsConfig.deathImageMaxSize.get()));
            if (width != height) {
                // 只读 1:1 方图：非方图忽略并提示一次
                if (!NON_SQUARE_LOGGED.contains(file)) {
                    NON_SQUARE_LOGGED.add(file);
                    LOGGER.warn("[acechat] 死亡覆盖图 {} 不是 1:1 方图（{}x{}），已忽略；请换成宽高相等的图片", file, width, height);
                }

                return null;
            }

            if (width > 1024) {
                LOGGER.warn("[acechat] 死亡覆盖图 {} 超过 1024x1024（{}x{}），将按上限缩放显示", file, width, height);
            }

            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("acechat",
                    "deaths/" + file.replace('.', '_').toLowerCase(Locale.ROOT));
            minecraft.getTextureManager().register(location, texture);
            LOADED.put(file, location);
            SIZES.put(file, new int[]{width, height, cap});
            return location;
        } catch (Exception error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[acechat] 读取死亡覆盖图 " + file + " 失败", error);
            }

            return null;
        }
    }

    /** 已加载的死亡覆盖图尺寸（宽, 高, 上限）。 */
    public static int[] sizeOf(String file) {
        return SIZES.getOrDefault(file, new int[]{1, 1, 256});
    }
}
