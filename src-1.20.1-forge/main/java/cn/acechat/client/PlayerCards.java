package cn.acechat.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 角色卡：以玩家为单位保存「文字元素 / 玩家名字颜色 / 半身像 HUD（含立绘与死亡覆盖）/ 叠加滤镜 / 调色」。
 *
 * <p>存储：{@code config/acechat/cards/<玩家名>.json}，默认卡是 {@code _default.json}；
 * 每张卡是一份完整配置快照，应用后由 {@link CommsConfig} 用全局项覆盖（聊天框、音效、
 * 显示开关、过滤名单、素材目录等仍为全局设置，不随卡改变）。
 *
 * <p>卡按玩家名对照：玩家退出后卡保留在本地，下次上线自动套用；没有专属卡的玩家一律使用默认卡。
 */
public final class PlayerCards {
    /** 默认卡的名字（一级菜单第一项）。 */
    public static final String DEFAULT_CARD = "_default";

    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<String> cachedCards;

    private PlayerCards() {
    }

    public static Path dir() {
        return AceChatFolders.cardsDir();
    }

    /** 把玩家名变成安全的文件名（去掉路径分隔符等）。 */
    public static String fileNameOf(String cardName) {
        String name = cardName == null ? DEFAULT_CARD : cardName.trim();
        if (name.isEmpty()) {
            name = DEFAULT_CARD;
        }

        return name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
    }

    public static Path fileOf(String cardName) {
        return dir().resolve(fileNameOf(cardName));
    }

    public static boolean exists(String cardName) {
        return Files.isRegularFile(fileOf(cardName));
    }

    /** 内存里的卡名集合判定（每帧调用也不碰磁盘）。 */
    public static boolean existsCached(String cardName) {
        return cardName != null && list().contains(cardName);
    }

    /** 本地已有的卡名（不含 .json，默认卡排最前）。 */
    public static List<String> list() {
        if (cachedCards != null) {
            return cachedCards;
        }

        List<String> result = new ArrayList<>();
        try {
            Path dir = dir();
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                            .sorted()
                            .forEach(p -> {
                                String file = p.getFileName().toString();
                                result.add(file.substring(0, file.length() - 5));
                            });
                }
            }
        } catch (Exception error) {
            LOGGER.warn("[acechat] 读取角色卡目录失败", error);
        }

        if (!result.contains(DEFAULT_CARD)) {
            result.add(0, DEFAULT_CARD);
        } else {
            result.remove(DEFAULT_CARD);
            result.add(0, DEFAULT_CARD);
        }

        cachedCards = result;
        return result;
    }

    public static void rescan() {
        cachedCards = null;
    }

    /** 读一张卡（不存在返回 null）。 */
    public static JsonObject read(String cardName) {
        try {
            Path path = fileOf(cardName);
            if (!Files.isRegularFile(path)) {
                return null;
            }

            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            LOGGER.warn("[acechat] 读取角色卡 " + cardName + " 失败", error);
            return null;
        }
    }

    /** 写一张卡（内容由调用方给出，通常是当前配置快照）。 */
    public static boolean write(String cardName, JsonObject data) {
        try {
            Path path = fileOf(cardName);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
            rescan();
            return true;
        } catch (Exception error) {
            LOGGER.warn("[acechat] 写入角色卡 " + cardName + " 失败", error);
            return false;
        }
    }

    public static boolean delete(String cardName) {
        try {
            if (DEFAULT_CARD.equals(cardName)) {
                return false;   // 默认卡不允许删除
            }

            Files.deleteIfExists(fileOf(cardName));
            rescan();
            return true;
        } catch (Exception error) {
            LOGGER.warn("[acechat] 删除角色卡 " + cardName + " 失败", error);
            return false;
        }
    }

    /** 把 from 卡的内容复制成 to 卡（to 已存在则不覆盖，返回是否成功）。 */
    public static boolean copyCard(String from, String to) {
        if (to == null || to.isEmpty() || exists(to)) {
            return false;
        }

        JsonObject source = read(from);
        if (source == null) {
            return false;
        }

        return write(to, source.deepCopy());
    }

    /** 把默认卡复制成某玩家的专属卡（已存在则不覆盖）。 */
    public static void createFromDefault(String cardName) {
        if (cardName == null || cardName.isEmpty() || exists(cardName)) {
            return;
        }

        JsonObject base = read(DEFAULT_CARD);
        if (base == null) {
            return;
        }

        write(cardName, base.deepCopy());
    }

    /** 当前在线的玩家名（一级菜单里「当前在线」分组用）。 */
    public static List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return names;
        }

        for (net.minecraft.world.entity.player.Player player : minecraft.level.players()) {
            String name = player.getGameProfile().getName();
            if (name != null && !name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * 把一张卡改名（卡文件也跟着改名）。
     *
     * @return 成功 true；新名为空、与旧名相同、新名已存在、默认卡、或源卡读不到 → false
     */
    public static boolean rename(String from, String to) {
        String oldName = from == null ? "" : from.trim();
        String newName = to == null ? "" : to.trim();
        if (oldName.isEmpty() || newName.isEmpty() || oldName.equals(newName) || DEFAULT_CARD.equals(oldName)) {
            return false;
        }

        if (exists(newName)) {
            return false;
        }

        JsonObject data = read(oldName);
        if (data == null || !write(newName, data)) {
            return false;
        }

        delete(oldName);
        rescan();
        return true;
    }

    /** 角色卡的「导出/导入」目录：config/acechat/export/cards/（不往 cards/ 塞副本，免得污染卡列表）。 */
    public static Path exportDir() {
        return dir().getParent().resolve("export").resolve("cards");
    }

    /**
     * 把一张卡导出成一个独立 json 文件（默认写到 {@link #exportDir()}），方便直接拷给别人。
     *
     * @return 写好的文件路径；读不到卡或写失败返回 null
     */
    public static Path exportTo(String cardName, Path targetDir) {
        JsonObject data = read(cardName);
        if (data == null) {
            return null;
        }

        try {
            Path dir = targetDir == null ? exportDir() : targetDir;
            Files.createDirectories(dir);
            Path file = dir.resolve(fileNameOf(cardName));
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            return file;
        } catch (Exception error) {
            LOGGER.warn("[acechat] 导出角色卡 " + cardName + " 失败", error);
            return null;
        }
    }

    /**
     * 从<b>选中的那个文件</b>导入一张角色卡（卡名 = 文件名去掉 .json）。
     *
     * @return 成功 true；文件不存在、名字为空、同名卡已存在、内容不是合法 json → false
     */
    public static boolean importFile(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return false;
            }

            String fileName = file.getFileName().toString();
            String cardName = fileName.toLowerCase(Locale.ROOT).endsWith(".json")
                    ? fileName.substring(0, fileName.length() - ".json".length()).trim()
                    : fileName.trim();
            if (cardName.isEmpty() || exists(cardName)) {
                return false;
            }

            JsonObject data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return write(cardName, data);
        } catch (Exception error) {
            LOGGER.warn("[acechat] 导入角色卡文件失败: " + file, error);
            return false;
        }
    }

    /** 旧的整目录导入（保留给脚本/兼容用，界面上已改成"选一个文件导入"）。 */
    public static int[] importFrom(Path sourceDir) {
        int imported = 0;
        int skipped = 0;
        Path dir = sourceDir == null ? exportDir() : sourceDir;
        if (!Files.isDirectory(dir)) {
            return new int[]{0, 0};
        }

        try {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .forEach(files::add);
            }

            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String cardName = fileName.substring(0, fileName.length() - ".json".length()).trim();
                if (cardName.isEmpty() || exists(cardName)) {
                    skipped++;
                    continue;
                }

                try {
                    JsonObject data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (write(cardName, data)) {
                        imported++;
                    } else {
                        skipped++;
                    }
                } catch (Exception error) {
                    skipped++;
                }
            }
        } catch (Exception error) {
            LOGGER.warn("[acechat] 导入角色卡失败", error);
        }

        rescan();
        return new int[]{imported, skipped};
    }
}

