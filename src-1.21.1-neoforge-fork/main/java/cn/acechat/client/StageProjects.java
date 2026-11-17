package cn.acechat.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件组（rules[]）的文件级编辑：新建 / 删除 / 重命名 / 复制 / 读取 / 保存。
 *
 * <p>事件组是工程文件里的 {@code rules[]}；每个事件组 = 名字（id）+ 触发条件（when）+ 执行参数
 * （target / radius / once / cooldown / play / pick）。这里只做 JSON 读写，界面调用这些方法。
 */
public final class StageProjects {
    private static final com.google.gson.Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 列出工程里的事件组名字（按文件顺序）。 */
    public static List<String> ruleIds(String project) {
        List<String> ids = new ArrayList<>();
        JsonArray rules = rules(read(project));
        if (rules != null) {
            for (JsonElement element : rules) {
                if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                    ids.add(element.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        return ids;
    }

    /** 新建一个事件组（默认顺序播工程第一组，无事件条件）。 */
    public static boolean addRule(String project, String name) {
        if (name != null && "canvas".equalsIgnoreCase(name.trim())) {
            return false;   // canvas 是画布保留 id
        }
        if (name == null || name.isBlank()) {
            return false;
        }
        JsonObject root = read(project);
        if (root == null) {
            return false;
        }
        JsonArray rules = ensureRules(root);
        if (find(rules, name) != null) {
            return false;
        }
        String firstGroup = firstGroupId(root);
        JsonObject rule = new JsonObject();
        rule.addProperty("id", name);
        JsonArray play = new JsonArray();
        play.add(firstGroup);
        rule.add("play", play);
        rule.addProperty("pick", "sequential");
        rule.addProperty("target", "nearby");
        rule.addProperty("radius", 32);
        rule.addProperty("once", "perPlayer");
        rule.addProperty("cooldown", 600);
        rules.add(rule);
        return write(project, root);
    }

    public static boolean removeRule(String project, String name) {
        JsonObject root = read(project);
        if (root == null) {
            return false;
        }
        JsonArray rules = rules(root);
        if (rules == null) {
            return false;
        }
        boolean removed = false;
        for (int i = rules.size() - 1; i >= 0; i--) {
            JsonElement element = rules.get(i);
            if (element.isJsonObject() && name.equals(str(element.getAsJsonObject(), "id"))) {
                rules.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        if (root.has("canvases") && root.get("canvases").isJsonObject()) {
            root.getAsJsonObject("canvases").remove(name);
        }
        String prefix = groupPrefix(name);
        String legacy = "cv_" + (name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "_"));
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            JsonArray groups = root.getAsJsonArray("groups");
            for (int i = groups.size() - 1; i >= 0; i--) {
                JsonElement element = groups.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                String id = element.getAsJsonObject().has("id")
                        ? element.getAsJsonObject().get("id").getAsString() : "";
                if (id.equals(prefix) || id.startsWith(prefix + "_")
                        || id.equals(legacy) || id.startsWith(legacy + "_")) {
                    groups.remove(i);
                }
            }
        }
        return write(project, root);
    }

    private static String groupPrefix(String id) {
        String raw = id == null ? "" : id;
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
        return "cv_" + safe + "_" + String.format("%08x", raw.hashCode());
    }

    /** 把画布键、cv_ 组 id、rule.play 一起改名。 */
    private static void moveCanvas(JsonObject root, String from, String to) {
        String oldPrefix = groupPrefix(from);
        String newPrefix = groupPrefix(to);
        if (root.has("canvases") && root.get("canvases").isJsonObject()) {
            JsonObject all = root.getAsJsonObject("canvases");
            if (all.has(from)) {
                JsonElement canvas = all.get(from);
                all.remove(from);
                all.add(to, canvas);
            }
        }
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("groups")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject group = element.getAsJsonObject();
                String id = group.has("id") ? group.get("id").getAsString() : "";
                if (id.equals(oldPrefix) || id.startsWith(oldPrefix + "_")) {
                    group.addProperty("id", newPrefix + id.substring(oldPrefix.length()));
                }
            }
        }
        for (String key : new String[]{"rules"}) {
            if (!root.has(key) || !root.getAsJsonArray(key).isJsonArray()) {
                continue;
            }
            for (JsonElement element : root.getAsJsonArray(key)) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject rule = element.getAsJsonObject();
                if (!rule.has("play") || !rule.get("play").isJsonArray()) {
                    continue;
                }
                JsonArray play = rule.getAsJsonArray("play");
                for (int i = 0; i < play.size(); i++) {
                    String id = play.get(i).getAsString();
                    if (id.equals(oldPrefix) || id.startsWith(oldPrefix + "_")) {
                        play.set(i, new com.google.gson.JsonPrimitive(newPrefix + id.substring(oldPrefix.length())));
                    }
                }
            }
        }
    }

    public static boolean renameRule(String project, String from, String to) {
        if (to == null || to.isBlank() || from == null) {
            return false;
        }
        JsonObject root = read(project);
        if (root == null) {
            return false;
        }
        JsonArray rules = rules(root);
        if (rules == null || find(rules, to) != null) {
            return false;
        }
        JsonObject rule = find(rules, from);
        if (rule == null) {
            return false;
        }
        rule.addProperty("id", to);
        moveCanvas(root, from, to);
        return write(project, root);
    }

    public static boolean copyRule(String project, String from, String to) {
        if (to == null || to.isBlank() || from == null) {
            return false;
        }
        JsonObject root = read(project);
        if (root == null) {
            return false;
        }
        JsonArray rules = ensureRules(root);
        if (find(rules, to) != null) {
            return false;
        }
        JsonObject source = find(rules, from);
        if (source == null) {
            return false;
        }
        JsonObject copy = source.deepCopy();
        copy.addProperty("id", to);
        rules.add(copy);
        // 复制画布与它自己的组（cv_from* → cv_to*）
        String oldPrefix = groupPrefix(from);
        String newPrefix = groupPrefix(to);
        if (root.has("canvases") && root.get("canvases").isJsonObject()) {
            JsonObject all = root.getAsJsonObject("canvases");
            if (all.has(from)) {
                all.add(to, all.get(from).deepCopy());
            }
        }
        if (copy.has("play") && copy.get("play").isJsonArray()) {
            JsonArray play = copy.getAsJsonArray("play");
            for (int i = 0; i < play.size(); i++) {
                String id = play.get(i).getAsString();
                if (id.equals(oldPrefix) || id.startsWith(oldPrefix + "_")) {
                    play.set(i, new com.google.gson.JsonPrimitive(newPrefix + id.substring(oldPrefix.length())));
                }
            }
        }
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            JsonArray groups = root.getAsJsonArray("groups");
            List<JsonObject> extra = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                JsonElement element = groups.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject group = element.getAsJsonObject();
                String id = group.has("id") ? group.get("id").getAsString() : "";
                if (id.equals(oldPrefix) || id.startsWith(oldPrefix + "_")) {
                    JsonObject dup = group.deepCopy();
                    dup.addProperty("id", newPrefix + id.substring(oldPrefix.length()));
                    extra.add(dup);
                }
            }
            for (JsonObject group : extra) {
                groups.add(group);
            }
        }
        return write(project, root);
    }

    /** 读一个事件组的字段（副本）；不存在返回 null。 */
    /** 事件组会播的台词组 id 列表。 */
    public static java.util.List<String> playGroups(String project, String name) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        JsonObject rule = rule(project, name);
        if (rule != null && rule.has("play") && rule.get("play").isJsonArray()) {
            for (com.google.gson.JsonElement element : rule.getAsJsonArray("play")) {
                ids.add(element.getAsString());
            }
        }
        return ids;
    }
    public static JsonObject rule(String project, String name) {
        JsonArray rules = rules(read(project));
        JsonObject rule = rules == null ? null : find(rules, name);
        return rule == null ? null : rule.deepCopy();
    }

    /** 用更新后的字段覆盖回工程文件（按 id 匹配）。 */
    public static boolean saveRule(String project, String name, JsonObject updated) {
        JsonObject root = read(project);
        if (root == null) {
            return false;
        }
        JsonArray rules = rules(root);
        if (rules == null) {
            return false;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonElement element = rules.get(i);
            if (element.isJsonObject() && name.equals(str(element.getAsJsonObject(), "id"))) {
                rules.set(i, updated);
                return write(project, root);
            }
        }
        return false;
    }

    // ── 内部 ──

    /** 读工程文件的 JSON 根（编辑器/快捷键索引用）。 */
    public static JsonObject readProject(String project) {
        return read(project);
    }

    private static JsonObject read(String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        try {
            Path file = StageScripts.dir().resolve(project + ".json");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(StageScripts.stripComments(
                    Files.readString(file, StandardCharsets.UTF_8)));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 供时间线/规则编辑写回工程文件。 */
    public static boolean writeProject(String project, JsonObject root) {
        return write(project, root);
    }
    private static boolean write(String project, JsonObject root) {
        try {
            Files.createDirectories(StageScripts.dir());
            Files.writeString(StageScripts.dir().resolve(project + ".json"),
                    GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonArray rules(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonElement element = root.get("rules");
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static JsonArray ensureRules(JsonObject root) {
        JsonElement element = root.get("rules");
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        JsonArray created = new JsonArray();
        root.add("rules", created);
        return created;
    }

    private static JsonObject find(JsonArray rules, String name) {
        if (rules == null || name == null) {
            return null;
        }
        for (JsonElement element : rules) {
            if (element.isJsonObject() && name.equals(str(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static String firstGroupId(JsonObject root) {
        JsonElement groups = root == null ? null : root.get("groups");
        if (groups != null && groups.isJsonArray()) {
            for (JsonElement element : groups.getAsJsonArray()) {
                if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                    return element.getAsJsonObject().get("id").getAsString();
                }
            }
        }
        return "g1";
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private StageProjects() {
    }
}
