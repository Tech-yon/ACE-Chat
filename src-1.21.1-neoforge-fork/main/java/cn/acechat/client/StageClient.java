package cn.acechat.client;

import cn.acechat.network.StagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** 客户端：收到服务端下发的演出包 → 转成台词行 → 交给 {@link StagePlayer} 播。 */
public final class StageClient {
    /** 收到包后给客户端 5 tick 的预解码窗口（第一句在这之后开始）。 */
    private static final int LEAD_IN = 5;
    public static void apply(StagePayload payload) {
        if (payload == null || payload.json() == null || payload.json().isBlank()) {
            return;
        }
        try {
            JsonElement rootElement = JsonParser.parseString(payload.json());
            if (!rootElement.isJsonObject()) {
                return;
            }
            JsonObject root = rootElement.getAsJsonObject();
            String project = root.has("project") ? root.get("project").getAsString() : "";
            String group = root.has("group") ? root.get("group").getAsString() : "";
            String rule = root.has("rule") ? root.get("rule").getAsString() : "";
            boolean loop = root.has("loop") && root.get("loop").getAsBoolean();
            List<StageScripts.Line> lines = new ArrayList<>();
            if (root.has("lines") && root.get("lines").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("lines");
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject line = element.getAsJsonObject();
                    String speaker = line.has("speaker") ? line.get("speaker").getAsString() : "";
                    String text = line.has("text") ? line.get("text").getAsString() : "";
                    int hold = line.has("hold") ? line.get("hold").getAsInt() : 50;
                    int start = line.has("start") ? line.get("start").getAsInt() : 0;
                    boolean wait = line.has("wait") && line.get("wait").getAsBoolean();
                    if (speaker.isEmpty() || text.isEmpty()) {
                        continue;
                    }
                    lines.add(new StageScripts.Line(speaker, text, start + LEAD_IN, Math.max(4, hold), wait, "", ""));
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            // 预解码本次演出会用到的角色卡皮肤/立绘（最多 12 个说话人）
            warmup(lines);
            StagePlayer.playReceived(Minecraft.getInstance(), project, group, rule, loop, lines);
        } catch (RuntimeException ignored) {
            // 包体坏了：静默忽略，绝不让客户端崩
        }
    }


    /** 预解码：把这张卡的脸/立绘提前加载进纹理缓存。 */
    private static void warmup(java.util.List<cn.acechat.client.StageScripts.Line> lines) {
        java.util.Set<String> speakers = new java.util.LinkedHashSet<>();
        for (cn.acechat.client.StageScripts.Line line : lines) {
            if (line.speaker() != null && !line.speaker().isBlank()) {
                speakers.add(line.speaker());
            }
            if (speakers.size() >= 12) {
                break;
            }
        }
        for (String speaker : speakers) {
            boolean remote = !cn.acechat.client.CardCharacter.preferLocal(speaker)
                    && cn.acechat.client.CardCharacter.hasRemote(speaker);
            if (!remote && !cn.acechat.client.PlayerCards.exists(speaker)) {
                continue;
            }
            cn.acechat.client.CardCharacter.Data character = remote
                    ? cn.acechat.client.CardCharacter.readStage(speaker)
                    : cn.acechat.client.CardCharacter.read(speaker);
            if (character.hasSkin() && !character.boundPlayer) {
                if (remote) {
                    byte[] bytes = cn.acechat.client.ClientAssets.bytesFor("skin", character.skin);
                    if (bytes != null) {
                        cn.acechat.client.CardTextures.addRemote("skin", character.skin, bytes);
                    }
                    // 远端资源还没到：跳过（等资源清单触发下载），不要去读本地不存在的文件
                } else {
                    cn.acechat.client.CardTextures.skin(character.skin);
                }
            }
            if (character.hasPortrait()) {
                if (remote) {
                    byte[] bytes = cn.acechat.client.ClientAssets.bytesFor("portrait", character.portrait);
                    if (bytes != null) {
                        cn.acechat.client.CardTextures.addRemote("portrait", character.portrait, bytes);
                    }
                } else {
                    cn.acechat.client.CardTextures.portrait(character.portrait);
                }
            }
        }
    }    private StageClient() {
    }
}
