package cn.acechat.client;

import com.google.gson.Gson;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 剧本演出 · 工程文件解析（实验版）。
 *
 * <p>一个 {@code ACE_config/stage/<工程名>.json} = 一个工程：
 * <pre>
 * {
 *   "version": 1,
 *   "name": "第一章 · 遭遇",
 *   "type": "sequential",              // 仅界面预设：sequential / random
 *   "loop": false,
 *   "groups": [
 *     { "id": "g1", "lines": [ { "speaker": "A", "text": "a1", "hold": 50 } ] },
 *     { "id": "g2", "lines": [ { "speaker": "A", "text": "c1", "hold": 40 },
 *                              { "speaker": "B", "text": "a2", "at": 20, "hold": 40, "wait": true } ] }
 *   ],
 *   "rules": [
 *     { "id": "r_manual", "play": ["g1"], "pick": "sequential" },
 *     { "id": "r_ambush", "play": ["g1","g2"], "pick": "random", "weights": [3,1], "noRepeat": true }
 *   ]
 * }
 * </pre>
 *
 * <p>兼容旧格式：根节点只有 {@code lines} 时，视为单组 {@code default} + 单规则 {@code default}。
 * 时间单位统一 tick（20 tick = 1 秒）。
 */
public final class StageScripts {
    /** 句子：startTick 为相对「组开始」的偏移。 */
    public record Line(String speaker, String text, int startTick, int holdTick,
                       boolean waitForNext, String sound, String portrait) {
    }

    /** 组：一次播放单元，可多句、可跨角色。 */
    public record Group(String id, List<Line> lines) {
    }

    /**
     * 规则：什么时候播 + 取哪些组 + 怎么取。
     *
     * <p>{@code whenJson} 保留原始条件 JSON（由 {@code cn.acechat.stage.StageEngine} 解析）；
     * 其余字段是执行参数：窗口 tick、广播范围、半径、圆心、一次性粒度、记录位置、冷却秒。
     */
    public record Rule(String id, List<String> play, String pick, List<Integer> weights, boolean noRepeat,
                       String whenJson, int window, String target, int radius, String center,
                       String once, String state, int cooldown, String priority, boolean repeat) {
        /** 优先级：high=2 / normal=1 / low=0。 */
        public int priorityRank() {
            return switch (priority == null ? "normal" : priority) {
                case "high" -> 2;
                case "low" -> 0;
                default -> 1;
            };
        }

        /** 只看取组方式时的便捷构造（界面 / 无头测试用）。 */
        public Rule(String id, List<String> play, String pick, List<Integer> weights, boolean noRepeat) {
            this(id, play, pick, weights, noRepeat, "", 200, "nearby", 32, "victim",
                "", "perWorld", 0, "normal", false);
        }
    }

    /** 工程。 */
    public record Project(String name, String type, boolean loop,
                          Map<String, Group> groups, List<Rule> rules, List<String> warnings,
                          int chunkRadius) {
        public Group group(String id) {
            return groups.get(id);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_HOLD_TICKS = 50;

    public static Path dir() {
        return AceChatFolders.rootDir().resolve("stage");
    }

    public static Project load(String name) throws IOException {
        Path file = dir().resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IOException("找不到工程: " + file);
        }
        Project parsed = parseProject(Files.readString(file, StandardCharsets.UTF_8));
        if (parsed.name() == null || parsed.name().isBlank()) {
            return new Project(name, parsed.type(), parsed.loop(), parsed.groups(), parsed.rules(), parsed.warnings(), parsed.chunkRadius());
        }
        return parsed;
    }

    /** 纯解析（无文件 IO），便于无头测试。 */
    public static Project parseProject(String json) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(stripComments(json));
            if (!parsed.isJsonObject()) {
                throw new IOException("工程根节点必须是对象");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("工程不是合法 JSON: " + e.getMessage(), e);
        }

        List<String> warnings = new ArrayList<>();
        String name = string(root, "name");
        String type = string(root, "type");
        boolean loop = bool(root, "loop", false);
        int chunkRadius = Math.max(1, Math.min(64, number(root, "chunkRadius", 4)));

        Map<String, Group> groups = new LinkedHashMap<>();
        JsonElement groupsElement = root.get("groups");
        if (groupsElement != null && groupsElement.isJsonArray()) {
            for (JsonElement element : groupsElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "id");
                if (id.isEmpty()) {
                    warnings.add("有一个组缺少 id，已跳过");
                    continue;
                }
                Group group = parseGroup(id, object.get("lines"), warnings);
                if (group != null) {
                    groups.put(id, group);
                }
            }
        } else if (groupsElement != null && groupsElement.isJsonObject()) {
            // 也接受 {"g1": [ ... ], "g2": [ ... ]} 这种写法
            for (Map.Entry<String, JsonElement> entry : groupsElement.getAsJsonObject().entrySet()) {
                Group group = parseGroup(entry.getKey(), entry.getValue(), warnings);
                if (group != null) {
                    groups.put(entry.getKey(), group);
                }
            }
        }

        // 旧格式：根节点直接给 lines
        if (groups.isEmpty() && root.has("lines")) {
            Group legacy = parseGroup("default", root.get("lines"), warnings);
            if (legacy != null) {
                groups.put("default", legacy);
                warnings.add("检测到旧格式（只有 lines），已按单组 default 读取");
            }
        }
        if (groups.isEmpty()) {
            // 没有对话组是合法状态：工程里只是暂时没有内容
            return new Project(name, type, loop, groups, List.of(), List.copyOf(warnings), chunkRadius);
        }

        List<Rule> rules = new ArrayList<>();
        JsonElement rulesElement = root.get("rules");
        if (rulesElement != null && rulesElement.isJsonArray()) {
            for (JsonElement element : rulesElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "id");
                if (id.isEmpty()) {
                    id = "rule" + (rules.size() + 1);
                }
                List<String> play = new ArrayList<>();
                JsonElement playElement = object.get("play");
                if (playElement != null && playElement.isJsonArray()) {
                    for (JsonElement item : playElement.getAsJsonArray()) {
                        String groupId = item.isJsonPrimitive() ? item.getAsString().trim() : "";
                        if (!groupId.isEmpty() && groups.containsKey(groupId)) {
                            play.add(groupId);
                        } else if (!groupId.isEmpty()) {
                            warnings.add("规则 " + id + " 引用了不存在的组 " + groupId + "，已忽略");
                        }
                    }
                } else if (playElement != null && playElement.isJsonPrimitive()) {
                    String groupId = playElement.getAsString().trim();
                    if (groups.containsKey(groupId)) {
                        play.add(groupId);
                    } else {
                        warnings.add("规则 " + id + " 引用了不存在的组 " + groupId + "，已忽略");
                    }
                }
        if (play.isEmpty() && playElement == null) {
                    play.add(groups.keySet().iterator().next());
                    warnings.add("规则 " + id + " 没写 play，已默认用第一个组");
                }
                String pick = string(object, "pick");
                if (pick.isEmpty()) {
                    pick = play.size() > 1 ? "random" : "sequential";
                }
                List<Integer> weights = new ArrayList<>();
                JsonElement weightsElement = object.get("weights");
                if (weightsElement != null && weightsElement.isJsonArray()) {
                    for (JsonElement item : weightsElement.getAsJsonArray()) {
                        try {
                            weights.add(Math.max(0, item.getAsInt()));
                        } catch (RuntimeException e) {
                            weights.add(0);
                        }
                    }
                }
                boolean noRepeat = bool(object, "noRepeat", true);
                String whenJson = object.has("when") ? object.get("when").toString() : "";
                int window = Math.max(0, number(object, "window", 200));
                String target = string(object, "target");
                if (target.isEmpty()) {
                    target = "nearby";
                }
        int rawRadius = (int) number(object, "radius", 0);
        int radius = rawRadius > 0 ? Math.min(256, rawRadius) : 0;
                String center = string(object, "center");
                if (center.isEmpty()) {
                    center = "victim";
                }
                String once = string(object, "once");
                String state = string(object, "state");
                if (state.isEmpty()) {
                    state = "perWorld";
                }
                int cooldown = Math.max(0, number(object, "cooldown", 0));
                String priority = string(object, "priority");
                if (!priority.equals("high") && !priority.equals("low")) {
                    priority = "normal";
                }
                rules.add(new Rule(id, List.copyOf(play), pick, List.copyOf(weights), noRepeat,
                    whenJson, window, target, radius, center, once, state, cooldown, priority,
                    bool(object, "repeat", false)));
            }
        }
        if (rules.isEmpty()) {
            String first = groups.keySet().iterator().next();
            rules.add(new Rule("default", List.of(first), "sequential", List.of(), true));
            warnings.add("工程里没有 rules，已生成一条默认规则（播 " + first + "）");
        }

        return new Project(name, type, loop, groups, rules, List.copyOf(warnings), chunkRadius);
    }

    private static Group parseGroup(String id, JsonElement linesElement, List<String> warnings) {
        if (linesElement == null || !linesElement.isJsonArray()) {
            return null;
        }
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        for (JsonElement element : linesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String speaker = string(object, "speaker");
            String text = string(object, "text");
            if (speaker.isEmpty() || text.isEmpty()) {
                warnings.add("组 " + id + " 里有一行缺 speaker/text，已跳过");
                continue;
            }
            int hold = Math.max(4, number(object, "hold", DEFAULT_HOLD_TICKS));
            int at = Math.max(0, number(object, "at", 0));
            boolean wait = bool(object, "wait", bool(object, "waitForNext", false));
            int start = cursor + at;
            lines.add(new Line(speaker, text, start, hold, wait, string(object, "sound"), string(object, "portrait")));
            cursor = start + hold;
        }
        return lines.isEmpty() ? null : new Group(id, List.copyOf(lines));
    }

    public static int totalTicks(Group group) {
        int total = 0;
        for (Line line : group.lines()) {
            total = Math.max(total, line.startTick() + line.holdTick());
        }
        return total;
    }

    public static String describe(Project project) {
        StringBuilder text = new StringBuilder();
        text.append(project.groups().size()).append(" 组 / ").append(project.rules().size()).append(" 规则");
        for (Group group : project.groups().values()) {
            text.append(" · ").append(group.id()).append('(').append(group.lines().size()).append(" 句 ")
                    .append(String.format(Locale.ROOT, "%.1fs", totalTicks(group) / 20.0D)).append(')');
        }
        return text.toString();
    }

    public static boolean exists(String name) {
        try {
            return name != null && !name.isBlank() && Files.isRegularFile(dir().resolve(name + ".json"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 新建一个默认工程（不覆盖已有文件）。 */
    public static boolean createDefault(String name) {
        if (name == null || name.isBlank() || exists(name)) {
            return false;
        }
        try {
            Files.createDirectories(dir());
            Path file = dir().resolve(name + ".json");
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("name", name);
            root.addProperty("type", "sequential");
            root.addProperty("loop", false);
            JsonArray groups = new JsonArray();
            groups.add(group("g1", new String[][]{{"AgnesTechyon", "（在这里写第一句台词）"}},
                    new int[]{50}, new boolean[]{false}));
            root.add("groups", groups);
            JsonArray rules = new JsonArray();
            rules.add(rule("r_manual", new String[]{"g1"}, "sequential"));
            root.add("rules", rules);
            Files.writeString(file, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 删除工程文件。 */
    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(dir().resolve(name + ".json"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 复制工程到新名字（不覆盖）。 */
    public static boolean copy(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank() || exists(to)) {
            return false;
        }
        try {
            Path source = dir().resolve(from + ".json");
            if (!Files.isRegularFile(source)) {
                return false;
            }
            Files.copy(source, dir().resolve(to + ".json"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 重命名工程文件（不覆盖）。 */
    public static boolean rename(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank() || exists(to)) {
            return false;
        }
        try {
            Path source = dir().resolve(from + ".json");
            if (!Files.isRegularFile(source)) {
                return false;
            }
            Files.move(source, dir().resolve(to + ".json"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 从导出目录导入同名工程（覆盖本地同名文件）。 */
    public static boolean importFrom(String name, Path sourceDir) {
        if (name == null || name.isBlank() || sourceDir == null) {
            return false;
        }
        try {
            Path source = sourceDir.resolve(name + ".json");
            if (!Files.isRegularFile(source)) {
                return false;
            }
            Files.createDirectories(dir());
            Files.copy(source, dir().resolve(name + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    /** 导出工程到指定目录（同名不覆盖则加序号）。 */
    public static Path export(String name, Path targetDir) {
        try {
            Path source = dir().resolve(name + ".json");
            if (!Files.isRegularFile(source)) {
                return null;
            }
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(name + ".json");
            int suffix = 1;
            while (Files.exists(target)) {
                target = targetDir.resolve(name + "-" + suffix + ".json");
                suffix++;
            }
            Files.copy(source, target);
            return target;
        } catch (IOException e) {
            return null;
        }
    }
    /** 写一个示例工程（已存在则不覆盖）。 */
    public static Path writeExample() {
        Path file = dir().resolve("example.json");
        try {
            Files.createDirectories(dir());
            if (Files.isRegularFile(file)) {
                return file;
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("name", "示例工程");
            root.addProperty("type", "sequential");
            root.addProperty("loop", false);
            JsonArray groups = new JsonArray();
            groups.add(group("g1", new String[][]{{"AgnesTechyon", "雷达有反应……空中，正在俯冲！"}}, new int[]{28}, new boolean[]{false}));
            groups.add(group("g2", new String[][]{{"AgnesTechyon", "掩护我！"}, {"Gryphus", "二号收到，交给我。"}},
                    new int[]{20, 30}, new boolean[]{false, true}));
            root.add("groups", groups);
            JsonArray rules = new JsonArray();
            rules.add(rule("r_manual", new String[]{"g1"}, "sequential"));
            rules.add(rule("r_random", new String[]{"g1", "g2"}, "random"));
            root.add("rules", rules);
            Files.writeString(file, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 写示例失败不影响功能
        }
        return file;
    }

    private static JsonObject group(String id, String[][] lines, int[] holds, boolean[] waits) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        JsonArray array = new JsonArray();
        for (int i = 0; i < lines.length; i++) {
            JsonObject line = new JsonObject();
            line.addProperty("speaker", lines[i][0]);
            line.addProperty("text", lines[i][1]);
            line.addProperty("hold", holds[i]);
            if (i > 0) {
                line.addProperty("at", 10);
            }
            if (waits[i]) {
                line.addProperty("wait", true);
            }
            array.add(line);
        }
        object.add("lines", array);
        return object;
    }

    private static JsonObject rule(String id, String[] play, String pick) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        JsonArray array = new JsonArray();
        for (String groupId : play) {
            array.add(groupId);
        }
        object.add("play", array);
        object.addProperty("pick", pick);
        object.addProperty("noRepeat", true);
        return object;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
    }

    private static int number(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 剥掉 // 行注释与块注释，方便用记事本直接改。 */
    public static String stripComments(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '/') {
                    while (i < raw.length() && raw.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < raw.length() && !(raw.charAt(i) == '*' && raw.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private StageScripts() {
    }
}
