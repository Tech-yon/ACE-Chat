package cn.acechat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 触发器快捷键：每条「快捷键触发」的触发器可以在工程文件里带
 * {@code "hotkey": "key.keyboard.j"}；按该键就本地播它所在的事件组。
 */
public final class StageHotkeys {
    private static final Map<String, String[]> INDEX = new HashMap<>();
    private static long lastScan;

    /** 按键时调用：命中就播放，返回是否处理。 */
    public static boolean press(net.minecraft.client.Minecraft minecraft, int key, int scancode) {
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        String name = com.mojang.blaze3d.platform.InputConstants.getKey(key, scancode).getName();
        rebuildIfStale();
        String[] target = INDEX.get(name);
        if (target == null) {
            return false;
        }
        int index = target.length > 2 ? Integer.parseInt(target[2]) : 0;
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new cn.acechat.network.ManualPayload(target[0], target[1], index));
            return true;
        } catch (RuntimeException e) {
            // 服务端没有通道（或单人未连接）时，退化为本地直接播
            return StagePlayer.playRule(minecraft, target[0], target[1]);
        }
    }

    /** 显示用（键名 → "工程 / 事件组"）。 */
    public static Map<String, String> snapshot() {
        rebuildIfStale();
        Map<String, String> view = new HashMap<>();
        INDEX.forEach((key, target) -> view.put(key, target[0] + " / " + target[1]));
        return view;
    }

    /** 把 key.keyboard.x 转成简短显示名：num1 / N / 0 / 【 / ~ … */
    public static String shortName(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return "";
        }
        String name = keyName;
        if (name.startsWith("key.keyboard.")) {
            name = name.substring("key.keyboard.".length());
        } else if (name.startsWith("key.mouse.")) {
            return "鼠标" + name.substring("key.mouse.".length());
        }
        return switch (name) {
            case "left.bracket" -> "【";
            case "right.bracket" -> "】";
            case "grave.accent" -> "~";
            case "minus" -> "-";
            case "equal" -> "=";
            case "period" -> ".";
            case "comma" -> ",";
            case "slash" -> "/";
            case "backslash" -> "\\";
            case "semicolon" -> ";";
            case "apostrophe" -> "'";
            case "space" -> "空格";
            case "enter" -> "回车";
            case "tab" -> "Tab";
            case "backspace" -> "退格";
            case "escape" -> "Esc";
            case "left.shift", "right.shift" -> "Shift";
            case "left.control", "right.control" -> "Ctrl";
            case "left.alt", "right.alt" -> "Alt";
            case "caps.lock" -> "Caps";
            case "up" -> "↑";
            case "down" -> "↓";
            case "left" -> "←";
            case "right" -> "→";
            default -> {
                if (name.startsWith("keypad.")) {
                    yield "num" + name.substring("keypad.".length());
                }
                if (name.length() <= 3 && name.startsWith("f") && name.chars().skip(1).allMatch(Character::isDigit)) {
                    yield name.toUpperCase(java.util.Locale.ROOT);
                }
                yield name.length() == 1 ? name.toUpperCase(java.util.Locale.ROOT) : name;
            }
        };
    }

    /** 绑定/解绑后让索引立刻重建。 */
    public static void invalidate() {
        lastScan = 0L;
    }

    private static void rebuildIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastScan < 1000L) {
            return;
        }
        lastScan = now;
        INDEX.clear();
        try (var stream = java.nio.file.Files.list(StageScripts.dir())) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String project = fileName.substring(0, fileName.length() - 5);
                        JsonObject root = StageProjects.readProject(project);
                        if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) {
                            return;
                        }
                        JsonArray rules = root.getAsJsonArray("rules");
                        for (JsonElement element : rules) {
                            if (!element.isJsonObject()) {
                                continue;
                            }
                            JsonObject rule = element.getAsJsonObject();
                            if (!rule.has("id") || !rule.has("when") || !rule.get("when").isJsonObject()) {
                                continue;
                            }
                            JsonObject when = rule.getAsJsonObject("when");
                            if (!when.has("triggers") || !when.get("triggers").isJsonArray()) {
                                continue;
                            }
                            String ruleId = rule.get("id").getAsString();
                            int triggerIndex = 0;
                            for (JsonElement triggerElement : when.getAsJsonArray("triggers")) {
                                if (!triggerElement.isJsonObject()) {
                                    continue;
                                }
                                JsonObject trigger = triggerElement.getAsJsonObject();
                                if (trigger.has("hotkey")) {
                                    String hotkey = trigger.get("hotkey").getAsString();
                                    if (!hotkey.isBlank()) {
                                        INDEX.put(hotkey, new String[]{project, ruleId, String.valueOf(triggerIndex)});
                                    }
                                }
                                triggerIndex++;
                            }
                        }
                    });
        } catch (Exception ignored) {
            // 目录读不了就当没绑定
        }
    }

    private StageHotkeys() {
    }
}
