package cn.acechat.server;

import cn.acechat.client.CardAssets;
import cn.acechat.client.CardCharacter;
import cn.acechat.client.CardFiles;
import cn.acechat.client.StageScripts;
import cn.acechat.network.AssetChunkPayload;
import cn.acechat.network.AssetManifestPayload;
import cn.acechat.network.AssetRequestPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端侧的「角色资源下发」：把工程里用到的角色卡身份 + 皮肤/立绘按需发给客户端。
 *
 * <p>客户端不需要工程文件；资源只在客户端内存缓存，不落盘。
 */
public final class ServerAssets {
    private static final int CHUNK = 100 * 1024;
    private static final int MAX_KEYS_PER_REQUEST = 32;

    /** 进服时发送清单（角色身份 + 文件哈希）。 */
    public static void sendManifest(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Set<String> speakers = collectSpeakers();
        JsonArray cards = new JsonArray();
        Map<String, String> files = new LinkedHashMap<>();
        for (String speaker : speakers) {
            if (!CardFiles.exists(speaker) && !CardCharacter.hasRemote(speaker)) {
                continue;
            }
            CardCharacter.Data character = CardCharacter.read(speaker);
            JsonObject card = new JsonObject();
            card.addProperty("id", speaker);
            JsonObject object = new JsonObject();
            object.addProperty("displayName", character.displayName == null ? "" : character.displayName);
            object.addProperty("boundPlayer", character.boundPlayer);
            object.addProperty("skin", character.skin == null ? "" : character.skin);
            object.addProperty("portrait", character.portrait == null ? "" : character.portrait);
            card.add("character", object);
            cards.add(card);

            addFile(files, "skin", character.skin);
            addFile(files, "portrait", character.portrait);
        }
        if (cards.isEmpty() && files.isEmpty()) {
            return;
        }
        JsonObject root = new JsonObject();
        root.add("cards", cards);
        JsonArray fileArray = new JsonArray();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            JsonObject file = new JsonObject();
            file.addProperty("kind", parts[0]);
            file.addProperty("id", parts[1]);
            file.addProperty("sha", entry.getValue());
            fileArray.add(file);
        }
        root.add("files", fileArray);
        try {
            PacketDistributor.sendToPlayer(player, new AssetManifestPayload(root.toString()));
        } catch (RuntimeException ignored) {
            // 通道不可用
        }
    }

    /** 客户端请求资源：按块发回。 */
    public static void handleRequest(AssetRequestPayload payload, ServerPlayer player) {
        if (payload == null || player == null || payload.json() == null) {
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(payload.json()).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (!root.has("keys") || !root.get("keys").isJsonArray()) {
            return;
        }
        int count = 0;
        for (var element : root.getAsJsonArray("keys")) {
            if (count++ >= MAX_KEYS_PER_REQUEST) {
                break;
            }
            String key = element.getAsString();
            String[] parts = key.split("\\|", 3);
            if (parts.length < 3) {
                continue;
            }
            String kind = parts[0];
            String id = parts[1];
            String sha = parts[2];
            Path file = fileOf(kind, id);
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.size(file) > CardAssets.HARD_MAX_BYTES) {
                    continue;   // 先看大小，不要把超大文件读进内存
                }
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > CardAssets.HARD_MAX_BYTES) {
                    continue;
                }
                int total = Math.max(1, (bytes.length + CHUNK - 1) / CHUNK);
                for (int i = 0; i < total; i++) {
                    int from = i * CHUNK;
                    int to = Math.min(bytes.length, from + CHUNK);
                    byte[] part = java.util.Arrays.copyOfRange(bytes, from, to);
                    PacketDistributor.sendToPlayer(player, new AssetChunkPayload(kind, id, sha, i, total, part));
                }
            } catch (Exception ignored) {
                // 读失败就跳过这个资源
            }
        }
    }

    private static Path fileOf(String kind, String id) {
        if (kind == null || id == null || id.isBlank() || id.indexOf('\0') >= 0) {
            return null;
        }
        if ("skin".equals(kind)) {
            String name = id;
            if (name.startsWith(CardAssets.SLIM + "/")) {
                return safeResolve(CardAssets.skinsSlimDir(), name.substring(CardAssets.SLIM.length() + 1));
            }
            if (name.startsWith(CardAssets.CLASSIC + "/")) {
                return safeResolve(CardAssets.skinsClassicDir(), name.substring(CardAssets.CLASSIC.length() + 1));
            }
            return null;
        }
        if ("portrait".equals(kind)) {
            if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) {
                return null;
            }
            return safeResolve(CardAssets.portraitsDir(), id);
        }
        return null;
    }

    /** 目录内解析：拒绝绝对路径/..，normalize 后必须仍在 base 下。 */
    private static Path safeResolve(Path base, String relative) {
        if (base == null || relative == null || relative.isBlank()
                || relative.indexOf('/') >= 0 || relative.indexOf('\\') >= 0) {
            return null;
        }
        try {
            Path root = base.toAbsolutePath().normalize();
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            return candidate;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static void addFile(Map<String, String> files, String kind, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        String key = kind + "|" + id;
        if (files.containsKey(key)) {
            return;
        }
        Path file = fileOf(kind, id);
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Files.size(file) > CardAssets.HARD_MAX_BYTES) {
                return;
            }
            files.put(key, sha1(Files.readAllBytes(file)));
        } catch (Exception ignored) {
            // 读失败就跳过
        }
    }

    private static Set<String> collectSpeakers() {
        Set<String> speakers = new LinkedHashSet<>();
        try {
            Path dir = StageScripts.dir();
            if (dir == null || !Files.isDirectory(dir)) {
                return speakers;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                        .forEach(path -> {
                            try {
                                StageScripts.Project project = StageScripts.parseProject(
                                        Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
                                for (StageScripts.Group group : project.groups().values()) {
                                    for (StageScripts.Line line : group.lines()) {
                                        if (line.speaker() != null && !line.speaker().isBlank()) {
                                            speakers.add(line.speaker());
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // 坏文件跳过
                            }
                        });
            }
        } catch (Exception ignored) {
            // 目录读不了就当没有
        }
        return speakers;
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(data);
            StringBuilder text = new StringBuilder();
            for (byte b : hash) {
                text.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private ServerAssets() {
    }
}
