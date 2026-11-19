package cn.acechat.network;

import cn.acechat.AceChat;
import cn.acechat.client.StageScripts;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：播一段已经解析好的演出（服务端算抽组/一次性，客户端只负责播）。
 *
 * <p>包体是一个 JSON 字符串（≤16KB）：
 * <pre>
 * {"project":"第一章","group":"g2","rule":"r_ambush","loop":false,
 *  "lines":[{"speaker":"A","text":"c1","hold":40,"wait":false}, ...]}
 * </pre>
 */
public record StagePayload(String json) implements CustomPacketPayload {
    public static final Type<StagePayload> TYPE = new Type<>(AceChat.id("stage"));

    public static final StreamCodec<ByteBuf, StagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StagePayload::json, StagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 顺位：把多个组合并成一条时间线（每组的 start 依次偏移）。 */
    public static StagePayload ofSequence(String project, java.util.List<StageScripts.Group> groups,
                                          String rule, boolean loop) {
        JsonObject root = new JsonObject();
        root.addProperty("project", project == null ? "" : project);
        root.addProperty("group", "*sequence*");
        root.addProperty("rule", rule == null ? "" : rule);
        root.addProperty("loop", loop);
        JsonArray lines = new JsonArray();
        int offset = 0;
        int total = 0;
        for (StageScripts.Group group : groups) {
            if (group == null) {
                continue;
            }
            int groupEnd = 0;
            for (StageScripts.Line line : group.lines()) {
                if (total >= 128) {
                    break;
                }
                JsonObject object = new JsonObject();
                object.addProperty("speaker", line.speaker());
                String text = line.text();
                if (text.length() > 256) {
                    text = text.substring(0, 256);
                }
                object.addProperty("text", text);
                object.addProperty("hold", line.holdTick());
                object.addProperty("start", offset + line.startTick());
                object.addProperty("wait", line.waitForNext());
                lines.add(object);
                total++;
                groupEnd = Math.max(groupEnd, line.startTick() + line.holdTick());
            }
            offset += groupEnd;
        }
        root.add("lines", lines);
        return build(root, lines);
    }

    /** 单包 JSON 的 UTF-8 上限（1.21.1 ByteBufCodecs.STRING_UTF8 是 32767，这里留余量）。 */
    private static StagePayload build(JsonObject root, JsonArray lines) {
        boolean truncated = false;
        while (lines.size() > 0
                && root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 30000) {
            lines.remove(lines.size() - 1);
            truncated = true;
        }
        if (truncated) {
            root.addProperty("truncated", true);
            org.slf4j.LoggerFactory.getLogger("acechat")
                    .warn("[acechat] 演出台词超过单包上限，已截断到 {} 句", lines.size());
        }
        return new StagePayload(root.toString());
    }

    /** 把一组台词打成包体。 */
    public static StagePayload of(String project, StageScripts.Group group, String rule, boolean loop) {
        JsonObject root = new JsonObject();
        root.addProperty("project", project == null ? "" : project);
        root.addProperty("group", group.id());
        root.addProperty("rule", rule == null ? "" : rule);
        root.addProperty("loop", loop);
        JsonArray lines = new JsonArray();
        int total = 0;
        for (StageScripts.Line line : group.lines()) {
            if (total >= 64) {
                break;   // 单包上限：64 句
            }
            JsonObject object = new JsonObject();
            object.addProperty("speaker", line.speaker());
            String text = line.text();
            if (text.length() > 256) {
                text = text.substring(0, 256);
            }
            object.addProperty("text", text);
            object.addProperty("hold", line.holdTick());
            object.addProperty("start", line.startTick());
            object.addProperty("wait", line.waitForNext());
            lines.add(object);
            total++;
        }
        root.add("lines", lines);
        return build(root, lines);
    }
}
