package cn.acechat.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 卡牌本地资源：皮肤（slim / classic 两个目录）与立绘目录的扫描与校验。
 *
 * <p>皮肤引用格式：{@code "slim/队长.png"}（文件夹 + 文件名），模型由文件夹决定。
 * 超尺寸/超大小不再拒绝，而是标记 {@code needsScale}，加载时由程序缩小；
 * 只有“不是 PNG / 超过 8MB 硬上限 / 读不了”才算不可用。
 */
public final class CardAssets {
    public static final int SKIN_MAX_SIDE = 512;
    public static final int SKIN_TARGET_BYTES = 64 * 1024;
    public static final int PORTRAIT_MAX_SIDE = 2048;
    public static final int PORTRAIT_TARGET_BYTES = 512 * 1024;
    public static final int PORTRAIT_TARGET_SIDE = 1024;
    /** 硬上限：超过这个大小直接判为不可用（防止解码时内存爆掉）。 */
    public static final long HARD_MAX_BYTES = 8L * 1024 * 1024;

    public static final String SLIM = "slim";
    public static final String CLASSIC = "classic";

    /** 一个可选资源。reference 形如 {@code slim/队长.png}（立绘为 {@code 队长.png}）。 */
    public record Entry(String reference, String folder, String fileName, boolean slim,
                        boolean usable, boolean needsScale, String note) {
    }

    public static Path configDir() {
        Path cards = CardFiles.dir();
        return cards == null ? null : cards.getParent();
    }

    public static Path skinsSlimDir() {
        return configDir() == null ? null : configDir().resolve("skins_slim");
    }

    public static Path skinsClassicDir() {
        return configDir() == null ? null : configDir().resolve("skins_classic");
    }

    public static Path portraitsDir() {
        return configDir() == null ? null : configDir().resolve("portraits");
    }

    /** 扫两个皮肤目录；先 slim 后 classic。 */
    public static List<Entry> skins() {
        List<Entry> list = new ArrayList<>();
        scan(skinsSlimDir(), SLIM, list);
        scan(skinsClassicDir(), CLASSIC, list);
        return list;
    }

    public static List<Entry> portraits() {
        List<Entry> list = new ArrayList<>();
        scan(portraitsDir(), null, list);
        return list;
    }

    /** 皮肤文件检查：返回 null 表示能用（可能需要缩放），否则返回不可用原因。 */
    public static String checkSkin(Path file) {
        return check(file, SKIN_MAX_SIDE, SKIN_TARGET_BYTES);
    }

    /** 立绘文件检查。 */
    public static String checkPortrait(Path file) {
        return check(file, PORTRAIT_MAX_SIDE, PORTRAIT_TARGET_BYTES);
    }

    /** 是否需要程序缩小。 */
    public static boolean needsScale(Path file, boolean skin) {
        try {
            long size = Files.size(file);
            int[] dimensions = pngSize(file);
            if (dimensions == null) {
                return false;
            }
            int maxSide = skin ? SKIN_MAX_SIDE : PORTRAIT_MAX_SIDE;
            long maxBytes = skin ? SKIN_TARGET_BYTES : PORTRAIT_TARGET_BYTES;
            return dimensions[0] > maxSide || dimensions[1] > maxSide || size > maxBytes;
        } catch (IOException e) {
            return false;
        }
    }

    private static void scan(Path dir, String folder, List<Entry> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String error = folder == null ? checkPortrait(path) : checkSkin(path);
                        String reference = folder == null ? name : folder + "/" + name;
                        boolean usable = error == null;
                        boolean scale = usable && needsScale(path, folder != null);
                        String note = error != null ? error : (scale ? "将自动缩小到上限内" : "");
                        out.add(new Entry(reference, folder, name, SLIM.equals(folder), usable, scale, note));
                    });
        } catch (IOException ignored) {
            // 目录读不了就当空的
        }
    }

    private static String check(Path file, int maxSide, long maxBytes) {
        try {
            long size = Files.size(file);
            if (size <= 0) {
                return "空文件";
            }
            if (size > HARD_MAX_BYTES) {
                return "文件超过 8MB 硬上限";
            }
            int[] dimensions = pngSize(file);
            if (dimensions == null) {
                return "不是有效的 PNG";
            }
            if (dimensions[0] <= 0 || dimensions[1] <= 0) {
                return "尺寸读取失败";
            }
            return null;   // 超尺寸/超大小不拒绝，加载时缩小
        } catch (IOException e) {
            return "读不了文件";
        }
    }

    /** 只读 PNG 头（签名 + IHDR）拿宽高，不整图解码。 */
    private static int[] pngSize(Path file) throws IOException {
        byte[] header = new byte[33];
        try (var in = Files.newInputStream(file)) {
            int read = in.readNBytes(header, 0, header.length);
            if (read < 33) {
                return null;
            }
        }
        if ((header[0] & 0xFF) != 0x89 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
            return null;
        }
        if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            return null;
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        return new int[]{width, height};
    }

    private CardAssets() {
    }
}
