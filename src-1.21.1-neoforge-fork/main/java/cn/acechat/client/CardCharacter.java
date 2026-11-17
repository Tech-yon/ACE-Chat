package cn.acechat.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 卡的「角色身份」：卡文件里的 {@code character} 子对象。
 *
 * <pre>
 * "character": {
 *   "displayName": "队长",
 *   "boundPlayer": false,
 *   "skin": "slim/队长.png",
 *   "portrait": "队长立绘.png"
 * }
 * </pre>
 *
 * <p>它不参与“选卡应用配置”，只被 HUD/演出用来解析名字、皮肤、立绘。
 * 旧卡没有该对象时按默认值：displayName=卡名、boundPlayer=true、皮肤/立绘为空。
 */
public final class CardCharacter {
    private static final java.util.Map<String, Cached> CACHE = new java.util.HashMap<>();
    /** 服务端下发的角色（只在内存，不落盘）。 */
    private static final java.util.Map<String, Data> REMOTE = new java.util.concurrent.ConcurrentHashMap<>();
    private record Cached(long stamp, Data data) {
    }

    /** 角色身份数据。 */
    public static final class Data {
        public String displayName = "";
        public boolean boundPlayer = true;
        public String skin = "";
        public String portrait = "";

        public boolean hasSkin() {
            return skin != null && !skin.isBlank();
        }

        public boolean hasPortrait() {
            return portrait != null && !portrait.isBlank();
        }
    }

    /** 读一张卡的 character；没有则返回默认值（不修改文件）。带 mtime 缓存。 */
    public static Data read(String cardName) {
        java.nio.file.Path file = CardFiles.fileOf(cardName);
        long stamp = file != null && java.nio.file.Files.isRegularFile(file)
                ? file.toFile().lastModified() : -1L;
        Cached cached = CACHE.get(cardName);
        if (cached != null && cached.stamp() == stamp) {
            return cached.data();
        }
        Data data = readUncached(cardName);
        CACHE.put(cardName, new Cached(stamp, data));
        return data;
    }

    /** 记录服务端下发的角色身份。 */
    public static void putRemote(String cardName, Data data) {
        if (cardName != null && data != null) {
            REMOTE.put(cardName, data);
        }
    }

    /** 演出用：服务端下发优先，其次本地卡。 */
    public static Data readStage(String cardName) {
        if (preferLocal(cardName)) {
            return read(cardName);   // 本机作者：永远用最新本地卡（编辑后立刻同步到演出）
        }
        Data remote = REMOTE.get(cardName);
        return remote != null ? remote : read(cardName);
    }

    /**
     * 单人/局域网主机的“作者本机”：本地卡文件优先于服务端下发的副本。
     * 否则演出会一直用进服时下发的旧卡（改皮肤/立绘/显示名都不生效）。
     */
    public static boolean preferLocal(String cardName) {
        if (cardName == null || cardName.isBlank()) {
            return false;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null || !minecraft.hasSingleplayerServer()) {
            return false;
        }
        java.nio.file.Path file = CardFiles.fileOf(cardName);
        return file != null && java.nio.file.Files.isRegularFile(file);
    }

    public static boolean hasRemote(String cardName) {
        return REMOTE.containsKey(cardName);
    }

    public static void clearRemote() {
        REMOTE.clear();
    }

    /** 清掉身份缓存（改卡/换卡后调用）。 */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Data readUncached(String cardName) {
        Data data = new Data();
        JsonObject root = CardFiles.read(cardName);
        if (root == null || !root.has("character") || !root.get("character").isJsonObject()) {
            return data;
        }
        JsonObject character = root.getAsJsonObject("character");
        data.displayName = str(character, "displayName");
        if (character.has("boundPlayer")) {
            try {
                data.boundPlayer = character.get("boundPlayer").getAsBoolean();
            } catch (RuntimeException ignored) {
                data.boundPlayer = true;
            }
        }
        data.skin = str(character, "skin");
        data.portrait = str(character, "portrait");
        return data;
    }

    /** 写回一张卡的 character。 */
    public static boolean write(String cardName, Data data) {
        JsonObject root = CardFiles.read(cardName);
        if (root == null) {
            return false;
        }
        JsonObject character = new JsonObject();
        character.addProperty("displayName", data.displayName == null ? "" : data.displayName.trim());
        character.addProperty("boundPlayer", data.boundPlayer);
        character.addProperty("skin", data.skin == null ? "" : data.skin.trim());
        character.addProperty("portrait", data.portrait == null ? "" : data.portrait.trim());
        root.add("character", character);
        return CardFiles.write(cardName, root);
    }

    /** HUD 显示名：优先 character.displayName，否则卡名。 */
    public static String displayNameOf(String cardName) {
        Data data = read(cardName);
        return data.displayName == null || data.displayName.isBlank() ? cardName : data.displayName;
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private CardCharacter() {
    }
}
