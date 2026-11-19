package cn.acechat.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 事件组（rule）的播放控制字段读写：玩法、可见性、一次性、优先级、半径、全局区块半径、重置。
 *
 * <p>字段映射：
 * <ul>
 *   <li>{@code pick}: sequential=顺位 / random=随机</li>
 *   <li>{@code target}: self=个人化 / nearby=群体</li>
 *   <li>{@code once}: perPlayer=一次性 / 空=多次</li>
 *   <li>{@code priority}: high / normal / low</li>
 *   <li>{@code radius}: 0=跟随工程默认，>0=自定义区块数</li>
 *   <li>工程根 {@code chunkRadius}: 群体播放默认半径（区块）</li>
 * </ul>
 */
public final class StageRules {
    public static final int DEFAULT_CHUNKS = 4;

    // ── 事件组字段 ──

    public static String pick(String project, String rule) {
        return str(rule(project, rule), "pick", "random");
    }

    public static boolean setPick(String project, String rule, String pick) {
        return put(project, rule, object -> object.addProperty("pick", pick));
    }

    public static String target(String project, String rule) {
        return str(rule(project, rule), "target", "self");
    }

    public static boolean setTarget(String project, String rule, String target) {
        return put(project, rule, object -> object.addProperty("target", target));
    }

    public static String once(String project, String rule) {
        return str(rule(project, rule), "once", "");
    }

    public static boolean setOnce(String project, String rule, String once) {
        return put(project, rule, object -> {
            if (once == null || once.isBlank()) {
                object.remove("once");
            } else {
                object.addProperty("once", once);
            }
        });
    }

    public static String priority(String project, String rule) {
        return str(rule(project, rule), "priority", "normal");
    }

    public static boolean setPriority(String project, String rule, String priority) {
        return put(project, rule, object -> object.addProperty("priority", priority));
    }

    public static int radius(String project, String rule) {
        JsonObject object = rule(project, rule);
        if (object == null || !object.has("radius")) {
            return 0;
        }
        return Math.max(0, object.get("radius").getAsInt());
    }

    public static boolean setRadius(String project, String rule, int chunks) {
        return put(project, rule, object -> object.addProperty("radius", Math.max(0, Math.min(64, chunks))));
    }

    // ── 工程级 ──

    public static int chunkRadius(String project) {
        JsonObject root = StageProjects.readProject(project);
        if (root != null && root.has("chunkRadius")) {
            return Math.max(1, Math.min(64, root.get("chunkRadius").getAsInt()));
        }
        return DEFAULT_CHUNKS;
    }

    public static boolean setChunkRadius(String project, int chunks) {
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return false;
        }
        root.addProperty("chunkRadius", Math.max(1, Math.min(64, chunks)));
        return StageProjects.writeProject(project, root);
    }

    public static boolean resetPlayer(String project, String rule) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new cn.acechat.network.ResetPayload(project, rule));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── 内部 ──

    private static JsonObject rule(String project, String rule) {
        return StageProjects.rule(project, rule);
    }

    private static String str(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        return element.isJsonNull() ? fallback : element.getAsString();
    }

    private interface Edit {
        void apply(JsonObject object);
    }

    private static boolean put(String project, String ruleName, Edit edit) {
        JsonObject object = rule(project, ruleName);
        if (object == null) {
            return false;
        }
        edit.apply(object);
        return StageProjects.saveRule(project, ruleName, object);
    }

    private StageRules() {
    }
}
