package cn.acechat.server;

import cn.acechat.AceChat;
import cn.acechat.client.StageScripts;
import cn.acechat.network.StagePayload;
import cn.acechat.stage.StageEngine;
import cn.acechat.client.StagePick;
import cn.acechat.stage.StageState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 服务端事件检测（实验版）：把 Minecraft 事件变成 {@link StageEngine.Signal}，
 * 交给条件引擎匹配工程里的规则，命中后抽组、算接收者、下发 {@link StagePayload}。
 *
 * <p>事件源：进服/退服/换维度/复活/受伤/死亡/实体加入世界/聊天 + 每 20 tick 轮询
 * （低血量 / 进区域 / 定时）。一次性与冷却由 {@link StageState} 记录在
 * {@code <存档>/acechat_stage.json}。
 */
@EventBusSubscriber(modid = AceChat.MOD_ID)
public final class ServerStageTriggers {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("acechat");
    private static final int POLL_TICKS = 20;
    private static final int MAX_BUFFER_WINDOW = 20 * 60;
    private static final int MAX_SENDS_PER_SIGNAL = 8;

    private record Cached(StageScripts.Project project, long mtime) {
    }

    /** 扁平触发器索引：每条触发器条件 + 它自己的播放组。 */
    private static final class TriggerGroups {
        final List<StageEngine.Cond> conds;
        final List<String> groups;

        TriggerGroups(List<StageEngine.Cond> conds, List<String> groups) {
            this.conds = conds;
            this.groups = groups;
        }
    }

    private static final Map<String, Cached> PROJECTS = new LinkedHashMap<>();
    private static final Map<String, StageEngine.Cond> CONDITIONS = new HashMap<>();
    /** 每条触发器各自的播放组（画布保存时写入 when.triggers[i].group）。 */
    private static final Map<String, TriggerGroups> TRIGGER_GROUPS = new HashMap<>();
    private static final Deque<StageEngine.Signal> BUFFER = new ArrayDeque<>();
    /**
     * 每条规则已经“用掉”的一次性事件信号：同一个击杀/聊天/死亡事件只允许触发同一条规则一次。
     * 周期信号（tick.every / player.lowHealth）不消耗，仍然可以按冷却重复。
     */
    private static final Map<StageEngine.Signal, Set<String>> CONSUMED = new java.util.IdentityHashMap<>();
    private static final Random RNG = new Random();
    private static final Map<String, String> LAST_PICK = new HashMap<>();
    private static final Map<String, Integer> CYCLE = new HashMap<>();
    /** 优先级队列上限。 */
    private static final int MAX_QUEUE = 5;
    /** 群体播放共用一条队列；个人化按玩家各一条。 */
    private static final java.util.UUID GROUP_QUEUE = new java.util.UUID(0L, 0L);
    private static final Map<java.util.UUID, java.util.Deque<Pending>> QUEUES = new HashMap<>();
    private static final Map<java.util.UUID, Long> BUSY_UNTIL = new HashMap<>();
    private static final Map<java.util.UUID, Pending> CURRENT = new HashMap<>();

    private static StageState state;
    private static Path statePath;
    private static long lastScan = -1L;
    private static long lastSave;

    private ServerStageTriggers() {
    }

    // ───────────────────────── 事件源 ─────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        ensureState(server);
        refreshProjects(now);
        while (!BUFFER.isEmpty() && now - BUFFER.peekFirst().tick() > MAX_BUFFER_WINDOW) {
            CONSUMED.remove(BUFFER.removeFirst());
        }
        if (now % POLL_TICKS == 0) {
            pollSignals(server, now);
        }
        // 心跳每个 tick 都发（带当前 tick 号），规则里的 every 决定隔多少 tick 成立
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (!online.isEmpty()) {
            fire(playerSignal("tick.every", online.get(0)));
        }
        flushQueues(server, now);
        if (now - lastSave > 600) {
            lastSave = now;
            state.save(statePath);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (state != null && statePath != null) {
            state.save(statePath);
        }
        BUSY_UNTIL.clear();
        QUEUES.clear();
        CURRENT.clear();
        state = null;
        BUFFER.clear();
        CONSUMED.clear();
        lastSave = 0L;
        lastScan = -1L;
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            fire(playerSignal("player.join", player));
            // 把工程里用到的角色卡/皮肤/立绘清单发给这个玩家（客户端不需要本地工程文件）
            cn.acechat.server.ServerAssets.sendManifest(player);
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            fire(playerSignal("player.leave", player));
        }
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            fire(playerSignal("player.enterDimension", player));
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            fire(playerSignal("player.respawn", player));
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim.level().isClientSide()) {
            return;
        }
        MinecraftServer server = victim.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer nearest = nearestPlayer(server, victim.getX(), victim.getY(), victim.getZ());
        fire(new StageEngine.Signal("entity.hurt",
                nearest == null ? "" : nearest.getGameProfile().getName(),
                nearest == null ? null : nearest.getUUID(),
                victim.getX(), victim.getY(), victim.getZ(),
                victim.level().dimension().location().toString(),
                entityId(victim), victim.getHealth(), victim.getMaxHealth(), "",
                server.getTickCount(), victim.getUUID().toString(),
                victim.getCustomName() == null ? "" : victim.getCustomName().getString()));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim.level().isClientSide()) {
            return;
        }
        MinecraftServer server = victim.getServer();
        if (server == null) {
            return;
        }
        Entity killer = event.getSource().getEntity();
        ServerPlayer nearest = nearestPlayer(server, victim.getX(), victim.getY(), victim.getZ());
        String playerName = "";
        UUID playerId = null;
        if (killer instanceof ServerPlayer killerPlayer) {
            playerName = killerPlayer.getGameProfile().getName();
            playerId = killerPlayer.getUUID();
        } else if (nearest != null) {
            playerName = nearest.getGameProfile().getName();
            playerId = nearest.getUUID();
        }
        fire(new StageEngine.Signal("entity.killed", playerName, playerId,
                victim.getX(), victim.getY(), victim.getZ(),
                victim.level().dimension().location().toString(),
                entityId(victim), 0f, 0f, "", server.getTickCount(),
                    victim.getUUID().toString(),
                    victim.getCustomName() == null ? "" : victim.getCustomName().getString()));
        if (victim instanceof ServerPlayer dead) {
            fire(playerSignal("player.death", dead));
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        MinecraftServer server = living.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer nearest = nearestPlayer(server, living.getX(), living.getY(), living.getZ());
        if (nearest == null) {
            return;
        }
        fire(new StageEngine.Signal("entity.spawn",
                nearest.getGameProfile().getName(), nearest.getUUID(),
                living.getX(), living.getY(), living.getZ(),
                living.level().dimension().location().toString(),
                entityId(living), 0f, 0f, "", server.getTickCount(),
                    living.getUUID().toString(),
                    living.getCustomName() == null ? "" : living.getCustomName().getString()));
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.server;
        String text = event.getMessage() == null ? "" : event.getMessage().getString();
        fire(new StageEngine.Signal("player.chatContains",
                player.getGameProfile().getName(), player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.level().dimension().location().toString(), "", 0f, 0f, text,
                server == null ? 0L : server.getTickCount(), "", ""));
    }

    private static void pollSignals(MinecraftServer server, long now) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            fire(playerSignal("player.lowHealth", player));
            }
    }

    private static StageEngine.Signal playerSignal(String event, ServerPlayer player) {
        MinecraftServer server = player.server;
        return new StageEngine.Signal(event, player.getGameProfile().getName(), player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.level().dimension().location().toString(), "",
                player.getHealth(), player.getMaxHealth(), "",
                server == null ? 0L : server.getTickCount(), "", "");
    }

    // ───────────────────────── 匹配与下发 ─────────────────────────

    /** 取出规则 when.triggers 的逐条条件与各自的播放组；不是扁平 triggers 数组就返回 null。 */
    private static TriggerGroups triggerGroups(StageScripts.Rule rule) {
        String json = rule.whenJson();
        if (json == null || json.isEmpty()) {
            return null;
        }
        return TRIGGER_GROUPS.computeIfAbsent(json, raw -> {
            JsonElement element;
            try {
                element = JsonParser.parseString(raw);
            } catch (RuntimeException e) {
                return null;
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject root = element.getAsJsonObject();
            if (!root.has("triggers") || !root.get("triggers").isJsonArray()) {
                return null;
            }
            List<StageEngine.Cond> conds = new ArrayList<>();
            List<String> groups = new ArrayList<>();
            for (JsonElement item : root.getAsJsonArray("triggers")) {
                if (!item.isJsonObject()) {
                    conds.add(null);
                    groups.add("");
                    continue;
                }
                JsonObject object = item.getAsJsonObject();
                conds.add(StageEngine.parseEntry(object));
                groups.add(object.has("group") ? object.get("group").getAsString() : "");
            }
            return new TriggerGroups(conds, groups);
        });
    }

    /** 单条触发器自己的播放组（没有就返回空串，回退到 rule.play）。 */
    private static String triggerGroup(StageScripts.Rule rule, int index) {
        TriggerGroups groups = triggerGroups(rule);
        if (groups == null || index < 0 || index >= groups.groups.size()) {
            return "";
        }
        return groups.groups.get(index);
    }

    /** 自动触发时：找出真正匹配的那几条触发器，取它们各自的组（都没有就回退 rule.play）。 */
    private static List<String> matchedGroups(StageScripts.Rule rule, List<StageEngine.Signal> buffer, long now) {
        TriggerGroups groups = triggerGroups(rule);
        if (groups == null) {
            return null;
        }
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < groups.conds.size(); i++) {
            String groupId = groups.groups.get(i);
            StageEngine.Cond cond = groups.conds.get(i);
            if (groupId == null || groupId.isEmpty() || cond == null) {
                continue;
            }
            if (StageEngine.matches(cond, buffer, now, rule.window())) {
                matched.add(groupId);
            }
        }
        return matched.isEmpty() ? null : matched;
    }

    /** 客户端按下了某条快捷键触发器：注入一次性信号（仍走整组条件判定）。 */
    /** 客户端按下了某条快捷键触发器：只针对那个事件组做一次判定（一次性）。 */
    public static void manual(cn.acechat.network.ManualPayload payload, ServerPlayer player) {
        MinecraftServer server = player == null ? currentServer : player.server;
        if (server == null || payload == null || state == null) {
            return;
        }
        Cached cached = PROJECTS.get(payload.project());
        if (cached == null) {
            return;
        }
        StageScripts.Project project = cached.project();
        StageScripts.Rule rule = null;
        for (StageScripts.Rule candidate : project.rules()) {
            if (candidate.id().equals(payload.rule())) {
                rule = candidate;
                break;
            }
        }
        if (rule == null || rule.whenJson().isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        StageEngine.Signal signal = new StageEngine.Signal("acechat.manual",
                player == null ? "" : player.getGameProfile().getName(),
                player == null ? null : player.getUUID(),
                0, 0, 0, "", "", 0f, 0f, "#" + (payload.index() + 1), now, "", "");
        List<StageEngine.Signal> snapshot = new ArrayList<>(BUFFER);
        snapshot.add(signal);
        StageEngine.Cond cond;
        try {
            cond = CONDITIONS.computeIfAbsent(rule.whenJson(), StageEngine::parse);
        } catch (RuntimeException e) {
            return;
        }
        if (cond == null) {
            LOGGER.info("[acechat] 规则 {} 的 when 解析为空（{}）", rule.id(), rule.whenJson());
            return;
        }
        if (!StageEngine.matches(cond, snapshot, now, rule.window())) {
            return;
        }
        LOGGER.info("[acechat] 触发命中：project={} rule={} when={}",
                payload.project(), rule.id(), rule.whenJson());
        // 手动/快捷键：不受一次性、冷却、忙碌期限制，按一下就演（播着再按会从头重播）
        playNow(player, project, rule, triggerGroup(rule, payload.index()));
    }

    /** 估算这段演出多久播完（tick）。 */
    private static long estimateDurationTicks(StageScripts.Project project, StageScripts.Rule rule) {
        long total = 20L;
        for (String id : rule.play()) {
            StageScripts.Group group = project.group(id);
            if (group == null) {
                continue;
            }
            long best = 0L;
            for (StageScripts.Line line : group.lines()) {
                best = Math.max(best, line.startTick() + line.holdTick() + 10L);
            }
            total += best;   // 多组是顺序播放，时长相加
        }
        return Math.min(total, 20L * 900L);   // 上限 45 秒，避免异常数据锁太久
    }

    private static boolean playNow(ServerPlayer player, StageScripts.Project project,
                                   StageScripts.Rule rule, String groupId) {
        List<StageScripts.Group> groups = new ArrayList<>();
        if (groupId != null && !groupId.isEmpty()) {
            StageScripts.Group picked = project.group(groupId);
            if (picked != null) {
                groups.add(picked);
            }
        }
        if (groups.isEmpty()) {
            for (String id : rule.play()) {
                StageScripts.Group group = project.group(id);
                if (group != null) {
                    groups.add(group);
                }
            }
        }
        if (groups.isEmpty()) {
            return false;
        }
        StagePayload payload = groups.size() == 1
                ? StagePayload.of(project.name(), groups.get(0), rule.id(), project.loop())
                : StagePayload.ofSequence(project.name(), groups, rule.id(), project.loop());
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException ignored) {
            return false;   // 通道不可用
        }
        return true;
    }

    /** 手动重置某事件组在这个玩家身上的记录。 */
    public static void reset(cn.acechat.network.ResetPayload payload, ServerPlayer player) {
        if (state == null || payload == null || player == null) {
            return;
        }
        state.resetFor(payload.project() + "::" + payload.rule(), player.getGameProfile().getName());
        if (statePath != null) {
            state.save(statePath);
        }
    }


    private static void fire(StageEngine.Signal signal) {
        if (signal == null || state == null) {
            return;
        }
        MinecraftServer server = currentServer;
        if (server == null) {
            return;
        }
        BUFFER.addLast(signal);
        long now = signal.tick();
        List<StageEngine.Signal> snapshot = new ArrayList<>(BUFFER);
        int sent = 0;
        for (Map.Entry<String, Cached> entry : PROJECTS.entrySet()) {
            StageScripts.Project project = entry.getValue().project();
            for (StageScripts.Rule rule : project.rules()) {
                if (rule.whenJson().isEmpty()) {
                    continue;   // 没写 when 的规则只能手动播
                }
                StageEngine.Cond cond;
                try {
                    cond = CONDITIONS.computeIfAbsent(rule.whenJson(), StageEngine::parse);
                } catch (RuntimeException e) {
                    continue;
                }
                String key = entry.getKey() + "::" + rule.id();
                // 已经用掉的一次性信号，对这条规则不再参与匹配（避免每个 tick 反复重播）
                List<StageEngine.Signal> usable = new ArrayList<>(snapshot.size());
                for (StageEngine.Signal candidate : snapshot) {
                    if (!consumedBy(candidate, key)) {
                        usable.add(candidate);
                    }
                }
                if (cond == null || !StageEngine.matches(cond, usable, now, rule.window())) {
                    continue;
                }
                if (!state.allowed(key, rule.once(), signal.playerName(), now, rule.cooldown())) {
                    continue;
                }
                if (dispatch(server, signal, project, rule, key, now, matchedGroups(rule, usable, now))) {
                    sent++;
                    // 一次性事件（击杀/受伤/聊天/进出服…）只允许触发这条规则一次；
                    // tick.every / lowHealth 这种周期信号不消耗，仍按冷却控制。
                    for (StageEngine.Signal used : usable) {
                        if (!isPeriodicEvent(used.event())) {
                            CONSUMED.computeIfAbsent(used, ignored -> new HashSet<>()).add(key);
                        }
                    }
                    if ("acechat.manual".equals(signal.event())) {
                        BUFFER.remove(signal);   // 一次性：用完即消
                        CONSUMED.remove(signal);
                    }
                    if (sent >= MAX_SENDS_PER_SIGNAL) {
                        return;
                    }
                }
            }
        }
    }

    /** 这条规则是否已经用掉了这个信号。 */
    private static boolean consumedBy(StageEngine.Signal signal, String key) {
        Set<String> used = CONSUMED.get(signal);
        return used != null && used.contains(key);
    }

    /** 周期信号：可以按 cooldown 反复触发；其余（击杀/受伤/聊天/进退服…）对同一条规则只用一次。 */
    private static boolean isPeriodicEvent(String event) {
        return "tick.every".equalsIgnoreCase(event) || "player.lowHealth".equalsIgnoreCase(event);
    }

    private static boolean dispatch(MinecraftServer server, StageEngine.Signal signal,
                                    StageScripts.Project project, StageScripts.Rule rule,
                                    String key, long now, List<String> presetGroups) {
        List<StageScripts.Group> groups = new ArrayList<>();
        if (presetGroups != null && !presetGroups.isEmpty()) {
            // 画布按触发卡拆了链：只演匹配到的那几条链，互不合并
            for (String id : presetGroups) {
                StageScripts.Group group = project.group(id);
                if (group != null) {
                    groups.add(group);
                }
            }
        } else if ("sequential".equalsIgnoreCase(rule.pick())) {
            for (String id : rule.play()) {
                StageScripts.Group group = project.group(id);
                if (group != null) {
                    groups.add(group);
                }
            }
        } else {
            int cycle = CYCLE.getOrDefault(key, 0);
            int index = StagePick.choose(rule, RNG, LAST_PICK.get(key), cycle);
            if (!rule.play().isEmpty()) {
                String groupId = rule.play().get(Math.max(0, Math.min(index, rule.play().size() - 1)));
                StageScripts.Group group = project.group(groupId);
                if (group != null) {
                    groups.add(group);
                }
                LAST_PICK.put(key, groupId);
                CYCLE.put(key, cycle + 1);
            }
        }
        if (groups.isEmpty()) {
            return false;
        }
        List<ServerPlayer> targets = recipients(server, project, rule, signal);
        if (targets.isEmpty()) {
            return false;
        }
        StagePayload payload = groups.size() == 1
                ? StagePayload.of(project.name(), groups.get(0), rule.id(), project.loop())
                : StagePayload.ofSequence(project.name(), groups, rule.id(), project.loop());
        long duration = sequenceDuration(groups);
        List<java.util.UUID> ids = new ArrayList<>();
        for (ServerPlayer player : targets) {
            ids.add(player.getUUID());
        }
        Pending pending = new Pending(payload, key, rule, duration, now, ids);
        if ("self".equalsIgnoreCase(rule.target())) {
            ServerPlayer center = signal.playerId() == null ? null
                    : server.getPlayerList().getPlayer(signal.playerId());
            if (center == null) {
                center = targets.get(0);
            }
            return offer(center.getUUID(), pending);
        } else {
            return offer(GROUP_QUEUE, pending);
        }
    }

    private static long sequenceDuration(List<StageScripts.Group> groups) {
        int offset = 0;
        for (StageScripts.Group group : groups) {
            int groupEnd = 0;
            for (StageScripts.Line line : group.lines()) {
                groupEnd = Math.max(groupEnd, line.startTick() + line.holdTick());
            }
            offset += groupEnd;
        }
        return Math.max(20, offset);
    }

    /** 队列条目。 */
    private static final class Pending {
        final StagePayload payload;
        final String key;
        final int rank;
        final long duration;
        final long created;
        final List<java.util.UUID> recipients;
        final String once;

        Pending(StagePayload payload, String key, StageScripts.Rule rule, long duration,
                long created, List<java.util.UUID> recipients) {
            this.payload = payload;
            this.key = key;
            this.rank = rule.priorityRank();
            this.duration = duration;
            this.created = created;
            this.recipients = List.copyOf(recipients);
            this.once = rule.once();
        }
    }

    private static boolean offer(java.util.UUID owner, Pending item) {
        if (owner == null) {
            return false;
        }
        Pending current = CURRENT.get(owner);
        if (current != null && current.key.equals(item.key)) {
            return false;   // 同一事件组正在播 → 跳过
        }
        java.util.Deque<Pending> queue = QUEUES.computeIfAbsent(owner, ignored -> new java.util.ArrayDeque<>());
        for (Pending queued : queue) {
            if (queued.key.equals(item.key)) {
                return false;   // 队列里已有同一事件组 → 跳过
            }
        }
        if (current != null && item.rank < current.rank) {
            return false;   // 高优先级正在播，低优先级直接丢弃
        }
        if (queue.size() >= MAX_QUEUE) {
            Pending victim = null;
            for (Pending queued : queue) {
                if (queued.rank < item.rank && (victim == null || queued.created < victim.created)) {
                    victim = queued;
                }
            }
            if (victim == null) {
                return false;   // 全是同级或更高 → 丢弃新来的
            }
            queue.remove(victim);
        }
        queue.addLast(item);
        List<Pending> sorted = new ArrayList<>(queue);
        sorted.sort((a, b) -> a.rank != b.rank
                ? Integer.compare(b.rank, a.rank)
                : Long.compare(a.created, b.created));
        queue.clear();
        queue.addAll(sorted);
        return true;
    }

    private static void flushQueues(MinecraftServer server, long now) {
        for (java.util.UUID owner : new ArrayList<>(QUEUES.keySet())) {
            if (now < BUSY_UNTIL.getOrDefault(owner, 0L)) {
                continue;
            }
            java.util.Deque<Pending> queue = QUEUES.get(owner);
            if (queue == null) {
                continue;
            }
            Pending next = queue.pollFirst();
            if (next == null) {
                CURRENT.remove(owner);
                continue;
            }
            deliver(server, next, now);
            BUSY_UNTIL.put(owner, now + Math.max(20L, next.duration));
            CURRENT.put(owner, next);
        }
    }

    private static void deliver(MinecraftServer server, Pending item, long now) {
        for (java.util.UUID id : item.recipients) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            try {
                PacketDistributor.sendToPlayer(player, item.payload);
                if (state != null) {
                    state.markTriggered(item.key, item.once, player.getGameProfile().getName(), now + item.duration);
                }
            } catch (RuntimeException ignored) {
                // 通道不可用：跳过这个玩家
            }
        }
    }

    private static List<ServerPlayer> recipients(MinecraftServer server, StageScripts.Project project,
                                                 StageScripts.Rule rule, StageEngine.Signal signal) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        ServerPlayer center = null;
        if (signal.playerId() != null) {
            center = server.getPlayerList().getPlayer(signal.playerId());
        }
        if ("self".equalsIgnoreCase(rule.target()) || "triggerer".equalsIgnoreCase(rule.target())) {
            return center == null ? List.of() : List.of(center);
        }
        if ("all".equalsIgnoreCase(rule.target())) {
            return players;
        }
        if (center == null) {
            return players;
        }
        int chunks = rule.radius() > 0 ? rule.radius()
                : (project != null && project.chunkRadius() > 0 ? project.chunkRadius() : 4);
        double radius = chunks * 16.0D;
        double radiusSquared = radius * radius;
        ServerPlayer from = center;
        return players.stream().filter(player -> player.distanceToSqr(from) <= radiusSquared).toList();
    }

    private static ServerPlayer nearestPlayer(MinecraftServer server, double x, double y, double z) {
        return server.getPlayerList().getPlayers().stream()
                .min(Comparator.comparingDouble(player -> player.distanceToSqr(x, y, z)))
                .orElse(null);
    }

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    // ───────────────────────── 工程与状态 ─────────────────────────

    private static volatile MinecraftServer currentServer;

    private static void refreshProjects(long now) {
        if (now - lastScan < 100) {
            return;
        }
        lastScan = now;
        Path dir = StageScripts.dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Map<String, Path> found = new HashMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        found.put(name.substring(0, name.length() - 5), path);
                    });
        } catch (IOException ignored) {
            return;
        }
        PROJECTS.keySet().removeIf(name -> !found.containsKey(name));
        for (Map.Entry<String, Path> entry : found.entrySet()) {
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(entry.getValue()).toMillis();
            } catch (IOException e) {
                continue;
            }
            Cached cached = PROJECTS.get(entry.getKey());
            if (cached != null && cached.mtime() == mtime) {
                continue;
            }
            try {
                PROJECTS.put(entry.getKey(), new Cached(StageScripts.load(entry.getKey()), mtime));
            } catch (IOException | RuntimeException error) {
                PROJECTS.remove(entry.getKey());
                LOGGER.warn("[acechat] 工程 {} 读取失败，已跳过", entry.getKey(), error);
            }
        }
    }

    private static void ensureState(MinecraftServer server) {
        currentServer = server;
        if (state != null) {
            return;
        }
        try {
            statePath = server.getWorldPath(LevelResource.ROOT).resolve("acechat_stage.json");
        } catch (RuntimeException e) {
            statePath = null;
        }
        state = StageState.load(statePath);
    }
}
