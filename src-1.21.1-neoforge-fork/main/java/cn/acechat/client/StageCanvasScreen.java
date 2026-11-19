package cn.acechat.client;

import cn.acechat.AceChat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SPEC-SACS「剧本演出」节点画布（完整一版）。
 *
 * <p>画布数据存在工程 JSON 的 {@code canvas} 段；同时把结构回写成播放用的
 * {@code groups}/{@code rules}（顺序组=一条链；随机组=每个对话一张权重组）。
 */
public class StageCanvasScreen extends Screen {
    private static final ResourceLocation PAPER = AceChat.id("textures/gui/paper.png");
    private static final int PAPER_W = 960;
    private static final int PAPER_H = 540;

    private static final int INK = 0xFF2B2B26;
    private static final int MUTED = 0xFF6B6B62;
    private static final int PAPER_FILL = 0xFFD8D8CA;
    private static final int CARD = 0xFFE6E2D6;
    private static final int CARD_SEL = 0xFFEFEAD9;
    private static final int FRAME = 0xFF3A3A34;
    private static final int ACCENT = 0xFF9B4E3B;
    private static final int WIRE = 0xFF5A5A52;

    private static final int TOP_H = 34;
    private static final int BOTTOM_H = 16;
    private static final int NODE_W = 210;
    private static final int HEADER_H = 30;
    private static final int ROW_H = 16;

    /** 事件编码 → 中文说明（选择列表显示）。 */
    private static final String[][] EVENT_DESC = {
            {"entity.hurt", "实体受伤"},
            {"entity.killed", "实体死亡"},
            {"entity.spawn", "实体生成"},
            {"player.death", "玩家死亡"},
            {"player.respawn", "玩家重生"},
            {"player.join", "玩家进服"},
            {"player.leave", "玩家离服"},
            {"player.enterDimension", "玩家换维度"},

            {"player.lowHealth", "玩家低血量"},
            {"player.chatContains", "聊天含关键词"},
            {"tick.every", "周期心跳"},
            {"hotkey", "快捷键触发"}
    };

    /** 必须填参数才算数的事件（空参数视为无效触发）。 */
    private static final java.util.Set<String> NEEDS_PARAM = java.util.Set.of(
            "entity.hurt", "entity.killed", "entity.spawn",
            "player.chatContains", "player.lowHealth", "player.enterDimension");

    /** 老数据里可能存了“编码 · 中文说明”，这里只保留编码。 */
    private static String sanitizeEvent(String value) {
        if (value == null) {
            return "";
        }
        int cut = value.indexOf('\u00B7');
        if (cut < 0) {
            cut = value.indexOf('·');
        }
        return cut < 0 ? value.trim() : value.substring(0, cut).trim();
    }

    private static String eventLabel(String event) {
        for (String[] item : EVENT_DESC) {
            if (item[0].equals(event)) {
                return item[0] + " · " + item[1];
            }
        }
        return event;
    }
    private static final String[] EVENTS = {
            "entity.hurt", "entity.killed", "entity.spawn", "player.death", "player.respawn",
            "player.join", "player.leave", "player.enterDimension",
            "player.lowHealth", "player.chatContains", "tick.every", "hotkey"
    };

    private enum Type { TRIGGER, GATE, DIALOGUE, INTERVAL, END }

    private final class Node {
        int id;
        Type type;
        double x;
        double y;
        boolean expanded;
        String event = "entity.hurt";
        String params = "";
        String hotkey = "key.keyboard.j";
        boolean negate;
        String gate = "all";
        String speaker = "";
        String text = "";
        double hold = 2.0;
        double gap = 0.0;
        boolean wait;
        double interval = 0.5;
        int weight = 50;
        boolean repeat;
        double cooldownSeconds;
        String broadcast = "nearby";
        String radiusText = "";
        boolean hasRepeatField;
        boolean hasCooldownField;
        boolean hasBroadcastField;
        boolean hasRadiusField;

        Node(int id, Type type, double x, double y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
        }

        int rows() {
            return switch (type) {
                case TRIGGER -> 3;
                case GATE -> 1;
                case DIALOGUE -> "random".equals(kind) ? 4 : 3;
                case INTERVAL -> 2;
                case END -> 1 + (repeat ? 1 : 0) + 1 + ("nearby".equals(broadcast) ? 1 : 0);
            };
        }

        int height() {
            return HEADER_H + 6 + (expanded ? rows() * ROW_H : 0);
        }

        String broadcastText() {
            return switch (broadcast) {
                case "self" -> "个人";
                case "all" -> "全体";
                default -> "附近";
            };
        }

        String title() {
            return switch (type) {
                case TRIGGER -> {
                    if ("hotkey".equals(event)) {
                        yield "触发：快捷键 " + StageHotkeys.shortName(hotkey) + (negate ? "（非）" : "");
                    }
                    yield "触发：" + event + (negate ? "（非）" : "")
                            + (NEEDS_PARAM.contains(event) && !paramsUsable(event, params) ? "（参数无效·不触发）" : "");
                }
                case GATE -> "逻辑门：" + ("any".equals(gate) ? "或" : "与");
                case DIALOGUE -> "对话：" + (speaker.isBlank() ? "（未选卡）" : speaker);
                case INTERVAL -> "时间：" + fmt(hold) + " / " + fmt(gap);
                case END -> "结束：" + (repeat ? "重复" : "截止");
            };
        }

        String subtitle() {
            return switch (type) {
                case TRIGGER -> "hotkey".equals(event)
                        ? "按下即演（不受冷却限制）"
                        : (params.isBlank() ? "无参数" : params);
                case GATE -> "连接多个触发条件";
                case DIALOGUE -> trim(text, 12);
                case INTERVAL -> "持续 " + fmt(hold) + " · 停滞 " + fmt(gap);
                case END -> (repeat ? "可重复 · 冷却 " + Math.round(cooldownSeconds) + "s" : "只触发一次")
                        + " · " + broadcastText();
            };
        }
    }

    private record Edge(int from, int to) {
    }

    private final Screen parent;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private int nextId = 1;

    private final String project;
    private String currentRule = "";
    private boolean rulePickerOpen;
    /** 保存时「触发节点 id → 该触发链的播放组」；writeWhen 用它把组写进各自触发器。 */
    private final java.util.Map<Integer, String> canvasTriggerGroups = new java.util.LinkedHashMap<>();
    private boolean canvasPresent = true;
    private boolean firstCanvasSave;
    private boolean canvasExistedAtLoad;
    private int loadedTriggerCount;
    private int ruleScroll;
    private String armedDelete = "";
    private long armedAt;
    private String kind = "sequence";
    private String status = "";
    private int statusTicks;

    private final List<String> undo = new ArrayList<>();
    private final List<String> redo = new ArrayList<>();
    private final List<Node> clipboard = new ArrayList<>();

    private float zoom = 1.0F;
    private double panX;
    private double panY;
    private boolean panning;
    private double lastMouseX;
    private double lastMouseY;

    private Node selected;
    private Node dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private final List<Node> boxSelected = new ArrayList<>();
    private boolean boxing;
    private double boxStartX;
    private double boxStartY;
    private double boxEndX;
    private double boxEndY;

    private Node connectingFrom;
    private boolean connectFromInput;
    private double wireMouseX;
    private double wireMouseY;

    private boolean menuOpen;
    private int menuX;
    private int menuY;

    private boolean cardPickerOpen;
    private boolean eventPickerOpen;
    private final List<String> cardNames = new ArrayList<>();
    private Node pickerNode;
    private int pickerScroll;

    private Node clickedNode;
    private Node sliderNode;
    private int sliderRow = -1;
    private Node bindingHotkey;   // 正在等玩家按键的触发节点（快捷键绑定中）
    private int clickedRow = -1;
    private long clickedAt;

    private Node editing;
    private boolean editorOpen;
    private String editorLabel = "";
    private String editorTarget = "";
    private final StringBuilder editorBuffer = new StringBuilder();
    private int caret;
    private int selAnchor = -1;
    private int editorScroll;
    private boolean hasKeyShiftDown;
    private int editX;
    private int editY;
    private int editW = 320;
    private int editH = 64;

    public StageCanvasScreen(Screen parent, String project) {
        super(Component.literal("ACE CHAT · 剧本设计"));
        this.parent = parent;
        this.project = project == null ? "" : project;
        this.currentRule = firstRule();
        load();
    }

    private void load() {
        nodes.clear();
        edges.clear();
        nextId = 1;
        if (project.isEmpty()) {
            seed();
            return;
        }
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            seed();
            return;
        }
        JsonObject canvas = null;
        if (root.has("canvases") && root.get("canvases").isJsonObject()) {
            JsonObject all = root.getAsJsonObject("canvases");
            if (all.has(currentRule) && all.get(currentRule).isJsonObject()) {
                canvas = all.getAsJsonObject(currentRule);
            }
        }
        if (canvas == null && "canvas".equals(currentRule)
                && root.has("canvas") && root.get("canvas").isJsonObject()) {
            canvas = root.getAsJsonObject("canvas");
        }
        if (canvas == null) {
            // 该事件组还没有画布：给空画布，不写盘、不生成占位台词
            canvasPresent = false;
            canvasExistedAtLoad = false;
            loadedTriggerCount = 0;
            return;
        }
        canvasPresent = true;
        canvasExistedAtLoad = true;
        kind = str(canvas, "kind", "sequence");
        if (canvas.has("nodes") && canvas.get("nodes").isJsonArray()) {
            for (JsonElement element : canvas.getAsJsonArray("nodes")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                Type type;
                try {
                    type = Type.valueOf(str(object, "type", "DIALOGUE"));
                } catch (IllegalArgumentException e) {
                    type = Type.DIALOGUE;
                }
                Node node = new Node(num(object, "id", nextId), type,
                        dbl(object, "x", 60), dbl(object, "y", 60));
                nextId = Math.max(nextId + 1, node.id + 1);
                readNode(node, object);
                nodes.add(node);
            }
        }
        if (canvas.has("edges") && canvas.get("edges").isJsonArray()) {
            for (JsonElement element : canvas.getAsJsonArray("edges")) {
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    edges.add(new Edge(num(object, "from", 0), num(object, "to", 0)));
                }
            }
        }
        loadedTriggerCount = 0;
        for (Node node : nodes) {
            if (node.type == Type.TRIGGER) {
                loadedTriggerCount++;
            }
        }
        migrateLegacyGroups();
        backfillEndNode();
    }

    /** 旧版本的组 id（cv_<safe> 或 cv_<safe>_n）迁移到新前缀，避免同一段演出播两遍。 */
    private void migrateLegacyGroups() {
        JsonObject root = StageProjects.readProject(project);
        if (root == null || !root.has("groups") || !root.get("groups").isJsonArray()) {
            return;
        }
        String oldPrefix = "cv_" + currentRule.replaceAll("[^A-Za-z0-9_]", "_");
        String newPrefix = groupPrefix();
        if (oldPrefix.equals(newPrefix)) {
            return;
        }
        JsonArray groups = root.getAsJsonArray("groups");
        java.util.Map<String, String> renamed = new java.util.HashMap<>();
        for (JsonElement element : groups) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            String id = str(group, "id", "");
            if (!id.equals(oldPrefix) && !id.startsWith(oldPrefix + "_")) {
                continue;
            }
            if (id.equals(newPrefix) || id.startsWith(newPrefix + "_")) {
                continue;
            }
            String target = newPrefix + id.substring(oldPrefix.length());
            group.addProperty("id", target);
            renamed.put(id, target);
        }
        if (renamed.isEmpty()) {
            return;
        }
        if (root.has("rules") && root.get("rules").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("rules")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject rule = element.getAsJsonObject();
                if (!currentRule.equals(str(rule, "id", "")) || !rule.has("play")
                        || !rule.get("play").isJsonArray()) {
                    continue;
                }
                JsonArray play = rule.getAsJsonArray("play");
                for (int i = 0; i < play.size(); i++) {
                    String old = play.get(i).getAsString();
                    if (renamed.containsKey(old)) {
                        play.set(i, new com.google.gson.JsonPrimitive(renamed.get(old)));
                    }
                }
            }
        }
        StageProjects.writeProject(project, root);
    }

    /** 老画布没有 broadcast/radius 字段时，用规则现状回填，避免保存时把规则设置冲掉。 */
    private void backfillEndNode() {
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return;
        }
        JsonObject rule = null;
        if (root.has("rules") && root.get("rules").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("rules")) {
                if (element.isJsonObject()
                        && currentRule.equals(str(element.getAsJsonObject(), "id", ""))) {
                    rule = element.getAsJsonObject();
                    break;
                }
            }
        }
        if (rule == null) {
            return;
        }
        for (Node node : nodes) {
            if (node.type != Type.END) {
                continue;
            }
            if (!node.hasRepeatField) {
                // 老画布没有 repeat 字段：once 为空 = 可重复，once 有限制 = 截止
                if (rule.has("repeat")) {
                    node.repeat = bool(rule, "repeat");
                } else {
                    node.repeat = str(rule, "once", "").isBlank();
                }
            }
            if (!node.hasCooldownField) {
                node.cooldownSeconds = dbl(rule, "cooldown", 0);
            }
            if (!node.hasBroadcastField) {
                node.broadcast = str(rule, "target", "nearby");
            }
            if (!node.hasRadiusField) {
                int radius = (int) dbl(rule, "radius", 0);
                node.radiusText = radius > 0 ? String.valueOf(radius) : "";
            }
            break;
        }
    }

    private void readNode(Node node, JsonObject object) {
        node.expanded = bool(object, "expanded");
        node.event = sanitizeEvent(str(object, "event", "entity.hurt"));
        node.params = str(object, "params", "");
        node.hotkey = str(object, "hotkey", "key.keyboard.j");
        node.negate = bool(object, "negate");
        node.gate = str(object, "gate", "all");
        node.speaker = str(object, "speaker", "");
        node.text = str(object, "text", "");
        node.hold = dbl(object, "hold", 2.0);
        node.gap = dbl(object, "gap", 0.0);
        node.wait = bool(object, "wait");
        node.interval = dbl(object, "interval", 0.5);
        node.weight = num(object, "weight", 50);
        node.hasRepeatField = object.has("repeat");
        node.repeat = bool(object, "repeat");
        node.hasCooldownField = object.has("cooldownSeconds");
        node.cooldownSeconds = dbl(object, "cooldownSeconds", 0);
        node.hasBroadcastField = object.has("broadcast");
        node.broadcast = str(object, "broadcast", "nearby");
        node.hasRadiusField = object.has("radiusText");
        node.radiusText = str(object, "radiusText", "");
    }

    private void seed() {
        Node trigger = add(Type.TRIGGER, 40, 110);
        Node gate = add(Type.GATE, 300, 116);
        Node line1 = add(Type.DIALOGUE, 570, 60);
        line1.speaker = "队长";
        line1.text = "撑住，我们马上到";
        Node line2 = add(Type.DIALOGUE, 570, 220);
        line2.speaker = "队员";
        line2.text = "收到，正在撤离";
        Node end = add(Type.END, 840, 120);
        edges.add(new Edge(trigger.id, gate.id));
        edges.add(new Edge(gate.id, line1.id));
        edges.add(new Edge(line1.id, line2.id));
        edges.add(new Edge(line2.id, end.id));
    }

    private Node add(Type type, double x, double y) {
        Node node = new Node(nextId++, type, x, y);
        nodes.add(node);
        return node;
    }

    private void persist() {
        if (project.isEmpty()) {
            return;
        }
        if (!canvasPresent && nodes.isEmpty() && edges.isEmpty()) {
            return;   // 还没在这张画布上做任何事：不写盘，避免覆盖原规则
        }
        firstCanvasSave = !canvasPresent;
        canvasPresent = true;
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return;
        }
        JsonObject canvas = new JsonObject();
        canvas.addProperty("kind", kind);
        JsonArray nodeArray = new JsonArray();
        for (Node node : nodes) {
            JsonObject object = new JsonObject();
            object.addProperty("id", node.id);
            object.addProperty("type", node.type.name());
            object.addProperty("x", node.x);
            object.addProperty("y", node.y);
            object.addProperty("expanded", node.expanded);
            object.addProperty("event", node.event);
            object.addProperty("params", node.params);
            object.addProperty("hotkey", node.hotkey);
            object.addProperty("negate", node.negate);
            object.addProperty("gate", node.gate);
            object.addProperty("speaker", node.speaker);
            object.addProperty("text", node.text);
            object.addProperty("hold", node.hold);
            object.addProperty("gap", node.gap);
            object.addProperty("wait", node.wait);
            object.addProperty("interval", node.interval);
            object.addProperty("weight", node.weight);
            object.addProperty("repeat", node.repeat);
            object.addProperty("cooldownSeconds", node.cooldownSeconds);
            object.addProperty("broadcast", node.broadcast);
            object.addProperty("radiusText", node.radiusText);
            nodeArray.add(object);
        }
        canvas.add("nodes", nodeArray);
        JsonArray edgeArray = new JsonArray();
        for (Edge edge : edges) {
            JsonObject object = new JsonObject();
            object.addProperty("from", edge.from());
            object.addProperty("to", edge.to());
            edgeArray.add(object);
        }
        canvas.add("edges", edgeArray);
        JsonObject all = root.has("canvases") && root.get("canvases").isJsonObject()
                ? root.getAsJsonObject("canvases") : new JsonObject();
        all.add(currentRule, canvas);
        root.add("canvases", all);
        rebuildPlayback(root);
        StageProjects.writeProject(project, root);
    }

    private void rebuildPlayback(JsonObject root) {
        JsonArray groups;
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            groups = root.getAsJsonArray("groups");
        } else if (root.has("groups") && root.get("groups").isJsonObject()) {
            groups = new JsonArray();
            com.google.gson.JsonObject object = root.getAsJsonObject("groups");
            for (String key : object.keySet()) {
                JsonObject group = new JsonObject();
                group.addProperty("id", key);
                JsonElement value = object.get(key);
                if (value.isJsonArray()) {
                    group.add("lines", value.getAsJsonArray());
                }
                groups.add(group);
            }
        } else {
            groups = new JsonArray();
        }
        JsonArray rules = root.has("rules") && root.get("rules").isJsonArray()
                ? root.getAsJsonArray("rules") : new JsonArray();

        List<Node> chain = chainOrder();
        if ("random".equals(kind)) {
            List<String> ids = new ArrayList<>();
            JsonArray weights = new JsonArray();
            int index = 0;
            for (Node node : chain) {
                if (node.type != Type.DIALOGUE) {
                     continue;
                  }
                  if (node.speaker == null || node.speaker.isBlank()
                          || node.text == null || node.text.isBlank()) {
                     continue;   // 空对白不建组
                  }
                index++;
                String groupId = groupPrefix() + "_" + index;
                JsonObject group = new JsonObject();
                group.addProperty("id", groupId);
                JsonArray lines = new JsonArray();
                JsonObject line = new JsonObject();
                line.addProperty("speaker", node.speaker);
                line.addProperty("text", node.text);
                line.addProperty("start", 0);
                line.addProperty("hold", 40);
                line.addProperty("wait", node.wait);
                lines.add(line);
                group.add("lines", lines);
                replaceGroup(groups, groupId, group);
                ids.add(groupId);
                weights.add(Math.max(1, node.weight));
            }
            if (ids.isEmpty()) {
                clearCanvasPlayback(root, groups);
                return;
            }
            JsonObject rule = ensureRule(rules, currentRule, "random");
            JsonArray play = existingPlay(rule);
            int preserved = play.size();
            ids.forEach(play::add);
            rule.add("play", play);
            JsonArray fullWeights = new JsonArray();
            for (int i = 0; i < preserved; i++) {
                fullWeights.add(1);
            }
            weights.forEach(fullWeights::add);
            rule.add("weights", fullWeights);
            rule.addProperty("pick", "random");
            writeWhen(rule);
            applyEndSettings(rule);
        } else {
            JsonArray playIds = new JsonArray();
            java.util.Set<String> keptGroups = new java.util.HashSet<>();
            java.util.Map<Integer, String> groupsByTrigger = new java.util.LinkedHashMap<>();
            int triggerIndex = 0;
            for (Node trigger : nodes) {
                if (trigger.type != Type.TRIGGER) {
                    continue;
                }
                triggerIndex++;
                java.util.Set<Integer> visited = new java.util.HashSet<>();
                List<Node> chainOfTrigger = new ArrayList<>();
                walkChain(trigger, visited, chainOfTrigger);
                List<JsonObject> builtLines = buildLines(chainOfTrigger);
                if (builtLines.isEmpty()) {
                    continue;   // 这条触发链没有对白，不建组
                }
                String groupId = groupPrefix() + "_" + triggerIndex;
                keptGroups.add(groupId);
                JsonObject group = new JsonObject();
                group.addProperty("id", groupId);
                JsonArray arr = new JsonArray();
                builtLines.forEach(arr::add);
                group.add("lines", arr);
                replaceGroup(groups, groupId, group);
                playIds.add(groupId);
                groupsByTrigger.put(trigger.id, groupId);
            }
            if (playIds.isEmpty()) {
                // 没有接线：退回把所有对白放进一个组
                List<JsonObject> builtLines = buildLines(chain);
                if (builtLines.isEmpty()) {
                    clearCanvasPlayback(root, groups);
                    return;
                }
                JsonObject group = new JsonObject();
                group.addProperty("id", groupPrefix());
                JsonArray arr = new JsonArray();
                builtLines.forEach(arr::add);
                group.add("lines", arr);
                replaceGroup(groups, groupPrefix(), group);
                playIds.add(groupPrefix());
                keptGroups.add(groupPrefix());
            }
            pruneCanvasGroups(groups, keptGroups);
            JsonObject rule = ensureRule(rules, currentRule, "sequence");
            rule.add("play", canvasPlay(rule, playIds));
            rule.addProperty("pick", "sequential");
            canvasTriggerGroups.clear();
            canvasTriggerGroups.putAll(groupsByTrigger);
            writeWhen(rule);
            canvasTriggerGroups.clear();
            applyEndSettings(rule);
        }
        root.add("groups", groups);
        root.add("rules", rules);
    }

    /** 从触发/逻辑门沿连线走出播放链；没有连线就按节点列表顺序。 */
    private List<Node> chainOrder() {
        List<Node> result = new ArrayList<>();
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        List<Node> starts = new ArrayList<>();
        for (Node node : nodes) {
            if (node.type == Type.TRIGGER) {
                starts.add(node);
            }
        }
        if (starts.isEmpty()) {
            for (Node node : nodes) {
                if (node.type == Type.GATE) {
                    starts.add(node);
                    break;
                }
            }
        }
        for (Node start : starts) {
            walkChain(start, visited, result);
        }
        if (result.isEmpty()) {
            for (Node node : nodes) {
                if (node.type == Type.DIALOGUE || node.type == Type.INTERVAL) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    /** 把一条链上的对白/间隔节点变成 lines 数组（间隔作用于它后面那一句）。 */
    private List<JsonObject> buildLines(List<Node> chain) {
        List<JsonObject> lines = new ArrayList<>();
        int start = 0;
        Node pendingTime = null;
        for (Node node : chain) {
            if (node.type == Type.INTERVAL) {
                pendingTime = node;
                continue;
            }
            if (node.type != Type.DIALOGUE) {
                continue;
            }
            int holdTicks = 40;
            int gapTicks = 0;
            if (pendingTime != null) {
                holdTicks = Math.max(10, (int) Math.round(pendingTime.hold * 20));
                gapTicks = Math.max(0, (int) Math.round(pendingTime.gap * 20));
            }
            if (node.speaker == null || node.speaker.isBlank()
                    || node.text == null || node.text.isBlank()) {
                continue;   // 空对白不写进组
            }
            JsonObject line = new JsonObject();
            line.addProperty("speaker", node.speaker);
            line.addProperty("text", node.text);
            line.addProperty("start", start);
            line.addProperty("at", Math.max(0, gapTicks));
            line.addProperty("hold", holdTicks);
            line.addProperty("wait", node.wait);
            lines.add(line);
            start += holdTicks + gapTicks;
            pendingTime = null;
        }
        return lines;
    }

    /** 删掉这张画布以前写、但这次没再生成的旧组。 */
    private void pruneCanvasGroups(JsonArray groups, java.util.Set<String> keep) {
        String prefix = groupPrefix();
        for (int i = groups.size() - 1; i >= 0; i--) {
            JsonElement element = groups.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            String id = str(element.getAsJsonObject(), "id", "");
            if ((id.equals(prefix) || id.startsWith(prefix + "_")) && !keep.contains(id)) {
                groups.remove(i);
            }
        }
    }

    /** 新的 play 列表：保留非本画布的组，替换掉本画布旧组。 */
    private JsonArray canvasPlay(JsonObject rule, JsonArray newIds) {
        String prefix = groupPrefix();
        JsonArray result = new JsonArray();
        for (JsonElement element : existingPlay(rule)) {
            String id = element.getAsString();
            if (id.equals(prefix) || id.startsWith(prefix + "_")) {
                continue;
            }
            result.add(id);
        }
        for (JsonElement element : newIds) {
            result.add(element);
        }
        return result;
    }

    /**
     * 触发节点的「参数」：key=value，空格/逗号分隔。
     * entity=minecraft:zombie  dimension=minecraft:overworld  keyword=你好  below=0.3
     */
    private void applyParams(JsonObject leaf, String params) {
        if (params == null || params.isBlank()) {
            return;
        }
        String event = leaf.has("event") ? leaf.get("event").getAsString() : "";
        boolean playerEvent = event.startsWith("player.");
        String text = params.trim();
        if (!text.contains("=")) {
            // 裸值按事件解释：玩家事件=玩家名/关键词/阈值/维度；心跳=间隔 tick；其它含冒号的当实体 id
            switch (event) {
                case "player.death", "player.respawn", "player.join", "player.leave" ->
                        leaf.addProperty("player", text);
                case "player.chatContains" -> leaf.addProperty("keyword", text);
                case "player.lowHealth" -> {
                    try {
                        leaf.addProperty("below", Double.parseDouble(text));
                    } catch (NumberFormatException ignored) {
                        // 非数字就当没填
                    }
                }
                case "player.enterDimension" -> leaf.addProperty("dimension", text);
                case "tick.every" -> {
                    try {
                        leaf.addProperty("every", Math.max(1, Integer.parseInt(text)));
                    } catch (NumberFormatException ignored) {
                        // 非数字就当没填
                    }
                }
                default -> {
                    if (text.contains(":")) {
                        leaf.addProperty("entity", text);
                    }
                }
            }
            return;
        }
        for (String token : text.split("[\\s,;]+")) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "entity", "mob" -> leaf.addProperty("entity", value);
                case "dimension", "dim" -> leaf.addProperty("dimension", value);
                case "keyword", "text" -> leaf.addProperty("keyword", value);
                case "uuid" -> leaf.addProperty("uuid", value);
                case "player" -> leaf.addProperty("player", value);
                case "every" -> {
                    try {
                        leaf.addProperty("every", Math.max(1, Integer.parseInt(value)));
                    } catch (NumberFormatException ignored) {
                        // 忽略非法数字
                    }
                }
                case "name" -> {
                    // 玩家事件里 name= 就是玩家名；实体事件才当自定义名
                    if (playerEvent) {
                        leaf.addProperty("player", value);
                    } else {
                        leaf.addProperty("name", value);
                    }
                }
                case "below", "health" -> {
                    try {
                        leaf.addProperty("below", Double.parseDouble(value));
                    } catch (NumberFormatException ignored) {
                        // 忽略非法数字
                    }
                }
                default -> {
                    // 未知键忽略
                }
            }
        }
    }

    /** 画布上没有对白了：清掉这条规则的画布组和 play（触发时不再显示任何对话）。 */
    private void clearCanvasPlayback(JsonObject root, JsonArray groups) {
        String prefix = groupPrefix();
        int removed = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            JsonElement element = groups.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            String id = str(element.getAsJsonObject(), "id", "");
            if (id.equals(prefix) || id.startsWith(prefix + "_")) {
                groups.remove(i);
                removed++;
            }
        }
        if (root.has("rules") && root.get("rules").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("rules")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject rule = element.getAsJsonObject();
                if (currentRule.equals(str(rule, "id", ""))) {
                    if (removed > 0 || rule.has("play")) {
                        rule.add("play", existingPlay(rule));
                    }
                    if (removed > 0) {
                        rule.remove("weights");
                    }
                    break;
                }
            }
        }
        root.add("groups", groups);
    }

    /** 旧 play 里不属于画布的组，保留下来（避免保存画布时把原内容挤掉）。 */
    private JsonArray existingPlay(JsonObject rule) {
        JsonArray result = new JsonArray();
        if (rule.has("play") && rule.get("play").isJsonArray()) {
            for (JsonElement element : rule.getAsJsonArray("play")) {
                String id = element.getAsString();
                if (!id.equals(groupPrefix()) && !id.startsWith(groupPrefix() + "_")) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    /** 结束卡（重复/截止）+ 冷却卡 → 写进 rule。 */
    private void applyEndSettings(JsonObject rule) {
        Node endNode = null;
        for (Node node : nodes) {
            if (node.type == Type.END && endNode == null) {
                endNode = node;
            }
            }
        if (endNode == null) {
            return;   // 画布上没有结束卡：不动规则的 repeat/once/target/radius
        }
        boolean repeat = endNode.repeat;
        rule.addProperty("repeat", repeat);
        rule.addProperty("cooldown", repeat && endNode != null ? (int) Math.round(endNode.cooldownSeconds) : 0);
        String existingOnce = str(rule, "once", "");
        if (repeat) {
            rule.addProperty("once", "");
        } else if (!existingOnce.isBlank() && !"perplayer".equalsIgnoreCase(existingOnce)) {
            rule.addProperty("once", existingOnce);   // 保留 perworld/pertype/perinstance
        } else {
            rule.addProperty("once", "perplayer");
        }
        String target = endNode == null ? "nearby" : endNode.broadcast;
        rule.addProperty("target", "self".equals(target) ? "self" : "all".equals(target) ? "all" : "nearby");
        if (endNode != null && "nearby".equals(endNode.broadcast)
                && endNode.radiusText != null && !endNode.radiusText.isBlank()) {
            try {
                int chunks = Math.max(1, Math.min(64, Integer.parseInt(endNode.radiusText.trim())));
                rule.addProperty("radius", chunks);
            } catch (NumberFormatException ignored) {
                rule.remove("radius");
            }
        } else {
            rule.remove("radius");
        }
    }

    /** 触发节点 + 逻辑门 → rule.when。 */

    /** 参数是否真的能用作过滤（按事件白名单校验，避免无效参数退化成无条件触发）。 */
    private static boolean paramsUsable(String event, String params) {
        if (params == null || params.isBlank()) {
            return false;
        }
        String text = params.trim();
        if (!text.contains("=")) {
            return event != null && event.startsWith("entity.") && text.contains(":");
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String token : text.split("[\\s,;]+")) {
            int eq = token.indexOf('=');
            if (eq > 0 && eq < token.length() - 1) {
                keys.add(token.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (keys.isEmpty()) {
            return false;
        }
        return switch (event == null ? "" : event) {
            case "entity.hurt", "entity.killed", "entity.spawn" ->
                    keys.contains("entity") || keys.contains("mob")
                            || keys.contains("name") || keys.contains("uuid");
            case "player.chatContains" -> keys.contains("keyword") || keys.contains("text");
            case "player.lowHealth" -> keys.contains("below") || keys.contains("health");
            case "player.enterDimension" -> keys.contains("dimension") || keys.contains("dim");
            case "player.enterArea" -> keys.contains("area") || keys.contains("dimension");
            default -> true;
        };
    }

    private void writeWhen(JsonObject rule) {
      if (firstCanvasSave) {
         boolean anyTrigger = false;
         for (Node node : nodes) {
            if (node.type == Type.TRIGGER) {
               anyTrigger = true;
               break;
            }
         }
         if (!anyTrigger) {
            return;   // 首次建画布且没有触发节点：保留原 when
         }
      }
      boolean canvasHotkey = false;
      for (Node node : nodes) {
         if (node.type == Type.TRIGGER && "hotkey".equals(node.event)) {
            canvasHotkey = true;
            break;
         }
      }
      if (rule.has("when") && rule.get("when").isJsonObject()) {
         JsonObject existing = rule.getAsJsonObject("when");
         boolean hotkey = false;
         if (existing.has("triggers") && existing.get("triggers").isJsonArray()) {
            for (JsonElement element : existing.getAsJsonArray("triggers")) {
               if (element.isJsonObject() && (element.getAsJsonObject().has("hotkey")
                            || "hotkey".equalsIgnoreCase(str(element.getAsJsonObject(), "mode", "")))) {
                  hotkey = true;
                  break;
               }
            }
         }
         if (existing.has("expr")) {
            return;   // 表达式条件由触发器页维护，画布不动它
         }
         if (hotkey && !canvasHotkey) {
            return;   // 触发器页配的快捷键，画布不动它
         }
      }
        List<Node> hotkeyNodes = new ArrayList<>();
        List<JsonObject> leaves = new ArrayList<>();
        String gate = "all";
        for (Node node : nodes) {
            if (node.type != Type.TRIGGER) {
                continue;
            }
            if ("hotkey".equals(node.event)) {
                hotkeyNodes.add(node);
                continue;
            }
            if (NEEDS_PARAM.contains(node.event) && !paramsUsable(node.event, node.params)) {
                return;   // 有无效触发节点：保持旧 when 不动，避免条件被放宽
            }
            JsonObject leaf = new JsonObject();
            leaf.addProperty("event", node.event);
            applyParams(leaf, node.params);
            JsonObject entry = leaf;
            if (node.negate) {
                entry = new JsonObject();
                entry.add("not", leaf);
            }
            String groupId = canvasTriggerGroups.get(node.id);
            if (groupId != null && !groupId.isEmpty()) {
                entry.addProperty("group", groupId);
            }
            leaves.add(entry);
        }
        for (Node node : nodes) {
            if (node.type == Type.GATE) {
                gate = "any".equals(node.gate) ? "any" : "all";
                break;
            }
        }
        if (!hotkeyNodes.isEmpty()) {
            JsonObject when = new JsonObject();
            JsonArray triggers = new JsonArray();
            boolean first = true;
            for (Node node : hotkeyNodes) {
                JsonObject trigger = new JsonObject();
                trigger.addProperty("mode", "hotkey");
                trigger.addProperty("hotkey", node.hotkey == null || node.hotkey.isBlank()
                        ? "key.keyboard.j" : node.hotkey);
                if (node.negate) {
                    trigger.addProperty("negate", true);
                }
                String groupId = canvasTriggerGroups.get(node.id);
                if (groupId != null && !groupId.isEmpty()) {
                    trigger.addProperty("group", groupId);
                }
                if (!first) {
                    trigger.addProperty("op", "any");
                }
                first = false;
                triggers.add(trigger);
            }
            for (JsonObject leaf : leaves) {
                JsonObject trigger = leaf.deepCopy();
                if (!first) {
                    trigger.addProperty("op", "any");
                }
                first = false;
                triggers.add(trigger);
            }
            if (!leaves.isEmpty()) {
                status("快捷键与其它触发条件按“或”组合");
            }
            when.add("triggers", triggers);
            if (rule.has("when") && rule.get("when").isJsonObject()
                    && rule.getAsJsonObject("when").has("window")) {
                when.add("window", rule.getAsJsonObject("when").get("window"));
            }
            rule.add("when", when);
            return;
        }
        if (leaves.isEmpty()) {
            rule.remove("when");
            return;
        }
        JsonObject when = new JsonObject();
        if (rule.has("when") && rule.get("when").isJsonObject()) {
            JsonObject old = rule.getAsJsonObject("when");
            if (old.has("window")) {
                when.add("window", old.get("window"));
            }
        }
        if (leaves.size() == 1) {
            when = leaves.get(0);
        } else {
            JsonArray array = new JsonArray();
            leaves.forEach(array::add);
            when.add(gate, array);
        }
        rule.add("when", when);
    }

    private void replaceGroup(JsonArray groups, String id, JsonObject replacement) {
        for (int i = 0; i < groups.size(); i++) {
            JsonElement element = groups.get(i);
            if (element.isJsonObject() && id.equals(str(element.getAsJsonObject(), "id", ""))) {
                groups.set(i, replacement);
                return;
            }
        }
        groups.add(replacement);
    }

    private JsonObject ensureRule(JsonArray rules, String id, String ruleKind) {
        for (JsonElement element : rules) {
            if (element.isJsonObject() && id.equals(str(element.getAsJsonObject(), "id", ""))) {
                JsonObject rule = element.getAsJsonObject();
                rule.addProperty("kind", ruleKind);
                return rule;
            }
        }
        JsonObject rule = new JsonObject();
        rule.addProperty("id", id);
        rule.addProperty("kind", ruleKind);
        rule.addProperty("pick", "random".equals(ruleKind) ? "random" : "sequential");
        rules.add(rule);
        return rule;
    }

    private String snapshot() {
        JsonObject canvas = new JsonObject();
        canvas.addProperty("kind", kind);
        JsonArray nodeArray = new JsonArray();
        for (Node node : nodes) {
            JsonObject object = new JsonObject();
            object.addProperty("id", node.id);
            object.addProperty("type", node.type.name());
            object.addProperty("x", node.x);
            object.addProperty("y", node.y);
            object.addProperty("expanded", node.expanded);
            object.addProperty("event", node.event);
            object.addProperty("params", node.params);
            object.addProperty("hotkey", node.hotkey);
            object.addProperty("negate", node.negate);
            object.addProperty("gate", node.gate);
            object.addProperty("speaker", node.speaker);
            object.addProperty("text", node.text);
            object.addProperty("hold", node.hold);
            object.addProperty("gap", node.gap);
            object.addProperty("wait", node.wait);
            object.addProperty("interval", node.interval);
            object.addProperty("weight", node.weight);
            object.addProperty("repeat", node.repeat);
            object.addProperty("cooldownSeconds", node.cooldownSeconds);
            object.addProperty("broadcast", node.broadcast);
            object.addProperty("radiusText", node.radiusText);
            nodeArray.add(object);
        }
        canvas.add("nodes", nodeArray);
        JsonArray edgeArray = new JsonArray();
        for (Edge edge : edges) {
            JsonObject object = new JsonObject();
            object.addProperty("from", edge.from());
            object.addProperty("to", edge.to());
            edgeArray.add(object);
        }
        canvas.add("edges", edgeArray);
        return canvas.toString();
    }

    private void beforeChange() {
        undo.add(snapshot());
        if (undo.size() > 60) {
            undo.remove(0);
        }
        redo.clear();
    }

    private void applySnapshot(String json) {
        JsonObject canvas = JsonParser.parseString(json).getAsJsonObject();
        nodes.clear();
        edges.clear();
        nextId = 1;
        kind = str(canvas, "kind", "sequence");
        if (canvas.has("nodes")) {
            for (JsonElement element : canvas.getAsJsonArray("nodes")) {
                JsonObject object = element.getAsJsonObject();
                Type type;
                try {
                    type = Type.valueOf(str(object, "type", "DIALOGUE"));
                } catch (IllegalArgumentException e) {
                    type = Type.DIALOGUE;
                }
                Node node = new Node(num(object, "id", nextId), type,
                        dbl(object, "x", 60), dbl(object, "y", 60));
                nextId = Math.max(nextId + 1, node.id + 1);
                readNode(node, object);
                nodes.add(node);
            }
        }
        if (canvas.has("edges")) {
            for (JsonElement element : canvas.getAsJsonArray("edges")) {
                JsonObject object = element.getAsJsonObject();
                edges.add(new Edge(num(object, "from", 0), num(object, "to", 0)));
            }
        }
        selected = null;
        boxSelected.clear();
        persist();
    }

    private void undoAction() {
        if (undo.isEmpty()) {
            return;
        }
        redo.add(snapshot());
        applySnapshot(undo.remove(undo.size() - 1));
        status("已撤销");
    }

    private void redoAction() {
        if (redo.isEmpty()) {
            return;
        }
        undo.add(snapshot());
        applySnapshot(redo.remove(redo.size() - 1));
        status("已重做");
    }

    @Override
    public boolean isPauseScreen() {
        // 编辑工程时冻结世界：单人游戏里一打开画布就暂停 tick（生物不动、时间不走、
        // 正在播的演出也会停在原地）。原版规则：仅单人生效，LAN 已发布的世界不暂停。
        return true;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.addRenderableWidget(Button.builder(Component.literal("事件组：" + currentRule), b -> {
            rulePickerOpen = !rulePickerOpen;
            ruleScroll = 0;
            armedDelete = "";
        }).bounds(this.width - 320, 9, 104, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(this.width - 52, 9, 44, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("排列"), b -> {
            beforeChange();
            autoArrange();
            persist();
        }).bounds(this.width - 108, 9, 50, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("类型"), b -> {
            beforeChange();
            kind = "random".equals(kind) ? "sequence" : "random";
            persist();
            status("事件组类型：" + ("random".equals(kind) ? "随机" : "顺序"));
        }).bounds(this.width - 156, 9, 44, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("保存"), b -> {
            persist();
            status("已保存到 " + project + ".json");
        }).bounds(this.width - 204, 9, 44, 16).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, PAPER_FILL);
        graphics.blit(PAPER, 0, 0, this.width, this.height, 0, 0, PAPER_W, PAPER_H, PAPER_W, PAPER_H);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render 会画背景和按钮，先让它画完，再把画布盖在上面
        super.render(graphics, mouseX, mouseY, partialTick);
        this.drawGrid(graphics);
        this.drawWires(graphics);
        this.drawNodes(graphics);
        this.drawConnectPreview(graphics);
        this.drawSelectionBox(graphics);

        graphics.drawString(this.font, "ACE CHAT · 剧本设计", 16, 8, INK, false);
        graphics.drawString(this.font, "工程 " + (project.isEmpty() ? "（无）" : project)
                        + " · " + ("random".equals(kind) ? "随机组" : "顺序组")
                        + " · 节点 " + nodes.size() + " · 连线 " + edges.size()
                        + " · " + Math.round(zoom * 100) + "%",
                16, 20, MUTED, false);
        graphics.drawString(this.font, "中键平移 · 滚轮缩放 · 右键空白新建 · Delete 删除 · Ctrl+C/V · Ctrl+Z/Y",
                16, this.height - 11, MUTED, false);
        if (statusTicks > 0) {
            graphics.drawString(this.font, status, 16, this.height - 24, 0xFF3A6B3A, false);
        }

        this.drawMenu(graphics);
        this.drawEditor(graphics);
        this.drawPickers(graphics);
        this.drawRulePicker(graphics);
    }


    /** 工程里的第一条规则 id；没有就返回 canvas。 */
    private String firstRule() {
        try {
            JsonObject root = StageProjects.readProject(project);
            if (root != null && root.has("rules") && root.get("rules").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("rules")) {
                    if (element.isJsonObject()) {
                        String id = element.getAsJsonObject().has("id")
                                ? element.getAsJsonObject().get("id").getAsString() : "";
                        if (!id.isBlank()) {
                            return id;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 读失败就用默认
        }
        return "canvas";
    }

    private String groupPrefix() {
        return groupPrefixFor(currentRule);
    }

    private static String groupPrefixFor(String id) {
        String raw = id == null ? "" : id;
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
        return "cv_" + safe + "_" + String.format("%08x", raw.hashCode());
    }

    private String safeRule() {
        return currentRule.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        try {
            JsonObject root = StageProjects.readProject(project);
            if (root != null && root.has("rules") && root.get("rules").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("rules")) {
                    if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                        String id = element.getAsJsonObject().get("id").getAsString();
                        if (!id.isBlank()) {
                            ids.add(id);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 读失败就空
        }
        if (ids.isEmpty()) {
            ids.add("canvas");
        }
        return ids;
    }

    private void switchRule(String id) {
        if (id == null || id.isBlank() || id.equals(currentRule)) {
            return;
        }
        closeEditor();
        persist();
        currentRule = id;
        resetEditorState();
        load();
        this.init();
    }

    /** 切组时清掉撤销栈和所有选中/编辑状态，防止把 A 组的内容写到 B 组。 */
    private void resetEditorState() {
        undo.clear();
        redo.clear();
        selected = null;
        boxSelected.clear();
        dragging = null;
        connectingFrom = null;
        clickedNode = null;
        clickedRow = -1;
        editorOpen = false;
        pendingApply = null;
        armedDelete = "";
    }

    private void status(String text) {
        this.status = text;
        this.statusTicks = 120;
    }

    private void drawGrid(GuiGraphics graphics) {
        int top = TOP_H;
        int bottom = this.height - BOTTOM_H;
        if (bottom <= top || zoom < 0.35F) {
            return;
        }
        graphics.enableScissor(0, top, this.width, bottom);
        try {
            double spacing = 20.0 * zoom;
            double originX = this.width / 2.0 + panX;
            double originY = (top + bottom) / 2.0 + panY;
            int firstX = (int) Math.floor(-originX / spacing);
            int lastX = (int) Math.ceil((this.width - originX) / spacing);
            int firstY = (int) Math.floor((top - originY) / spacing);
            int lastY = (int) Math.ceil((bottom - originY) / spacing);
            for (int gx = firstX; gx <= lastX; gx++) {
                for (int gy = firstY; gy <= lastY; gy++) {
                    int px = (int) Math.round(originX + gx * spacing);
                    int py = (int) Math.round(originY + gy * spacing);
                    graphics.fill(px, py, px + 1, py + 1, 0x22A9A99A);
                }
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private int screenX(Node node) {
        return (int) Math.round(this.width / 2.0 + panX + node.x * zoom);
    }

    private int screenY(Node node) {
        return (int) Math.round((TOP_H + this.height - BOTTOM_H) / 2.0 + panY + node.y * zoom);
    }

    private int nodeW() {
        return (int) Math.round(NODE_W * zoom);
    }

    private int nodeH(Node node) {
        return (int) Math.round(node.height() * zoom);
    }

    private int cardFill(Type type, boolean selected) {
        if (selected) {
            return 0xFFF3EEDC;
        }
        return switch (type) {
            case TRIGGER -> 0xFFDFC3B8;
            case GATE -> 0xFFC9D6DF;
            case DIALOGUE -> 0xFFEAE2CB;
            case INTERVAL -> 0xFFE8DAA8;
            case END -> 0xFFD2CFC4;
        };
    }

    private int headerFill(Type type) {
        return switch (type) {
            case TRIGGER -> 0xFFCBA79A;
            case GATE -> 0xFFB0C2CE;
            case DIALOGUE -> 0xFFD9CDAF;
            case INTERVAL -> 0xFFD6C488;
            case END -> 0xFFBEBBB0;
        };
    }

    private void drawNodes(GuiGraphics graphics) {
        int top = TOP_H;
        int bottom = this.height - BOTTOM_H;
        graphics.enableScissor(0, top, this.width, bottom);
        try {
            for (Node node : nodes) {
                int x = screenX(node);
                int y = screenY(node);
                int w = nodeW();
                int h = nodeH(node);
                if (x + w < 0 || y + h < top || x > this.width || y < 0) {
                    continue;
                }
                boolean sel = node == selected || boxSelected.contains(node);
                int headerH = (int) Math.round(HEADER_H * zoom);
                graphics.fill(x, y, x + w, y + h, cardFill(node.type, sel));
                graphics.renderOutline(x, y, w, h, sel ? ACCENT : FRAME);
                graphics.fill(x, y, x + w, y + headerH, headerFill(node.type));
                graphics.fill(x, y, x + 2, y + h, ACCENT);

                // 文字随缩放：把逻辑坐标搬进 pose，再整体 scale
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(x, y, 0);
                pose.scale(zoom, zoom, 1.0F);
                graphics.drawString(this.font, trim(node.title(), 24), 8, 5, INK, false);
                graphics.drawString(this.font, trim(node.subtitle(), 24), 8, 17, MUTED, false);
                if (node.expanded) {
                    drawExpanded(graphics, node, 8, HEADER_H + 4, NODE_W - 16);
                }
                drawArrow(graphics, NODE_W - 30, 8, node.expanded);
                if ("random".equals(kind) && node.type == Type.DIALOGUE) {
                    graphics.drawString(this.font, node.weight + "%", NODE_W - 42, 5, ACCENT, false);
                }
                pose.popPose();
                drawPorts(graphics, node, x, y, w, h);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /** 展开箭头：用三角块画，比字符更大更清楚。 */
    private void drawArrow(GuiGraphics graphics, int x, int y, boolean expanded) {
        int size = 7;
        if (expanded) {
            for (int i = 0; i < size; i++) {
                graphics.fill(x + i, y + i, x + size * 2 - i, y + i + 1, MUTED);
            }
        } else {
            for (int i = 0; i < size; i++) {
                graphics.fill(x + i, y + i, x + i + 1, y + size * 2 - i, MUTED);
            }
        }
    }

    private void drawExpanded(GuiGraphics graphics, Node node, int x, int y, int w) {
        int rowY = y;
        switch (node.type) {
            case TRIGGER -> {
                boolean hotkeyTrigger = "hotkey".equals(node.event);
                field(graphics, x, rowY, "事件", hotkeyTrigger ? "快捷键触发" : node.event);
                if (hotkeyTrigger) {
                    field(graphics, x, rowY + ROW_H, "按键", StageHotkeys.shortName(node.hotkey)
                            + (bindingHotkey == node ? "（请按键…）" : ""));
                } else {
                    field(graphics, x, rowY + ROW_H, "参数", node.params.isBlank() ? "（空）" : trim(node.params, 10));
                }
                field(graphics, x, rowY + 2 * ROW_H, "非", node.negate ? "开" : "关");
            }
            case GATE -> field(graphics, x, rowY, "逻辑", "any".equals(node.gate) ? "或" : "与");
            case DIALOGUE -> {
                field(graphics, x, rowY, "角色卡", node.speaker.isBlank() ? "（未选）" : trim(node.speaker, 12));
                field(graphics, x, rowY + ROW_H, "台词", trim(node.text, 12));
                field(graphics, x, rowY + 2 * ROW_H, "等按键", node.wait ? "开" : "关");
                if ("random".equals(kind)) {
                    graphics.drawString(this.font, "权重", x, rowY + 3 * ROW_H, MUTED, false);
                    drawSlider(graphics, x + 42, rowY + 3 * ROW_H + 4, w - 60, node.weight, 1, 100);
                    graphics.drawString(this.font, node.weight + "%", x + 42 + (w - 60) + 4,
                            rowY + 3 * ROW_H, ACCENT, false);
                }
            }
            case END -> {
                int row = 0;
                field(graphics, x, rowY + row * ROW_H, "效果", node.repeat ? "重复" : "截止");
                row++;
                if (node.repeat) {
                    graphics.drawString(this.font, "冷却", x, rowY + row * ROW_H, MUTED, false);
                    drawSlider(graphics, x + 42, rowY + row * ROW_H + 4, w - 60, node.cooldownSeconds, 0, 600);
                    graphics.drawString(this.font, Math.round(node.cooldownSeconds) + "s",
                            x + 42 + (w - 60) + 4, rowY + row * ROW_H, INK, false);
                    row++;
                }
                field(graphics, x, rowY + row * ROW_H, "播报", node.broadcastText());
                row++;
                if ("nearby".equals(node.broadcast)) {
                    field(graphics, x, rowY + row * ROW_H, "半径",
                            node.radiusText == null || node.radiusText.isBlank()
                                    ? "默认" : node.radiusText + " 区块");
                }
            }
            case INTERVAL -> {
                graphics.drawString(this.font, "持续", x, rowY, MUTED, false);
                drawSlider(graphics, x + 42, rowY + 4, w - 60, node.hold, 0.5, 30);
                graphics.drawString(this.font, fmt(node.hold), x + 42 + (w - 60) + 4, rowY, INK, false);
                graphics.drawString(this.font, "停滞", x, rowY + ROW_H + 2, MUTED, false);
                drawSlider(graphics, x + 42, rowY + ROW_H + 6, w - 60, node.gap, 0, 10);
                graphics.drawString(this.font, fmt(node.gap), x + 42 + (w - 60) + 4, rowY + ROW_H + 2, INK, false);
            }
            default -> {
            }
        }
    }

    /** 小滑条（逻辑坐标）。 */
    private void drawSlider(GuiGraphics graphics, int x, int y, int width, double value, double min, double max) {
        int track = Math.max(20, width - 30);
        graphics.fill(x, y + 3, x + track, y + 7, 0xFFCFC8B6);
        graphics.renderOutline(x, y + 3, track, 4, FRAME);
        float ratio = (float) ((value - min) / (max - min));
        ratio = Math.max(0, Math.min(1, ratio));
        int knob = x + Math.round(track * ratio);
        graphics.fill(knob - 2, y, knob + 3, y + 10, ACCENT);
    }

    private double sliderValue(double mouseX, int x, int width, double min, double max) {
        int track = Math.max(20, width - 30);
        double ratio = (mouseX - x) / (double) track;
        ratio = Math.max(0, Math.min(1, ratio));
        return min + (max - min) * ratio;
    }

    private void field(GuiGraphics graphics, int x, int y, String name, String value) {
        graphics.drawString(this.font, name, x, y, MUTED, false);
        graphics.drawString(this.font, value, x + 42, y, INK, false);
    }

    private void drawPorts(GuiGraphics graphics, Node node, int x, int y, int w, int h) {
        int cy = y + h / 2;
        int r = Math.max(3, Math.round(6.0F * zoom));
        if (node.type != Type.TRIGGER) {
            drawBall(graphics, x, cy, r, 0xFFD8D8CA, ACCENT);
        }
        if (node.type != Type.END) {
            drawBall(graphics, x + w, cy, r, ACCENT, ACCENT);
        }
    }

    /** 球状端口：用横条拼出圆形。 */
    private void drawBall(GuiGraphics graphics, int cx, int cy, int r, int fill, int outline) {
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            int y = cy + dy;
            graphics.fill(cx - span - 1, y, cx + span + 1, y + 1, outline);
            if (span > 0) {
                graphics.fill(cx - span, y, cx + span, y + 1, fill);
            }
        }
    }

    private void drawWires(GuiGraphics graphics) {
        for (Edge edge : edges) {
            Node from = find(edge.from());
            Node to = find(edge.to());
            if (from == null || to == null) {
                continue;
            }
            drawWire(graphics, screenX(from) + nodeW(), screenY(from) + nodeH(from) / 2,
                    screenX(to), screenY(to) + nodeH(to) / 2, WIRE);
            if ("random".equals(kind) && from.type == Type.GATE && to.type == Type.DIALOGUE) {
                graphics.drawString(this.font, to.weight + "%",
                        (screenX(from) + nodeW() + screenX(to)) / 2,
                        (screenY(from) + screenY(to)) / 2 - 6, ACCENT, false);
            }
        }
    }

    private void drawWire(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.max(40, Math.abs(x1 - x0) / 2);
        int c0x = x0 + dx;
        int c1x = x1 - dx;
        int thickness = Math.max(1, Math.round(2.0F * zoom));
        int steps = Math.max(32, Math.abs(x1 - x0) + Math.abs(y1 - y0));
        int lastX = x0;
        int lastY = y0;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float mt = 1 - t;
            float px = mt * mt * mt * x0 + 3 * mt * mt * t * c0x + 3 * mt * t * t * c1x + t * t * t * x1;
            float py = mt * mt * mt * y0 + 3 * mt * mt * t * y0 + 3 * mt * t * t * y1 + t * t * t * y1;
            int ix = Math.round(px);
            int iy = Math.round(py);
            // 把上一个点到当前点的空隙也填上，缩放再小也不会断
            int fromX = Math.min(lastX, ix);
            int fromY = Math.min(lastY, iy);
            int toX = Math.max(lastX, ix) + thickness;
            int toY = Math.max(lastY, iy) + thickness;
            graphics.fill(fromX, fromY, toX, toY, color);
            lastX = ix;
            lastY = iy;
        }
    }

    private void drawConnectPreview(GuiGraphics graphics) {
        if (connectingFrom == null) {
            return;
        }
        drawWire(graphics, screenX(connectingFrom) + nodeW(),
                screenY(connectingFrom) + nodeH(connectingFrom) / 2,
                (int) wireMouseX, (int) wireMouseY, ACCENT);
    }

    private void drawSelectionBox(GuiGraphics graphics) {
        if (!boxing) {
            return;
        }
        int x0 = (int) Math.min(boxStartX, boxEndX);
        int y0 = (int) Math.min(boxStartY, boxEndY);
        int x1 = (int) Math.max(boxStartX, boxEndX);
        int y1 = (int) Math.max(boxStartY, boxEndY);
        graphics.fill(x0, y0, x1, y1, 0x229B4E3B);
        graphics.renderOutline(x0, y0, x1 - x0, y1 - y0, ACCENT);
    }

    private static final int RULE_VISIBLE = 12;
    private static final int RULE_ROW = 18;

    private void drawRulePicker(GuiGraphics graphics) {
        if (!rulePickerOpen) {
            return;
        }
        List<String> ids = ruleIds();
        int w = 220;
        int visible = Math.min(ids.size(), RULE_VISIBLE);
        int h = 22 + visible * RULE_ROW + 26;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(x, y, x + w, y + h, 0xFFF2EDDB);
        graphics.renderOutline(x, y, w, h, FRAME);
        graphics.drawString(this.font, "选择事件组（滚轮翻页）", x + 8, y + 6, INK, false);
        for (int i = 0; i < visible; i++) {
            int index = i + ruleScroll;
            if (index >= ids.size()) {
                break;
            }
            int rowY = y + 22 + i * RULE_ROW;
            boolean active = ids.get(index).equals(currentRule);
            graphics.drawString(this.font, (active ? "▶ " : "   ") + ids.get(index), x + 8, rowY, INK, false);
        }
        // 底部按钮
        int bw = (w - 24) / 2;
        int by = y + h - 22;
        graphics.fill(x + 8, by, x + 8 + bw, by + 18, 0xFFE6E2D6);
        graphics.renderOutline(x + 8, by, bw, 18, FRAME);
        graphics.drawString(this.font, "＋ 新建", x + 8 + bw / 2 - 18, by + 5, INK, false);
        boolean armed = currentRule.equals(armedDelete)
                && System.currentTimeMillis() - armedAt < 5000;
        graphics.fill(x + 16 + bw, by, x + 16 + bw * 2, by + 18,
                armed ? 0xFFE7C9C0 : 0xFFE6E2D6);
        graphics.renderOutline(x + 16 + bw, by, bw, 18, armed ? ACCENT : FRAME);
        graphics.drawString(this.font, armed ? "确认删除？" : "－ 删除",
                x + 16 + bw + bw / 2 - 24, by + 5, ACCENT, false);
    }

    private boolean rulePickerClick(double mouseX, double mouseY) {
        if (!rulePickerOpen) {
            return false;
        }
        List<String> ids = ruleIds();
        int w = 220;
        int visible = Math.min(ids.size(), RULE_VISIBLE);
        int h = 22 + visible * RULE_ROW + 26;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            rulePickerOpen = false;
            return true;
        }
        int bw = (w - 24) / 2;
        int by = y + h - 22;
        if (mouseY >= by && mouseY <= by + 18) {
            if (mouseX >= x + 8 && mouseX <= x + 8 + bw) {
                addRule();
            } else if (mouseX >= x + 16 + bw && mouseX <= x + 16 + bw * 2) {
                long now = System.currentTimeMillis();
                if (currentRule.equals(armedDelete) && now - armedAt < 5000) {
                    deleteRule(currentRule);
                    armedDelete = "";
                    rulePickerOpen = false;
                } else {
                    armedDelete = currentRule;
                    armedAt = now;
                    status("再点一次「确认删除」才会删除 " + currentRule);
                }
            } else {
                rulePickerOpen = false;
            }
            return true;
        }
        int index = ruleScroll + (int) ((mouseY - y - 22) / RULE_ROW);
        if (index >= 0 && index < ids.size()) {
            switchRule(ids.get(index));
        }
        rulePickerOpen = false;
        return true;
    }

    /** 新建一个空事件组并切过去。 */
    private void addRule() {
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return;
        }
        JsonArray rules = root.has("rules") && root.get("rules").isJsonArray()
                ? root.getAsJsonArray("rules") : new JsonArray();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (JsonElement element : rules) {
            if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                used.add(element.getAsJsonObject().get("id").getAsString());
            }
        }
        int index = 1;
        while (used.contains("rule" + index)) {
            index++;
        }
        String id = "rule" + index;
        JsonObject rule = new JsonObject();
        rule.addProperty("id", id);
        rule.addProperty("pick", "sequential");
        rules.add(rule);
        root.add("rules", rules);
        StageProjects.writeProject(project, root);
        persist();
        currentRule = id;
        resetEditorState();
        load();
        this.init();
        status("已新建事件组 " + id);
    }

    /** 删除事件组（连带它自己的组和画布）。 */
    private void deleteRule(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        JsonObject root = StageProjects.readProject(project);
        if (root == null) {
            return;
        }
        JsonArray rules = root.has("rules") && root.get("rules").isJsonArray()
                ? root.getAsJsonArray("rules") : new JsonArray();
        int before = rules.size();
        for (int i = rules.size() - 1; i >= 0; i--) {
            JsonElement element = rules.get(i);
            if (element.isJsonObject() && id.equals(str(element.getAsJsonObject(), "id", ""))) {
                rules.remove(i);
            }
        }
        boolean found = rules.size() != before;
        root.add("rules", rules);
        String prefix = groupPrefixFor(id);
        String legacyPrefix = "cv_" + id.replaceAll("[^A-Za-z0-9_]", "_");
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            JsonArray groups = root.getAsJsonArray("groups");
            for (int i = groups.size() - 1; i >= 0; i--) {
                JsonElement element = groups.get(i);
                if (element.isJsonObject()) {
                    String gid = str(element.getAsJsonObject(), "id", "");
                    boolean mine = gid.equals(prefix) || gid.startsWith(prefix + "_")
                            || gid.equals(legacyPrefix) || gid.startsWith(legacyPrefix + "_");
                    if (mine) {
                        groups.remove(i);
                    }
                }
            }
        }
        if (root.has("canvases") && root.get("canvases").isJsonObject()) {
            root.getAsJsonObject("canvases").remove(id);
        }
        if ("canvas".equals(id) && root.has("canvas")) {
            root.remove("canvas");   // 删掉旧格式的根画布，避免“删了又复活”
        }
        StageProjects.writeProject(project, root);
        status(found ? "已删除事件组 " + id : "已清理残留 " + id);
        currentRule = firstRule();
        resetEditorState();
        load();
        this.init();
    }

    private void drawMenu(GuiGraphics graphics) {
        if (!menuOpen) {
            return;
        }
        int w = 96;
        int h = 5 * 14 + 4;
        int x = Math.min(menuX, this.width - w - 4);
        int y = Math.min(menuY, this.height - h - 4);
        graphics.fill(x, y, x + w, y + h, 0xFFEFEAD9);
        graphics.renderOutline(x, y, w, h, FRAME);
        String[] labels = {"触发节点", "对话节点", "间隔节点", "逻辑门", "结束节点"};
        for (int i = 0; i < labels.length; i++) {
            graphics.drawString(this.font, labels[i], x + 6, y + 4 + i * 14, INK, false);
        }
    }

    private void drawEditor(GuiGraphics graphics) {
        if (!editorOpen) {
            return;
        }
        int x = editX;
        int y = editY;
        int w = editW;
        int h = editH;
        graphics.fill(x, y, x + w, y + h, 0xFFF2EDDB);
        graphics.renderOutline(x, y, w, h, FRAME);
        graphics.drawString(this.font, "编辑：" + editorLabel, x + 8, y + 6, INK, false);
        graphics.drawString(this.font, "Enter/点空白 关闭 · ←→ 移动 · Shift 选择 · Ctrl+A/C/V/X", x + 8, y + 18, MUTED, false);

        int boxX = x + 8;
        int boxW = w - 16;
        int boxY = y + 32;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 18, 0xFFFFFFFF);
        graphics.renderOutline(boxX, boxY, boxW, 18, FRAME);

        String text = editorBuffer.toString();
        int caretPx = this.font.width(text.substring(0, Math.max(0, Math.min(caret, text.length()))));
        int textW = this.font.width(text);
        // 让光标保持可见
        if (caretPx - editorScroll > boxW - 12) {
            editorScroll = caretPx - (boxW - 12);
        }
        if (caretPx - editorScroll < 2) {
            editorScroll = Math.max(0, caretPx - 2);
        }
        int from = selFrom();
        int to = selTo();
        if (from != to) {
            int sx = boxX + 4 + this.font.width(text.substring(0, from)) - editorScroll;
            int ex = boxX + 4 + this.font.width(text.substring(0, to)) - editorScroll;
            graphics.fill(sx, boxY + 3, ex, boxY + 15, 0x669B4E3B);
        }
        graphics.enableScissor(boxX + 2, boxY + 1, boxX + boxW - 2, boxY + 17);
        try {
            graphics.drawString(this.font, text, boxX + 4 - editorScroll, boxY + 5, 0xFF1F1F1A, false);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = boxX + 4 + caretPx - editorScroll;
                graphics.fill(cx, boxY + 3, cx + 1, boxY + 15, 0xFF1F1F1A);
            }
        } finally {
            graphics.disableScissor();
        }
        graphics.drawString(this.font, editorBuffer.length() + "/300", x + w - 40, y + 6, MUTED, false);
    }

    private int selFrom() {
        return selAnchor < 0 ? caret : Math.min(selAnchor, caret);
    }

    private int selTo() {
        return selAnchor < 0 ? caret : Math.max(selAnchor, caret);
    }

    private String selectedText() {
        return hasSelection() ? editorBuffer.substring(selFrom(), selTo()) : editorBuffer.toString();
    }

    private boolean hasSelection() {
        return selAnchor >= 0 && selAnchor != caret;
    }

    private void deleteSelection() {
        if (!hasSelection()) {
            return;
        }
        int from = selFrom();
        int to = selTo();
        editorBuffer.delete(from, to);
        caret = from;
        selAnchor = -1;
    }

    private void insertText(String value) {
        deleteSelection();
        int room = 300 - editorBuffer.length();
        if (room <= 0) {
            return;
        }
        String add = value.length() > room ? value.substring(0, room) : value;
        editorBuffer.insert(caret, add);
        caret += add.length();
        selAnchor = -1;
    }

    private void setCaret(int position, boolean keepSelection) {
        int clamped = Math.max(0, Math.min(editorBuffer.length(), position));
        if (keepSelection) {
            if (selAnchor < 0) {
                selAnchor = caret;
            }
        } else {
            selAnchor = -1;
        }
        caret = clamped;
        if (selAnchor == caret) {
            selAnchor = -1;
        }
    }

    /** 点击输入框：按横向位置定位光标。 */
    private void caretFromMouse(double mouseX) {
        String text = editorBuffer.toString();
        int target = (int) Math.round(mouseX - (editX + 8 + 4) + editorScroll);
        int best = text.length();
        for (int i = 0; i <= text.length(); i++) {
            if (this.font.width(text.substring(0, i)) >= target) {
                int before = i == 0 ? 0 : this.font.width(text.substring(0, i - 1));
                best = (target - before) * 2 < (this.font.width(text.substring(0, i)) - before) ? i - 1 : i;
                break;
            }
        }
        setCaret(best, hasKeyShiftDown);
    }

    private void drawPickers(GuiGraphics graphics) {
        if (!cardPickerOpen && !eventPickerOpen) {
            return;
        }
        List<String> items = eventPickerOpen ? eventItems() : cardNames;
        int w = 220;
        int h = Math.min(300, 18 * items.size() + 26);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(x, y, x + w, y + h, 0xFFF2EDDB);
        graphics.renderOutline(x, y, w, h, FRAME);
        graphics.drawString(this.font, eventPickerOpen ? "选择事件" : "选择角色卡", x + 8, y + 6, INK, false);
        for (int i = 0; i < items.size(); i++) {
            int rowY = y + 20 + i * 18;
            if (rowY + 16 > y + h) {
                break;
            }
            graphics.drawString(this.font, items.get(i), x + 10, rowY + 4,
                    i % 2 == 0 ? INK : 0xFF3A3A34, false);
        }
    }

    private boolean pickerClick(double mouseX, double mouseY) {
        if (!cardPickerOpen && !eventPickerOpen) {
            return false;
        }
        List<String> items = eventPickerOpen ? eventItems() : cardNames;
        int w = 220;
        int h = Math.min(300, 18 * items.size() + 26);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            cardPickerOpen = false;
            eventPickerOpen = false;
            return true;
        }
        int index = (int) ((mouseY - y - 20) / 18);
        if (index >= 0 && index < items.size() && pickerNode != null) {
            if (eventPickerOpen) {
                if (!EVENTS[index].equals(pickerNode.event)) {
                    pickerNode.params = "";
                }
                pickerNode.event = EVENTS[index];
            } else {
                pickerNode.speaker = items.get(index);
            }
            persist();
        }
        cardPickerOpen = false;
        eventPickerOpen = false;
        pickerNode = null;
        return true;
    }

    private List<String> eventItems() {
        List<String> items = new ArrayList<>();
        for (String event : EVENTS) {
            items.add(eventLabel(event));
        }
        return items;
    }

    private Node hitNode(double mouseX, double mouseY) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node node = nodes.get(i);
            int x = screenX(node);
            int y = screenY(node);
            if (mouseX >= x - 3 && mouseX <= x + nodeW() + 3 && mouseY >= y - 3 && mouseY <= y + nodeH(node) + 3) {
                return node;
            }
        }
        return null;
    }

    private Node find(int id) {
        for (Node node : nodes) {
            if (node.id == id) {
                return node;
            }
        }
        return null;
    }

    /** 按连线顺序排列：从触发卡开始沿箭头一路往右排，没连上的节点放最后。 */
    private void autoArrange() {
        List<Node> order = new ArrayList<>();
        java.util.Set<Integer> walked = new java.util.HashSet<>();
        for (Node node : nodes) {
            if (node.type == Type.TRIGGER) {
                walkChain(node, walked, order);
            }
        }
        for (Node node : nodes) {
            if (edgeInto(node.id) == null) {
                walkChain(node, walked, order);
            }
        }
        for (Node node : nodes) {
            if (!walked.contains(node.id)) {
                order.add(node);
            }
        }
        int x = 40;
        for (Node node : order) {
            node.x = x;
            node.y = 110;
            x += NODE_W + 60;
        }
    }

    /** 沿连线深度优先走一遍，把节点按先后顺序放进 order。 */
    private void walkChain(Node node, java.util.Set<Integer> walked, List<Node> order) {
        if (!walked.add(node.id)) {
            return;
        }
        order.add(node);
        for (Edge edge : edges) {
            if (edge.from() == node.id) {
                Node next = find(edge.to());
                if (next != null) {
                    walkChain(next, walked, order);
                }
            }
        }
    }

    private int rowAt(Node node, double mouseX, double mouseY) {
        if (!node.expanded) {
            return -1;
        }
        int x = screenX(node);
        int y = screenY(node) + (int) Math.round((HEADER_H + 4) * zoom);
        if (mouseX < x + 4 || mouseX > x + nodeW() - 4) {
            return -1;
        }
        int row = (int) ((mouseY - y) / (ROW_H * zoom));
        return row >= 0 && row < node.rows() ? row : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;   // 顶栏按钮优先
        }
        if (rulePickerClick(mouseX, mouseY)) {
            return true;
        }
        if (pickerClick(mouseX, mouseY)) {
            return true;
        }
        if (editorOpen) {
            boolean inside = mouseX >= editX && mouseX <= editX + editW
                    && mouseY >= editY && mouseY <= editY + editH;
            if (!inside) {
                closeEditor();
                return true;
            }
            int boxY = editY + 32;
            if (mouseY >= boxY && mouseY <= boxY + 18
                    && mouseX >= editX + 8 && mouseX <= editX + editW - 8) {
                caretFromMouse(mouseX);
            }
            return true;
        }
        if (menuOpen && button == 0) {
            int w = 96;
            int h = 5 * 14 + 4;
            int x = Math.min(menuX, this.width - w - 4);
            int y = Math.min(menuY, this.height - h - 4);
            int index = (int) ((mouseY - y - 4) / 14);
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h && index >= 0 && index < 5) {
                beforeChange();
                Type type = switch (index) {
                    case 0 -> Type.TRIGGER;
                    case 1 -> Type.DIALOGUE;
                    case 2 -> Type.INTERVAL;
                    case 3 -> Type.GATE;
                    default -> Type.END;
                };
                Node created = add(type,
                        (mouseX - this.width / 2.0 - panX) / zoom,
                        (mouseY - (TOP_H + this.height - BOTTOM_H) / 2.0 - panY) / zoom);
                selected = created;
                menuOpen = false;
                persist();
                return true;
            }
            menuOpen = false;
        }

        if (button == 2) {
            panning = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }

        Node node = hitNode(mouseX, mouseY);
        if (button == 0) {
            if (node == null) {
                selected = null;
                boxSelected.clear();
                boxing = true;
                boxStartX = mouseX;
                boxStartY = mouseY;
                boxEndX = mouseX;
                boxEndY = mouseY;
                return true;
            }
            int x = screenX(node);
            int y = screenY(node);
            int w = nodeW();
            int h = nodeH(node);
            int cy = y + h / 2;
            double pr = Math.max(7.0, 10.0 * zoom);
            if (Math.hypot(mouseX - x, mouseY - cy) <= pr) {
                Edge into = edgeInto(node.id);
                if (into != null) {
                    beforeChange();
                    edges.remove(into);
                    connectingFrom = find(into.from());
                    wireMouseX = mouseX;
                    wireMouseY = mouseY;
                } else {
                    connectingFrom = node;
                    wireMouseX = mouseX;
                    wireMouseY = mouseY;
                }
                return true;
            }
            if (Math.hypot(mouseX - (x + w), mouseY - cy) <= pr) {
                connectingFrom = node;
                wireMouseX = mouseX;
                wireMouseY = mouseY;
                return true;
            }
            if (mouseX >= x + w - (int) Math.round(38 * zoom)
                    && mouseX <= x + w - (int) Math.round(16 * zoom)
                    && mouseY >= y + 2 && mouseY <= y + (int) Math.round(18 * zoom)) {
                beforeChange();
                node.expanded = !node.expanded;
                persist();
                return true;
            }
            int row = rowAt(node, mouseX, mouseY);
            if (row >= 0) {
                long now = System.currentTimeMillis();
                boolean doubleClick = pickerNode == null && editorOpen == false
                        && clickedNode == node && clickedRow == row && now - clickedAt < 350;
                clickedNode = node;
                clickedRow = row;
                clickedAt = now;
                beforeChange();
                if (doubleClick) {
                    if (node.type == Type.END && node.repeat && row == 1) {
                        openTextEditor(node, String.valueOf(Math.round(node.cooldownSeconds)), value -> {
                            try {
                                node.cooldownSeconds = Math.max(0, Math.min(600, Double.parseDouble(value)));
                            } catch (NumberFormatException ignored) {
                                // 输入非法就不改
                            }
                        });
                        return true;
                    }
                    if (node.type == Type.INTERVAL) {
                        if (row == 0) {
                            openTextEditor(node, String.format(Locale.ROOT, "%.1f", node.hold),
                                    value -> {
                                        try {
                                            node.hold = Math.max(0.5, Math.min(30, Double.parseDouble(value)));
                                        } catch (NumberFormatException ignored) {
                                            // 输入非法就不改
                                        }
                                    });
                        } else {
                            openTextEditor(node, String.format(Locale.ROOT, "%.1f", node.gap),
                                    value -> {
                                        try {
                                            node.gap = Math.max(0, Math.min(10, Double.parseDouble(value)));
                                        } catch (NumberFormatException ignored) {
                                            // 输入非法就不改
                                        }
                                    });
                        }
                    } else if (node.type == Type.DIALOGUE && "random".equals(kind) && row == 3) {
                        openTextEditor(node, String.valueOf(node.weight), value -> {
                            try {
                                node.weight = Math.max(1, Math.min(100, Integer.parseInt(value.trim())));
                                normalizeWeights(node);
                            } catch (NumberFormatException ignored) {
                                // 输入非法就不改
                            }
                        });
                    }
                    return true;
                }
                if (node.type == Type.END && node.repeat && row == 1) {
                    int x0 = x + (int) Math.round(42 * zoom);
                    int width = (int) Math.round((w / zoom - 60) * zoom);
                    node.cooldownSeconds = Math.round(sliderValue(mouseX, x0, width, 0, 600));
                    sliderNode = node;
                    sliderRow = 1;
                    persist();
                    return true;
                }
                if (node.type == Type.INTERVAL) {
                    int x0 = x + (int) Math.round(42 * zoom);
                    int width = (int) Math.round((w / zoom - 60) * zoom);
                    if (row == 0) {
                        node.hold = Math.round(sliderValue(mouseX, x0, width, 0.5, 30) * 10) / 10.0;
                    } else {
                        node.gap = Math.round(sliderValue(mouseX, x0, width, 0, 10) * 10) / 10.0;
                    }
                    sliderNode = node;
                    sliderRow = row;
                    persist();
                    return true;
                }
                if (node.type == Type.DIALOGUE && "random".equals(kind) && row == 3) {
                    int x0 = x + (int) Math.round(42 * zoom);
                    int width = (int) Math.round((w / zoom - 60) * zoom);
                    double ratio = (mouseX - x0) / (double) Math.max(1, width - 30);
                    node.weight = (int) Math.max(1, Math.min(100, Math.round(1 + 99 * ratio)));
                    normalizeWeights(node);
                    sliderNode = node;
                    sliderRow = row;
                    persist();
                    return true;
                }
                editRow(node, row);
                return true;
            }
            selected = node;
            boxSelected.clear();
            dragging = node;
            dragOffsetX = mouseX - x;
            dragOffsetY = mouseY - y;
            return true;
        }

        if (button == 1) {
            if (node == null) {
                menuOpen = true;
                menuX = (int) mouseX;
                menuY = (int) mouseY;
                return true;
            }
            int row = rowAt(node, mouseX, mouseY);
            if (row >= 0) {
                beforeChange();
                editRowReverse(node, row);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private Edge edgeInto(int nodeId) {
        for (Edge edge : edges) {
            if (edge.to() == nodeId) {
                return edge;
            }
        }
        return null;
    }

    private void editRow(Node node, int row) {
        switch (node.type) {
            case TRIGGER -> {
                if (row == 0) {
                    openEventPicker(node);
                } else if (row == 1) {
                    if ("hotkey".equals(node.event)) {
                        bindingHotkey = node;
                        status("请按一个键绑定快捷键（Esc 取消）");
                    } else {
                        openTextEditor(node, node.params, value -> node.params = value);
                    }
                } else {
                    node.negate = !node.negate;
                    persist();
                }
            }
            case GATE -> {
                node.gate = "any".equals(node.gate) ? "all" : "any";
                persist();
            }
            case DIALOGUE -> {
                switch (row) {
                    case 0 -> openCardPicker(node);
                    case 1 -> openTextEditor(node, node.text, value -> node.text = value);
                    case 2 -> {
                        node.wait = !node.wait;
                        persist();
                    }
                    default -> {
                        // 权重：滑条在 click 里处理，双击进输入
                    }
                }
            }
            case END -> {
                int cooldownRow = node.repeat ? 1 : -1;
                int broadcastRow = 1 + (node.repeat ? 1 : 0);
                int radiusRow = broadcastRow + 1;
                if (row == 0) {
                    node.repeat = !node.repeat;
                } else if (row == broadcastRow) {
                    node.broadcast = switch (node.broadcast) {
                        case "nearby" -> "self";
                        case "self" -> "all";
                        default -> "nearby";
                    };
                } else if ("nearby".equals(node.broadcast) && row == radiusRow) {
                    openTextEditor(node, node.radiusText, value -> node.radiusText = value);
                    return;
                } else if (row == cooldownRow) {
                    return;
                }
                persist();
            }
            case INTERVAL -> {
                // 滑条在 click 里处理，双击进输入
            }
            default -> {
            }
        }
    }

    /** 右键：数值减一点（持续/停滞/间隔/权重）。 */
    private void editRowReverse(Node node, int row) {
        switch (node.type) {
            case DIALOGUE -> {
                if ("random".equals(kind) && row == 3) {
                    node.weight = Math.max(1, node.weight - 5);
                    normalizeWeights(node);
                    persist();
                }
            }
            case INTERVAL -> {
                if (row == 0) {
                    node.hold = Math.max(0.5, Math.round((node.hold - 0.1) * 10) / 10.0);
                } else {
                    node.gap = Math.max(0, Math.round((node.gap - 0.1) * 10) / 10.0);
                }
                persist();
            }
            default -> {
            }
        }
    }

    /** 随机组：改一个权重后，其它按比例缩放到总和 100。 */
    private void normalizeWeights(Node changed) {
        List<Node> pool = new ArrayList<>();
        for (Node node : nodes) {
            if (node.type == Type.DIALOGUE) {
                pool.add(node);
            }
        }
        if (pool.size() <= 1) {
            changed.weight = 100;
            return;
        }
        int others = 0;
        for (Node node : pool) {
            if (node != changed) {
                others += Math.max(1, node.weight);
            }
        }
        int remain = 100 - changed.weight;
        if (remain < pool.size() - 1) {
            changed.weight = 100 - (pool.size() - 1);
            remain = pool.size() - 1;
        }
        int assigned = 0;
        for (int i = 0; i < pool.size(); i++) {
            Node node = pool.get(i);
            if (node == changed) {
                continue;
            }
            int value = others <= 0 ? 1 : Math.max(1, Math.round((float) remain * node.weight / others));
            node.weight = value;
            assigned += value;
        }
        int diff = remain - assigned;
        if (diff != 0) {
            for (Node node : pool) {
                if (node != changed) {
                    node.weight = Math.max(1, node.weight + diff);
                    break;
                }
            }
        }
    }

    private interface Apply {
        void apply(String value);
    }

    private void openTextEditor(Node node, String value, Apply apply) {
        editing = node;
        editorOpen = true;
        editorLabel = node.title();
        editorTarget = value == null ? "" : value;
        editorBuffer.setLength(0);
        editorBuffer.append(editorTarget);
        caret = editorBuffer.length();
        selAnchor = -1;
        editorScroll = 0;
        editX = (this.width - editW) / 2;
        editY = (this.height - editH) / 2;
        this.pendingApply = apply;
    }

    private Apply pendingApply;

    private void openCardPicker(Node node) {
        cardNames.clear();
        try {
            cardNames.addAll(PlayerCards.list());
        } catch (RuntimeException ignored) {
            // 读卡列表失败就空着
        }
        pickerNode = node;
        cardPickerOpen = true;
    }

    private void openEventPicker(Node node) {
        pickerNode = node;
        eventPickerOpen = true;
    }

    private void closeEditor() {
        if (!editorOpen) {
            return;
        }
        editorOpen = false;
        if (pendingApply != null) {
            pendingApply.apply(editorBuffer.toString().trim());
            pendingApply = null;
        }
        editing = null;
        persist();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && sliderNode != null) {
            sliderNode = null;
            sliderRow = -1;
            persist();
            return true;
        }
        if (button == 2) {
            panning = false;
            return true;
        }
        if (button == 0) {
            if (boxing) {
                boxing = false;
                boxSelected.clear();
                for (Node node : nodes) {
                    int x = screenX(node);
                    int y = screenY(node);
                    if (x + nodeW() >= Math.min(boxStartX, boxEndX) && x <= Math.max(boxStartX, boxEndX)
                            && y + nodeH(node) >= Math.min(boxStartY, boxEndY) && y <= Math.max(boxStartY, boxEndY)) {
                        boxSelected.add(node);
                    }
                }
                return true;
            }
            if (connectingFrom != null) {
                Node target = hitNode(mouseX, mouseY);
                if (target != null && target != connectingFrom && target.type != Type.TRIGGER) {
                    edges.removeIf(edge -> edge.to() == target.id);
                    edges.add(new Edge(connectingFrom.id, target.id));
                    persist();
                }
                connectingFrom = null;
                return true;
            }
            if (dragging != null) {
                dragging = null;
                persist();
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (sliderNode != null && button == 0) {
            int x0 = screenX(sliderNode) + (int) Math.round(42 * zoom);
            int width = (int) Math.round((NODE_W - 60) * zoom);
            if (sliderNode.type == Type.END && sliderNode.repeat && sliderRow == 1) {
                sliderNode.cooldownSeconds = Math.round(sliderValue(mouseX, x0, width, 0, 600));
            } else if (sliderNode.type == Type.INTERVAL) {
                if (sliderRow == 0) {
                    sliderNode.hold = Math.round(sliderValue(mouseX, x0, width, 0.5, 30) * 10) / 10.0;
                } else if (sliderRow == 1) {
                    sliderNode.gap = Math.round(sliderValue(mouseX, x0, width, 0, 10) * 10) / 10.0;
                }
            } else if (sliderNode.type == Type.DIALOGUE && "random".equals(kind) && sliderRow == 3) {
                double ratio = (mouseX - x0) / (double) Math.max(1, width - 30);
                sliderNode.weight = (int) Math.max(1, Math.min(100, Math.round(1 + 99 * ratio)));
                normalizeWeights(sliderNode);
            }
            persist();
            return true;
        }
        if (panning && button == 2) {
            panX += mouseX - lastMouseX;
            panY += mouseY - lastMouseY;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if (boxing) {
            boxEndX = mouseX;
            boxEndY = mouseY;
            return true;
        }
        if (connectingFrom != null) {
            wireMouseX = mouseX;
            wireMouseY = mouseY;
            return true;
        }
        if (dragging != null) {
            dragging.x = (mouseX - this.width / 2.0 - panX - dragOffsetX) / zoom;
            dragging.y = (mouseY - (TOP_H + this.height - BOTTOM_H) / 2.0 - panY - dragOffsetY) / zoom;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rulePickerOpen && scrollY != 0) {
            int max = Math.max(0, ruleIds().size() - RULE_VISIBLE);
            ruleScroll = Math.max(0, Math.min(max, ruleScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        float oldZoom = zoom;
        zoom = (float) Math.max(0.2, Math.min(2.0, zoom * (scrollY > 0 ? 1.1 : 1.0 / 1.1)));
        if (oldZoom != zoom) {
            double cx = this.width / 2.0;
            double cy = (TOP_H + this.height - BOTTOM_H) / 2.0;
            panX = (mouseX - cx) - (mouseX - cx - panX) * (zoom / oldZoom);
            panY = (mouseY - cy) - (mouseY - cy - panY) * (zoom / oldZoom);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (bindingHotkey != null) {
            if (keyCode == 256) {   // Esc：取消绑定
                bindingHotkey = null;
                status("已取消快捷键绑定");
                return true;
            }
            bindingHotkey.hotkey = com.mojang.blaze3d.platform.InputConstants
                    .getKey(keyCode, scanCode).getName();
            status("已绑定快捷键：" + StageHotkeys.shortName(bindingHotkey.hotkey));
            bindingHotkey = null;
            persist();
            return true;
        }
        boolean ctrl = (modifiers & 2) != 0;
        boolean shift = (modifiers & 1) != 0;
        if (editorOpen) {
            hasKeyShiftDown = shift;
            if (keyCode == 257 || keyCode == 256) {   // Enter / Esc
                closeEditor();
                return true;
            }
            if (ctrl && keyCode == 67) {   // Ctrl+C
                setClipboard(selectedText());
                return true;
            }
            if (ctrl && keyCode == 88) {   // Ctrl+X
                setClipboard(selectedText());
                if (hasSelection()) {
                    deleteSelection();
                } else {
                    editorBuffer.setLength(0);
                    caret = 0;
                    selAnchor = -1;
                }
                return true;
            }
            if (ctrl && keyCode == 86) {   // Ctrl+V
                pasteFromClipboard();
                return true;
            }
            if (ctrl && keyCode == 65) {   // Ctrl+A
                selAnchor = 0;
                caret = editorBuffer.length();
                return true;
            }
            if (keyCode == 259) {   // Backspace
                if (hasSelection()) {
                    deleteSelection();
                } else if (caret > 0) {
                    editorBuffer.deleteCharAt(caret - 1);
                    caret--;
                }
                return true;
            }
            if (keyCode == 261) {   // Delete
                if (hasSelection()) {
                    deleteSelection();
                } else if (caret < editorBuffer.length()) {
                    editorBuffer.deleteCharAt(caret);
                }
                return true;
            }
            if (keyCode == 263) {   // ←
                setCaret(hasSelection() && !shift ? selFrom() : caret - 1, shift);
                return true;
            }
            if (keyCode == 262) {   // →
                setCaret(hasSelection() && !shift ? selTo() : caret + 1, shift);
                return true;
            }
            if (keyCode == 268) {   // Home
                setCaret(0, shift);
                return true;
            }
            if (keyCode == 269) {   // End
                setCaret(editorBuffer.length(), shift);
                return true;
            }
            return true;
        }

        if (ctrl) {
            if (keyCode == 67) {
                copySelection();
                return true;
            }
            if (keyCode == 86) {
                paste();
                return true;
            }
            if (keyCode == 90) {
                undoAction();
                return true;
            }
            if (keyCode == 89) {
                redoAction();
                return true;
            }
        }
        if (keyCode == 261 || keyCode == 259) {
            if (!boxSelected.isEmpty()) {
                beforeChange();
                for (Node node : new ArrayList<>(boxSelected)) {
                    removeNode(node);
                }
                boxSelected.clear();
                persist();
                return true;
            }
            if (selected != null) {
                beforeChange();
                removeNode(selected);
                selected = null;
                persist();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void removeNode(Node node) {
        nodes.remove(node);
        edges.removeIf(edge -> edge.from() == node.id || edge.to() == node.id);
        if (selected == node) {
            selected = null;
        }
        if (dragging == node) {
            dragging = null;
        }
    }

    private void copySelection() {
        clipboard.clear();
        if (!boxSelected.isEmpty()) {
            clipboard.addAll(boxSelected);
        } else if (selected != null) {
            clipboard.add(selected);
        }
        status("已复制 " + clipboard.size() + " 个节点");
    }

    private void paste() {
        if (clipboard.isEmpty()) {
            return;
        }
        beforeChange();
        List<Node> created = new ArrayList<>();
        for (Node source : clipboard) {
            Node copy = add(source.type, source.x + 24, source.y + 24);
            copy.expanded = source.expanded;
            copy.event = source.event;
            copy.params = source.params;
            copy.hotkey = source.hotkey;
            copy.negate = source.negate;
            copy.gate = source.gate;
            copy.speaker = source.speaker;
            copy.text = source.text;
            copy.hold = source.hold;
            copy.gap = source.gap;
            copy.wait = source.wait;
            copy.interval = source.interval;
            copy.weight = source.weight;
            copy.repeat = source.repeat;
            copy.cooldownSeconds = source.cooldownSeconds;
            copy.broadcast = source.broadcast;
            copy.radiusText = source.radiusText;
            created.add(copy);
        }
        boxSelected.clear();
        boxSelected.addAll(created);
        selected = created.get(created.size() - 1);
        persist();
        status("已粘贴 " + created.size() + " 个节点");
    }


    /** 复制到系统剪贴板。 */
    private void setClipboard(String text) {
        try {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(text == null ? "" : text);
            status("已复制 " + (text == null ? 0 : text.length()) + " 字");
        } catch (RuntimeException ignored) {
            // 剪贴板不可用就忽略
        }
    }

    /** 从系统剪贴板粘贴（过滤换行/控制字符，限长 300）。 */
    private void pasteFromClipboard() {
        try {
            String text = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
            if (text == null || text.isEmpty()) {
                return;
            }
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < text.length() && clean.length() + editorBuffer.length() < 300; i++) {
                char ch = text.charAt(i);
                if (ch >= 32 && ch != 127) {
                    clean.append(ch);
                }
            }
            if (clean.length() > 0) {
                insertText(clean.toString());
                status("已粘贴 " + clean.length() + " 字");
            }
        } catch (RuntimeException ignored) {
            // 剪贴板不可用就忽略
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editorOpen) {
            if (codePoint >= 32) {
                insertText(String.valueOf(codePoint));
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void tick() {
        if (statusTicks > 0) {
            statusTicks--;
        }
    }

    @Override
    public void onClose() {
        closeEditor();
        persist();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static String str(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static double dbl(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int num(JsonObject object, String key, int fallback) {
        return (int) dbl(object, key, fallback);
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        try {
            return element != null && !element.isJsonNull() && element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
