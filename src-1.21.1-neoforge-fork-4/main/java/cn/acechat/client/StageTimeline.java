package cn.acechat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间线编辑：对话组（= 工程里的 groups）与组内对话（= lines）。
 *
 * <p>每句只有 {@code hold}（持续时间）与隐含的 {@code start}（开始时刻）；
 * “下一句停滞时长” = 下一句 start − (本句 start + hold)，保存时把整组 start 重算一遍。
 */
public final class StageTimeline {
    public static final int DEFAULT_HOLD_TICKS = 40;   // 2 秒
    public static final int MIN_HOLD_TICKS = 10;       // 0.5 秒

    // ── 对话组 ──

    /** 该事件组会播的对话组（按 play 顺序）。 */
    public static List<String> groupsOf(String project, String rule) {
        return StageProjects.playGroups(project, rule);
    }

    /** 新建一个空对话组（含一句默认台词），并追加到该事件组的 play 列表。 */
    public static boolean addGroup(String project, String rule, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return false;
        }
        JsonArray groups = groups(root);
        for (JsonElement element : groups) {
            if (element.isJsonObject() && name.equals(str(element.getAsJsonObject(), "id"))) {
                return false;
            }
        }
        JsonObject group = new JsonObject();
        group.addProperty("id", name);
        JsonArray lines = new JsonArray();
        JsonObject line = new JsonObject();
        line.addProperty("speaker", "");
        line.addProperty("text", "新台词");
        line.addProperty("start", 0);
        line.addProperty("hold", DEFAULT_HOLD_TICKS);
        line.addProperty("wait", false);
        lines.add(line);
        group.add("lines", lines);
        groups.add(group);
        appendPlay(root, rule, name);
        return StageProjects.writeProject(project, root);
    }

    public static boolean deleteGroup(String project, String rule, String groupId) {
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return false;
        }
        boolean changed = false;
        JsonArray groups = groups(root);
        for (int i = groups.size() - 1; i >= 0; i--) {
            JsonElement element = groups.get(i);
            if (element.isJsonObject() && groupId.equals(str(element.getAsJsonObject(), "id"))) {
                groups.remove(i);
                changed = true;
            }
        }
        // 不管组在不在，都把这个名字从所有规则的 play 里清掉（修孤立引用）
        JsonElement rules = root.get("rules");
        if (rules != null && rules.isJsonArray()) {
            for (JsonElement element : rules.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject ruleObject = element.getAsJsonObject();
                JsonElement play = ruleObject.get("play");
                if (play == null || !play.isJsonArray()) {
                    continue;
                }
                JsonArray array = play.getAsJsonArray();
                for (int i = array.size() - 1; i >= 0; i--) {
                    if (groupId.equals(array.get(i).getAsString())) {
                        array.remove(i);
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return false;
        }
        return StageProjects.writeProject(project, root);
    }

    /** 该对话组是否真的存在于工程里（不存在=孤立引用）。 */
    public static boolean groupExists(String project, String groupId) {
        return group(project, groupId) != null;
    }

    public static boolean renameGroup(String project, String rule, String oldId, String newId) {
        if (newId == null || newId.isBlank() || oldId == null) {
            return false;
        }
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return false;
        }
        JsonArray groups = groups(root);
        JsonObject target = null;
        for (JsonElement element : groups) {
            if (element.isJsonObject()) {
                String id = str(element.getAsJsonObject(), "id");
                if (newId.equals(id) && !oldId.equals(newId)) {
                    return false;
                }
                if (oldId.equals(id)) {
                    target = element.getAsJsonObject();
                }
            }
        }
        if (target == null) {
            return false;
        }
        target.addProperty("id", newId);
        replacePlay(root, rule, oldId, newId);
        return StageProjects.writeProject(project, root);
    }

    /** 在事件组的 play 列表里移动一组；delta = -1 上移 / +1 下移。 */
    public static boolean moveGroup(String project, String rule, String groupId, int delta) {
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return false;
        }
        JsonArray play = playArray(root, rule);
        if (play == null) {
            return false;
        }
        for (int i = 0; i < play.size(); i++) {
            if (groupId.equals(play.get(i).getAsString())) {
                int target = i + delta;
                if (target < 0 || target >= play.size()) {
                    return false;
                }
                String value = play.get(i).getAsString();
                List<String> ids = new ArrayList<>();
                for (JsonElement element : play) {
                    ids.add(element.getAsString());
                }
                ids.remove(i);
                ids.add(target, value);
                while (play.size() > 0) {
                    play.remove(0);
                }
                for (String id : ids) {
                    play.add(id);
                }
                return StageProjects.writeProject(project, root);
            }
        }
        return false;
    }

    // ── 组内对话 ──

    public static List<StageScripts.Line> lines(String project, String groupId) {
        List<StageScripts.Line> result = new ArrayList<>();
        JsonObject group = group(project, groupId);
        if (group == null || !group.has("lines") || !group.get("lines").isJsonArray()) {
            return result;
        }
        for (JsonElement element : group.getAsJsonArray("lines")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject line = element.getAsJsonObject();
            result.add(new StageScripts.Line(str(line, "speaker"), str(line, "text"),
                    line.has("start") ? line.get("start").getAsInt() : 0,
                    line.has("hold") ? line.get("hold").getAsInt() : DEFAULT_HOLD_TICKS,
                    line.has("wait") && line.get("wait").getAsBoolean(), str(line, "sound"), str(line, "portrait")));
        }
        return result;
    }

    public static boolean addLine(String project, String groupId) {
        JsonObject root = StageProjects.readProject(project);
        JsonObject group = group(root, groupId);
        if (group == null) {
            return false;
        }
        JsonArray lines = linesOf(group);
        JsonObject line = new JsonObject();
        line.addProperty("speaker", "");
        line.addProperty("text", "新台词");
        line.addProperty("hold", DEFAULT_HOLD_TICKS);
        line.addProperty("wait", false);
        lines.add(line);
        recompute(root, group);
        return StageProjects.writeProject(project, root);
    }

    public static boolean deleteLine(String project, String groupId, int index) {
        JsonObject root = StageProjects.readProject(project);
        JsonObject group = group(root, groupId);
        if (group == null) {
            return false;
        }
        JsonArray lines = linesOf(group);
        if (index < 0 || index >= lines.size()) {
            return false;
        }
        lines.remove(index);
        recompute(root, group);
        return StageProjects.writeProject(project, root);
    }

    public static boolean moveLine(String project, String groupId, int index, int delta) {
        JsonObject root = StageProjects.readProject(project);
        JsonObject group = group(root, groupId);
        if (group == null) {
            return false;
        }
        JsonArray lines = linesOf(group);
        int target = index + delta;
        if (index < 0 || index >= lines.size() || target < 0 || target >= lines.size()) {
            return false;
        }
        List<JsonElement> all = new ArrayList<>();
        for (JsonElement element : lines) {
            all.add(element);
        }
        JsonElement moved = all.remove(index);
        all.add(target, moved);
        while (lines.size() > 0) {
            lines.remove(0);
        }
        for (JsonElement element : all) {
            lines.add(element);
        }
        recompute(root, group);
        return StageProjects.writeProject(project, root);
    }

    /** 某句与下句之间的停滞（tick）。 */
    public static int gapTicks(List<StageScripts.Line> lines, int index) {
        if (index < 0 || index + 1 >= lines.size()) {
            return 0;
        }
        StageScripts.Line line = lines.get(index);
        StageScripts.Line next = lines.get(index + 1);
        return Math.max(0, next.startTick() - (line.startTick() + line.holdTick()));
    }

    /** 保存一句：speaker/text/hold/gap/wait，并把整组 start 重算。 */
    public static boolean setLine(String project, String groupId, int index, String speaker, String text,
                                  int holdTicks, int gapTicks, boolean wait) {
        JsonObject root = StageProjects.readProject(project);
        JsonObject group = group(root, groupId);
        if (group == null) {
            return false;
        }
        JsonArray lines = linesOf(group);
        if (index < 0 || index >= lines.size()) {
            return false;
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            JsonObject line = lines.get(i).getAsJsonObject();
            int start = line.has("start") ? line.get("start").getAsInt() : 0;
            int hold = line.has("hold") ? line.get("hold").getAsInt() : DEFAULT_HOLD_TICKS;
            int nextStart = i + 1 < lines.size()
                    ? (lines.get(i + 1).getAsJsonObject().has("start")
                        ? lines.get(i + 1).getAsJsonObject().get("start").getAsInt() : start + hold)
                    : start + hold;
            gaps.add(Math.max(0, nextStart - (start + hold)));
        }
        gaps.set(index, Math.max(0, gapTicks));
        JsonObject line = lines.get(index).getAsJsonObject();
        line.addProperty("speaker", speaker == null ? "" : speaker);
        line.addProperty("text", text == null ? "" : text);
        line.addProperty("hold", Math.max(MIN_HOLD_TICKS, holdTicks));
        line.addProperty("wait", wait);
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            JsonObject current = lines.get(i).getAsJsonObject();
            current.addProperty("start", start);
            int hold = current.has("hold") ? current.get("hold").getAsInt() : DEFAULT_HOLD_TICKS;
            start += hold + gaps.get(i);
        }
        return StageProjects.writeProject(project, root);
    }

    /** 组总时长（tick）：最后一句结束时刻。 */
    public static int durationTicks(List<StageScripts.Line> lines) {
        int end = 0;
        for (StageScripts.Line line : lines) {
            end = Math.max(end, line.startTick() + line.holdTick());
        }
        return Math.max(0, end);
    }

    // ── 内部 ──

    private static void recompute(JsonObject root, JsonObject group) {
        JsonArray lines = linesOf(group);
        List<Integer> gaps = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            JsonObject line = lines.get(i).getAsJsonObject();
            int hold = line.has("hold") ? line.get("hold").getAsInt() : DEFAULT_HOLD_TICKS;
            int oldStart = line.has("start") ? line.get("start").getAsInt() : start;
            int oldNext = i + 1 < lines.size() && lines.get(i + 1).getAsJsonObject().has("start")
                    ? lines.get(i + 1).getAsJsonObject().get("start").getAsInt() : oldStart + hold;
            gaps.add(Math.max(0, oldNext - (oldStart + hold)));
        }
        for (int i = 0; i < lines.size(); i++) {
            JsonObject line = lines.get(i).getAsJsonObject();
            line.addProperty("start", start);
            start += (line.has("hold") ? line.get("hold").getAsInt() : DEFAULT_HOLD_TICKS) + gaps.get(i);
        }
    }

    private static JsonObject group(String project, String groupId) {
        return group(StageProjects.readProject(project), groupId);
    }

    private static JsonObject group(JsonObject root, String groupId) {
        if (root == null) {
            return null;
        }
        for (JsonElement element : groups(root)) {
            if (element.isJsonObject() && groupId.equals(str(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonArray groups(JsonObject root) {
        JsonElement element = root.get("groups");
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        JsonArray created = new JsonArray();
        root.add("groups", created);
        return created;
    }

    private static JsonArray linesOf(JsonObject group) {
        JsonElement element = group.get("lines");
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        JsonArray created = new JsonArray();
        group.add("lines", created);
        return created;
    }

    private static JsonArray playArray(JsonObject root, String rule) {
        JsonObject ruleObject = findRule(root, rule);
        if (ruleObject == null) {
            return null;
        }
        JsonElement element = ruleObject.get("play");
        if (element == null || !element.isJsonArray()) {
            JsonArray created = new JsonArray();
            ruleObject.add("play", created);
            return created;
        }
        return element.getAsJsonArray();
    }

    private static JsonObject findRule(JsonObject root, String rule) {
        JsonElement rules = root.get("rules");
        if (rules == null || !rules.isJsonArray()) {
            return null;
        }
        for (JsonElement element : rules.getAsJsonArray()) {
            if (element.isJsonObject() && rule.equals(str(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static void appendPlay(JsonObject root, String rule, String groupId) {
        JsonArray play = playArray(root, rule);
        if (play == null) {
            return;
        }
        for (JsonElement element : play) {
            if (groupId.equals(element.getAsString())) {
                return;
            }
        }
        play.add(groupId);
    }

    private static void removePlay(JsonObject root, String rule, String groupId) {
        JsonArray play = playArray(root, rule);
        if (play == null) {
            return;
        }
        for (int i = play.size() - 1; i >= 0; i--) {
            if (groupId.equals(play.get(i).getAsString())) {
                play.remove(i);
            }
        }
    }

    private static void replacePlay(JsonObject root, String rule, String oldId, String newId) {
        JsonArray play = playArray(root, rule);
        if (play == null) {
            return;
        }
        for (int i = 0; i < play.size(); i++) {
            if (oldId.equals(play.get(i).getAsString())) {
                play.set(i, new com.google.gson.JsonPrimitive(newId));
            }
        }
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private StageTimeline() {
    }
}
