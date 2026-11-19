package cn.acechat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的「服务端资源缓存」：清单 → 按需请求 → 分块拼装 → 解码进纹理缓存。
 * 全部只在内存，不写磁盘；断线清空。
 */
public final class ClientAssets {
    private static final Map<String, byte[]> BYTES = new ConcurrentHashMap<>();
    private static final Map<String, String> SHA = new ConcurrentHashMap<>();
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private static final class Pending {
        final byte[][] chunks;
        int received;

        Pending(int total) {
            chunks = new byte[total][];
        }
    }

    /** 已下载的原始字节；没有则 null。 */
    public static byte[] bytesFor(String kind, String id) {
        return BYTES.get(kind + "|" + id);
    }

    public static void clear() {
        BYTES.clear();
        SHA.clear();
        PENDING.clear();
        CardCharacter.clearRemote();
    }

    /** 服务端清单：登记角色身份，并请求缺少的图片。 */
    public static void handleManifest(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (root.has("cards") && root.get("cards").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("cards")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject card = element.getAsJsonObject();
                String id = card.has("id") ? card.get("id").getAsString() : "";
                if (id.isEmpty() || !card.has("character") || !card.get("character").isJsonObject()) {
                    continue;
                }
                JsonObject character = card.getAsJsonObject("character");
                CardCharacter.Data data = new CardCharacter.Data();
                data.displayName = character.has("displayName") ? character.get("displayName").getAsString() : "";
                data.boundPlayer = !character.has("boundPlayer") || character.get("boundPlayer").getAsBoolean();
                data.skin = character.has("skin") ? character.get("skin").getAsString() : "";
                data.portrait = character.has("portrait") ? character.get("portrait").getAsString() : "";
                CardCharacter.putRemote(id, data);
            }
        }
        List<String> missing = new ArrayList<>();
        if (root.has("files") && root.get("files").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("files")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                String kind = file.has("kind") ? file.get("kind").getAsString() : "";
                String id = file.has("id") ? file.get("id").getAsString() : "";
                String sha = file.has("sha") ? file.get("sha").getAsString() : "";
                if (kind.isEmpty() || id.isEmpty() || sha.isEmpty()) {
                    continue;
                }
                if (sha.equals(SHA.get(kind + "|" + id))) {
                    continue;
                }
                missing.add(kind + "|" + id + "|" + sha);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        JsonObject request = new JsonObject();
        JsonArray keys = new JsonArray();
        for (String key : missing) {
            keys.add(key);
        }
        request.add("keys", keys);
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new cn.acechat.network.AssetRequestPayload(request.toString()));
        } catch (RuntimeException ignored) {
            // 服务端没装/通道不可用
        }
    }

    /** 分块到达：拼装完成后校验并解码。 */
    public static void handleChunk(cn.acechat.network.AssetChunkPayload payload) {
        if (payload == null || payload.data() == null || payload.total() <= 0) {
            return;
        }
        String key = payload.kind() + "|" + payload.id();
        String shaKey = key + "|" + payload.sha();
        Pending pending = PENDING.computeIfAbsent(shaKey, ignored -> new Pending(payload.total()));
        if (payload.index() < 0 || payload.index() >= pending.chunks.length) {
            return;
        }
        if (pending.chunks[payload.index()] == null) {
            pending.received++;
        }
        pending.chunks[payload.index()] = payload.data();
        if (pending.received < pending.chunks.length) {
            return;
        }
        PENDING.remove(shaKey);
        int size = 0;
        for (byte[] chunk : pending.chunks) {
            size += chunk == null ? 0 : chunk.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (byte[] chunk : pending.chunks) {
            if (chunk != null) {
                out.writeBytes(chunk);
            }
        }
        byte[] bytes = out.toByteArray();
        if (!payload.sha().equalsIgnoreCase(sha1(bytes))) {
            return;   // 校验失败：丢弃，下次清单再请求
        }
        BYTES.put(key, bytes);
        SHA.put(key, payload.sha());
        CardTextures.addRemote(payload.kind(), payload.id(), bytes);
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
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private ClientAssets() {
    }
}
