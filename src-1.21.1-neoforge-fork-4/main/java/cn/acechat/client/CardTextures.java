package cn.acechat.client;

import cn.acechat.AceChat;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 卡的皮肤 / 立绘纹理：读 png → 需要时缩小 → 注册动态纹理 → LRU 缓存。
 *
 * <p>上限 36MB（按像素字节估算），正在显示的纹理会被 pin 住不释放。
 * 皮肤保持原尺寸（≤512）；立绘缩到 ≤1024（显示性能）。
 */
public final class CardTextures {
    private static final long LIMIT_BYTES = 36L * 1024 * 1024;
    private static final int PORTRAIT_MAX = CardAssets.PORTRAIT_TARGET_SIDE;
    private static final int SKIN_MAX = CardAssets.SKIN_MAX_SIDE;

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Set<String> PINNED = new HashSet<>();
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final long FAIL_TTL_MS = 30_000L;
    private static long totalBytes;
    private static int counter;

    private record Entry(ResourceLocation id, long bytes, long lastUsed, int width, int height, long stamp) {
    }

    /** 皮肤纹理；失败返回 null。ref 形如 {@code slim/队长.png}。 */
    public static ResourceLocation skin(String reference) {
        return load("skin:" + reference, reference, SKIN_MAX, true);
    }

    /** 立绘纹理；失败返回 null。 */
    public static ResourceLocation portrait(String reference) {
        return load("portrait:" + reference, reference, PORTRAIT_MAX, false);
    }

    /** 该资源的像素尺寸（用于立绘按比例绘制）；没有则 {1,1}。 */
    /** 皮肤/立绘文件的修改时间（毫秒）；读不到返回 -1。 */
    private static long fileStamp(String reference) {
        java.io.File file = resolveFile(reference);
        return file == null ? -1L : file.lastModified();
    }

    public static int[] dimensions(String reference, boolean skin) {
        String kind = skin ? "skin" : "portrait";
        Entry entry = CACHE.get(kind + ":" + reference);
        if (entry == null) {
            entry = CACHE.get(kind + "|" + reference);   // 服务端下发的缓存键
        }
        return entry == null ? new int[]{1, 1} : new int[]{entry.width(), entry.height()};
    }

    public static void pin(ResourceLocation id) {
        for (Entry entry : CACHE.values()) {
            if (entry.id().equals(id)) {
                PINNED.add(entry.id().toString());
                return;
            }
        }
    }

    public static void unpinAll() {
        PINNED.clear();
    }

    public static void clearAll() {
        for (Entry entry : CACHE.values()) {
            release(entry);
        }
        CACHE.clear();
        PINNED.clear();
        FAILED.clear();
        totalBytes = 0;
    }

    /** 每帧/每次使用后调一次：释放最久没用的，直到回到上限内。 */
    public static void trim() {
        if (totalBytes <= LIMIT_BYTES) {
            return;
        }
        List<Map.Entry<String, Entry>> entries = new ArrayList<>(CACHE.entrySet());
        entries.sort(java.util.Comparator.comparingLong(entry -> entry.getValue().lastUsed()));
        for (Map.Entry<String, Entry> item : entries) {
            if (totalBytes <= LIMIT_BYTES) {
                break;
            }
            Entry entry = item.getValue();
            if (PINNED.contains(entry.id().toString())) {
                continue;
            }
            release(entry);
            CACHE.remove(item.getKey());
        }
    }

    /** 服务端下发：从原始 png 字节加载（内存缓存，不落盘）。 */
    public static ResourceLocation addRemote(String kind, String id, byte[] bytes) {
        String key = kind + ":" + id;
        Entry cached = CACHE.get(key);
        if (cached != null) {
            CACHE.put(key, new Entry(cached.id(), cached.bytes(), System.currentTimeMillis(),
                    cached.width(), cached.height(), cached.stamp()));
            return cached.id();
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            int maxSide = "skin".equals(kind) ? SKIN_MAX : PORTRAIT_MAX;
            int width = image.getWidth();
            int height = image.getHeight();
            BufferedImage target = image;
            if (width > maxSide || height > maxSide) {
                double scale = Math.min(maxSide / (double) width, maxSide / (double) height);
                int newWidth = Math.max(1, (int) Math.round(width * scale));
                int newHeight = Math.max(1, (int) Math.round(height * scale));
                target = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = target.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
                graphics.dispose();
                width = newWidth;
                height = newHeight;
            }
            NativeImage nativeImage = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = target.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int a = (argb >>> 24) & 0xFF;
                    nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            ResourceLocation textureId = AceChat.id("card_remote/" + (counter++));
            DynamicTexture texture = new DynamicTexture(nativeImage);
            texture.setFilter(false, false);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            long size = (long) width * height * 4L;
            CACHE.put(key, new Entry(textureId, size, System.currentTimeMillis(), width, height, -1L));
            totalBytes += size;
            trim();
            return textureId;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static ResourceLocation load(String key, String reference, int maxSide, boolean skin) {
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        long stamp = fileStamp(reference);
        if (cached != null && cached.stamp() == stamp) {
            CACHE.put(key, new Entry(cached.id(), cached.bytes(), now, cached.width(), cached.height(), stamp));
            return cached.id();
        }
        if (cached != null) {
            release(cached);
            CACHE.remove(key);
        }
        Long failedAt = FAILED.get(key);
        if (failedAt != null && now - failedAt < FAIL_TTL_MS) {
            return null;
        }
        if (reference == null || reference.isBlank()) {
            return null;
        }
        File file = resolveFile(reference);
        if (file == null || !file.isFile()) {
            FAILED.put(key, now);
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                FAILED.put(key, now);
                return null;
            }
            BufferedImage target = image;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width > maxSide || height > maxSide) {
                double scale = Math.min(maxSide / (double) width, maxSide / (double) height);
                int newWidth = Math.max(1, (int) Math.round(width * scale));
                int newHeight = Math.max(1, (int) Math.round(height * scale));
                target = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = target.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
                graphics.dispose();
                width = newWidth;
                height = newHeight;
            }
            NativeImage nativeImage = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = target.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int a = (argb >>> 24) & 0xFF;
                    nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            ResourceLocation id = AceChat.id("card/" + (counter++));
            DynamicTexture texture = new DynamicTexture(nativeImage);
            texture.setFilter(false, false);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            long bytes = (long) width * height * 4L;
            CACHE.put(key, new Entry(id, bytes, now, width, height, stamp));
            totalBytes += bytes;
            trim();
            return id;
        } catch (IOException | RuntimeException e) {
            FAILED.put(key, now);
            return null;
        }
    }

    private static File resolveFile(String reference) {
        String name = reference;
        boolean slim = false;
        if (reference.startsWith(CardAssets.SLIM + "/")) {
            slim = true;
            name = reference.substring(CardAssets.SLIM.length() + 1);
        } else if (reference.startsWith(CardAssets.CLASSIC + "/")) {
            name = reference.substring(CardAssets.CLASSIC.length() + 1);
        }
        java.nio.file.Path dir = slim ? CardAssets.skinsSlimDir()
                : reference.startsWith(CardAssets.CLASSIC + "/") ? CardAssets.skinsClassicDir()
                : CardAssets.portraitsDir();
        if (dir == null) {
            return null;
        }
        return dir.resolve(name).toFile();
    }

    private static void release(Entry entry) {
        try {
            Minecraft.getInstance().getTextureManager().release(entry.id());
        } catch (RuntimeException ignored) {
            // 纹理可能已经被释放
        }
        totalBytes -= entry.bytes();
    }

    private CardTextures() {
    }
}
