package cn.acechat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件组里的「触发器」：每条可设为「自动触发」（按事件参数判定）或「快捷键触发」（按键直接播）。
 * 自动触发时多条之间用 与/或 组合，每条可「非」。
 *
 * <pre>
 * "when": { "triggers": [
 *     { "event": "entity.hurt", "mode": "auto" },
 *     { "event": "player.lowHealth", "below": 0.3, "op": "any", "negate": true, "mode": "auto" },
 *     { "event": "entity.spawn", "mode": "hotkey", "hotkey": "key.keyboard.j" }
 * ], "window": 200 }
 * </pre>
 */
public final class StageTriggers {
    public record Item(int index, String event, String op, boolean negate, String mode, String hotkey, JsonObject leaf) {
        public boolean manual() {
            return "hotkey".equals(mode);
        }
    }

    private static final int DEFAULT_WINDOW_TICKS = 200;

    public static List<Item> list(String project, String ruleName) {
        List<Item> items = new ArrayList<>();
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return items;
        }
        JsonArray array = triggers(rule);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String op = object.has("op") ? object.get("op").getAsString() : "all";
            boolean negate = object.has("negate") && object.get("negate").getAsBoolean();
            String event = object.has("event") ? object.get("event").getAsString() : "";
            String mode = object.has("mode") ? object.get("mode").getAsString() : "auto";
            String hotkey = object.has("hotkey") ? object.get("hotkey").getAsString() : "";
            items.add(new Item(i, event, op, negate, mode, hotkey, object.deepCopy()));
        }
        return items;
    }

    public static boolean add(String project, String ruleName) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return false;
        }
        JsonArray array = triggers(rule);
        JsonObject trigger = new JsonObject();
        trigger.addProperty("event", "entity.hurt");
        trigger.addProperty("mode", "auto");
        if (!array.isEmpty()) {
            trigger.addProperty("op", "all");
        }
        array.add(trigger);
        return StageProjects.saveRule(project, ruleName, rule);
    }

    public static boolean remove(String project, String ruleName, int index) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return false;
        }
        JsonArray array = triggers(rule);
        if (index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return StageProjects.saveRule(project, ruleName, rule);
    }

    public static boolean setOp(String project, String ruleName, int index, String op) {
        return edit(project, ruleName, index, object -> object.addProperty("op", op));
    }

    public static boolean setNegate(String project, String ruleName, int index, boolean negate) {
        return edit(project, ruleName, index, object -> object.addProperty("negate", negate));
    }

    public static boolean setMode(String project, String ruleName, int index, String mode) {
        return edit(project, ruleName, index, object -> object.addProperty("mode", mode));
    }

    /** 绑定/清除这条触发器自己的快捷键。 */
    public static boolean setTriggerHotkey(String project, String ruleName, int index, String hotkey) {
        return edit(project, ruleName, index, object -> {
            if (hotkey == null || hotkey.isBlank()) {
                object.remove("hotkey");
            } else {
                object.addProperty("hotkey", hotkey.trim());
            }
        });
    }

    /** 保存某条的参数（保留 op / negate / mode / hotkey）。 */
    public static boolean save(String project, String ruleName, int index, JsonObject leaf) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        JsonArray array = rule == null ? null : triggers(rule);
        if (array == null || index < 0 || index >= array.size()) {
            return false;
        }
        JsonObject existing = array.get(index).getAsJsonObject();
        JsonObject target = leaf.deepCopy();
        for (String key : new String[]{"op", "negate", "mode", "hotkey"}) {
            if (existing.has(key)) {
                target.add(key, existing.get(key));
            }
        }
        array.set(index, target);
        return StageProjects.saveRule(project, ruleName, rule);
    }

    public static int windowSeconds(String project, String ruleName) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        JsonObject when = rule == null ? null : when(rule);
        int ticks = when != null && when.has("window") ? when.get("window").getAsInt() : DEFAULT_WINDOW_TICKS;
        return Math.max(0, ticks / 20);
    }

    public static boolean setWindowSeconds(String project, String ruleName, int seconds) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return false;
        }
        JsonObject when = when(rule);
        when.addProperty("window", Math.max(0, seconds) * 20);
        return StageProjects.saveRule(project, ruleName, rule);
    }

    public static String expression(String project, String ruleName) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        JsonObject when = rule == null ? null : when(rule);
        return when != null && when.has("expr") ? when.get("expr").getAsString() : "";
    }

    public static boolean setExpression(String project, String ruleName, String expr) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return false;
        }
        JsonObject when = when(rule);
        if (expr == null || expr.isBlank()) {
            when.remove("expr");
        } else {
            when.addProperty("expr", expr.trim());
        }
        return StageProjects.saveRule(project, ruleName, rule);
    }

    // ── 内部 ──

    private interface Edit {
        void apply(JsonObject object);
    }

    private static boolean edit(String project, String ruleName, int index, Edit edit) {
        JsonObject rule = StageProjects.rule(project, ruleName);
        if (rule == null) {
            return false;
        }
        JsonArray array = triggers(rule);
        if (index < 0 || index >= array.size()) {
            return false;
        }
        edit.apply(array.get(index).getAsJsonObject());
        return StageProjects.saveRule(project, ruleName, rule);
    }

    private static JsonObject when(JsonObject rule) {
        JsonElement element = rule.get("when");
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        rule.add("when", created);
        return created;
    }

    /** 取触发器数组；旧格式（all/any/单叶子）自动迁移成 triggers。 */
    private static JsonArray triggers(JsonObject rule) {
        JsonObject when = when(rule);
        JsonElement existing = when.get("triggers");
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        String mode = "all";
        JsonElement group = null;
        if (when.has("any") && when.get("any").isJsonArray()) {
            mode = "any";
            group = when.get("any");
        } else if (when.has("all") && when.get("all").isJsonArray()) {
            group = when.get("all");
        }
        if (group != null) {
            int index = 0;
            for (JsonElement child : group.getAsJsonArray()) {
                if (!child.isJsonObject()) {
                    continue;
                }
                JsonObject object = child.getAsJsonObject().deepCopy();
                boolean negate = false;
                if (object.has("not") && object.get("not").isJsonObject()) {
                    object = object.getAsJsonObject("not").deepCopy();
                    negate = true;
                }
                object.addProperty("op", index == 0 ? "all" : mode);
                object.addProperty("negate", negate);
                array.add(object);
                index++;
            }
            when.remove("all");
            when.remove("any");
        } else if (when.has("event")) {
            JsonObject object = when.deepCopy();
            JsonObject leaf = new JsonObject();
            for (String key : new String[]{"event", "entity", "keyword", "dimension", "below", "area"}) {
                if (object.has(key)) {
                    leaf.add(key, object.get(key));
                }
            }
            leaf.addProperty("op", "all");
            leaf.addProperty("negate", false);
            array.add(leaf);
            for (String key : new String[]{"event", "entity", "keyword", "dimension", "below", "area"}) {
                when.remove(key);
            }
        }
        when.add("triggers", array);
        return array;
    }

    private StageTriggers() {
    }
}
