package cn.acechat.client;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 剧本演出播放器（实验版）：按 tick 时间轴把一组台词推给 {@link CommsState#stageShow}。
 *
 * <p>支持：单组播放、按规则取组（sequential / random / weighted / cycle，含 noRepeat）、
 * 暂停 / 继续 / 下一句、段内 {@code wait} 停顿等按键、整段循环；退出世界自动复位。
 */
public final class StagePlayer {
    private static final List<StageScripts.Line> LINES = new ArrayList<>();
    private static int index;
    private static long tick;
    private static boolean paused;
    private static boolean waiting;
    private static boolean loop;
    private static String projectName = "";
    private static String groupName = "";
    private static String ruleName = "";
    private static String lastError = "";

    private static final Random RNG = new Random();
    private static final Map<String, String> LAST_PICK = new HashMap<>();
    private static final Map<String, Integer> CYCLE = new HashMap<>();

    public static boolean isPlaying() {
        return !LINES.isEmpty();
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isWaiting() {
        return waiting;
    }

    public static String projectName() {
        return projectName;
    }

    public static String groupName() {
        return groupName;
    }

    public static String ruleName() {
        return ruleName;
    }

    public static String lastError() {
        return lastError;
    }

    public static String progress() {
        return String.format(Locale.ROOT, "%s · %s %d/%d",
                projectName.isEmpty() ? "-" : projectName,
                groupName.isEmpty() ? "-" : groupName,
                Math.min(index, LINES.size()), LINES.size());
    }

    /** 直接播一条（不进工程），用于快速验证。 */
    public static void line(Minecraft minecraft, String speaker, String text, int holdTicks) {
        stop();
        StageScripts.Line line = new StageScripts.Line(speaker, text, 0, Math.max(4, holdTicks), false, "", "");
        LINES.clear();
        LINES.add(line);
        projectName = "";
        groupName = "(单条)";
        ruleName = "";
        CommsState.stageShow(minecraft, speaker, text, line.holdTick());
        index = 1;
        tick = 0L;
    }

    /** 播放某个工程的某个组。 */
    public static boolean playGroup(Minecraft minecraft, String project, String group) {
        lastError = "";
        try {
            StageScripts.Project loaded = StageScripts.load(project);
            StageScripts.Group target = loaded.group(group);
            if (target == null) {
                lastError = "没有这个组: " + group + "（可用: " + String.join(", ", loaded.groups().keySet()) + "）";
                return false;
            }
            start(loaded, target, "", minecraft);
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? "读取失败" : e.getMessage();
            return false;
        }
    }

    /** 按规则取组并播放（顺序 / 随机 / 权重 / 轮换）。 */
    public static boolean playRule(Minecraft minecraft, String project, String ruleId) {
        lastError = "";
        try {
            StageScripts.Project loaded = StageScripts.load(project);
            StageScripts.Rule rule = findRule(loaded, ruleId);
            if (rule == null) {
                lastError = "没有这条规则: " + ruleId + "（可用: " + rulesOf(loaded) + "）";
                return false;
            }
            if (rule.play().isEmpty()) {
            lastError = "规则没有可播放的组（画布可能已清空对白）";
            return false;
        }
        String key = project + "::" + rule.id();
            String last = LAST_PICK.get(key);
            int cycle = CYCLE.getOrDefault(key, 0);
            int picked;
            try {
                picked = StagePick.choose(rule, RNG, last, cycle);
            } catch (RuntimeException e) {
                picked = 0;
            }
            String groupId = rule.play().get(Math.max(0, Math.min(picked, rule.play().size() - 1)));
            StageScripts.Group target = loaded.group(groupId);
            if (target == null) {
                lastError = "规则指向的组不存在: " + groupId;
                return false;
            }
            LAST_PICK.put(key, groupId);
            CYCLE.put(key, cycle + 1);
            start(loaded, target, rule.id(), minecraft);
            return true;
        } catch (IOException e) {
            lastError = e.getMessage() == null ? "读取失败" : e.getMessage();
            return false;
        }
    }

    /** 播工程：只有一条规则时直接用规则，否则需指定。 */
    public static boolean play(Minecraft minecraft, String project) {
        lastError = "";
        try {
            StageScripts.Project loaded = StageScripts.load(project);
            if (loaded.rules().size() == 1) {
                return playRule(minecraft, project, loaded.rules().get(0).id());
            }
            lastError = "工程有 " + loaded.rules().size() + " 条规则，请指定：/acechat stage play "
                    + project + " <规则id>（可用: " + rulesOf(loaded) + "）";
            return false;
        } catch (IOException e) {
            lastError = e.getMessage() == null ? "读取失败" : e.getMessage();
            return false;
        }
    }

    /** 工程里的组列表（命令反馈用）。 */
    public static String groupsOf(String project) {
        try {
            return groupsOf(StageScripts.load(project));
        } catch (IOException e) {
            return e.getMessage() == null ? "读取失败" : e.getMessage();
        }
    }

    /** 工程里的规则列表（命令反馈用）。 */
    public static String rulesOf(String project) {
        try {
            return rulesOf(StageScripts.load(project));
        } catch (IOException e) {
            return e.getMessage() == null ? "读取失败" : e.getMessage();
        }
    }

    private static String groupsOf(StageScripts.Project project) {
        StringBuilder text = new StringBuilder();
        project.groups().forEach((id, group) -> {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(id).append('(').append(group.lines().size()).append("句)");
        });
        return text.length() == 0 ? "（空）" : text.toString();
    }

    private static String rulesOf(StageScripts.Project project) {
        StringBuilder text = new StringBuilder();
        for (StageScripts.Rule rule : project.rules()) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(rule.id()).append('[').append(rule.pick()).append(": ")
                    .append(String.join(",", rule.play())).append(']');
        }
        return text.length() == 0 ? "（空）" : text.toString();
    }

    private static StageScripts.Rule findRule(StageScripts.Project project, String ruleId) {
        for (StageScripts.Rule rule : project.rules()) {
            if (rule.id().equals(ruleId)) {
                return rule;
            }
        }
        return null;
    }

    private static void start(StageScripts.Project project, StageScripts.Group group, String rule, Minecraft minecraft) {
        LINES.clear();
        LINES.addAll(group.lines());
        index = 0;
        tick = 0L;
        paused = false;
        waiting = false;
        loop = project.loop();
        projectName = project.name() == null || project.name().isBlank() ? projectName : project.name();
        groupName = group.id();
        ruleName = rule;
        CommsState.stageFreeze(false);
        fireDueCues(minecraft);
    }

    /** 播放服务端下发的一段台词（已解析好组与台词）。 */
    public static void playReceived(net.minecraft.client.Minecraft minecraft, String project,
                                    String group, String rule, boolean loopFlag,
                                    java.util.List<StageScripts.Line> lines) {
        LINES.clear();
        LINES.addAll(lines);
        index = 0;
        tick = lines.isEmpty() ? 0L : lines.get(0).startTick();
        paused = false;
        waiting = false;
        loop = loopFlag;
        projectName = project == null ? "" : project;
        groupName = group == null ? "" : group;
        ruleName = rule == null ? "" : rule;
        CommsState.stageFreeze(false);
        if (minecraft != null) {
            fireDueCues(minecraft);
        }
    }

    public static void pause() {
        if (!LINES.isEmpty()) {
            paused = true;
            CommsState.stageFreeze(true);
        }
    }

    public static void resume() {
        paused = false;
        CommsState.stageFreeze(waiting);
    }

    /** 手动推进：等待中则继续；否则立刻结束当前句、跳到下一句。 */
    public static void next(Minecraft minecraft) {
        if (LINES.isEmpty()) {
            return;
        }
        if (waiting) {
            waiting = false;
            CommsState.stageFreeze(false);
            return;
        }
        if (index > 0) {
            StageScripts.Line current = LINES.get(Math.min(index, LINES.size()) - 1);
            tick = Math.max(tick, (long) current.startTick() + current.holdTick());
        }
        fireDueCues(minecraft);
    }

    public static void stop() {
        LINES.clear();
        index = 0;
        tick = 0L;
        paused = false;
        waiting = false;
        loop = false;
        projectName = "";
        groupName = "";
        ruleName = "";
        CommsState.stageFreeze(false);
        CommsState.stageStop();
    }

    /** 退出世界/断线：静默复位。 */
    public static void reset() {
        LINES.clear();
        index = 0;
        tick = 0L;
        paused = false;
        waiting = false;
        loop = false;
        projectName = "";
        groupName = "";
        ruleName = "";
        CommsState.stageFreeze(false);
    }

    public static void tick(Minecraft minecraft) {
        if (LINES.isEmpty()) {
            return;
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            CommsState.stageStop();
            return;
        }
        if (minecraft.isPaused() || paused || waiting) {
            return;
        }
        tick++;
        fireDueCues(minecraft);
        if (index >= LINES.size() && !CommsState.stageActive()) {
            if (loop) {
                index = 0;
                tick = 0L;
                fireDueCues(minecraft);
            } else {
                reset();
            }
        }
    }

    private static void fireDueCues(Minecraft minecraft) {
        while (index < LINES.size() && tick >= LINES.get(index).startTick()) {
            StageScripts.Line line = LINES.get(index);
            CommsState.stageShow(minecraft, line.speaker(), line.text(), line.holdTick());
            index++;
            if (line.waitForNext()) {
                waiting = true;
                CommsState.stageFreeze(true);
                return;
            }
        }
    }

    private StagePlayer() {
    }
}
