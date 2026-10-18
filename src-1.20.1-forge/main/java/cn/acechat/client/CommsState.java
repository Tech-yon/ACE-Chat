package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 客户端通讯内容与当前发言者状态。 */
public final class CommsState {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("acechat");
    /** 已经提示过的「聊天高度」日志（每个选项只提示一次，失败也只提示一次）。 */
    private static final java.util.Set<String> heightLogged = new java.util.HashSet<>();
    public record Entry(UUID sender, String name, String text, Component message, long createdAt, int streak) {
    }

    /** 原版聊天历史里要回放的一条（打开原版聊天框时临时注入）。 */
    public record History(String name, String text, long at) {
    }

    private static final java.util.ArrayDeque<History> VANILLA_HISTORY = new java.util.ArrayDeque<>();

    /** 打开原版聊天框时回放的历史（最多 120 条，最新的在后）。 */
    public static List<History> vanillaHistory() {
        return List.copyOf(VANILLA_HISTORY);
    }

    /**
     * 顶部聊天里的一行：玩家名用「名单配色」，没配过就用「玩家名初始颜色」（默认蓝色），
     * 后面跟尖括号包住的正文。
     */
    public static Component buildMessage(UUID sender, String name, String displayName, String text) {
        // 只有玩家名上色（名单色，没配过就用「玩家名初始颜色」= 默认蓝色），正文固定默认白色
        return Component.literal(displayName)
                .withStyle(CommsConfig.nameStyleFor(sender == null ? null : sender.toString(), name))
                .append(Component.literal(" <" + text + ">").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))));
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    /** ENTRIES 的只读视图，给 HUD 用，避免每次调用都复制一份。 */
    private static final List<Entry> VIEW = java.util.Collections.unmodifiableList(ENTRIES);
    /** 聊天内容版本号：只在增删条目或切换当前播出条目时变化，供 HUD 缓存换行结果。 */
    private static int version;
    /** 当前正在播出的那一条，以及它开始播的时刻。 */
    private static Entry displayed;
    private static long displayStart;
    private static UUID speaker;
    private static String speakerName = "";
    private static long speakerLastMessage;
    private static int streak;
    private static PortraitSource portrait;
    private static boolean sessionEnabled = true;
    /** 玩家 → 死亡时该玩家那条聊天消息的时间戳（用于「保留到消息消失或被替换」）。 */
    private static final java.util.Map<UUID, Long> DIED_AT = new java.util.HashMap<>();

    private CommsState() {
    }

    public static boolean route(Minecraft minecraft, UUID sender, Component message,
                                ChatType.Bound boundChatType) {
        if (!CommsConfig.hudEnabled.get() || sender == null || boundChatType == null) {
            return false;
        }
        // Forge 1.20.1 的普通聊天事件已由 ClientChatReceivedEvent 提供；系统/命令反馈没有 sender，
        // 同时再排除私聊、队伍和动作消息，避免它们误入顶部 HUD。
        String decoration = boundChatType.chatType().chat().translationKey();
        if (!"chat.type.text".equals(decoration)) {
            return false;
        }
        String name = resolveName(minecraft, sender);
        String text = cleanForgeChatText(message, name);
        if (text.isEmpty() || text.startsWith("/")) {
            return false;
        }
        if (CommsConfig.filter.get() == CommsConfig.ChatFilter.WHITELIST_ONLY
                && !isWhitelisted(name)) {
            return false;
        }

        long now = System.currentTimeMillis();
        boolean newSpeaker = speaker == null || !speaker.equals(sender)
                || now - speakerLastMessage > 3_000L;
        if (newSpeaker) {
            streak = 0;
        } else {
            streak = Math.min(12, streak + 1);
        }
        speaker = sender;
        speakerName = name;
        speakerLastMessage = now;
        portrait = PortraitSource.resolve(minecraft, sender, name);
        String displayName = CommsConfig.displayName(name);
        // 同一名玩家的新消息【顶替】他上一条；如果他正是当前播出的那位，就立刻换成新消息（不等停留时间）
        Entry previous = null;
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            if (ENTRIES.get(i).sender().equals(sender)) {
                previous = ENTRIES.remove(i);
                break;
            }
        }
        Entry entry = new Entry(sender, name, text, buildMessage(sender, name, displayName, text), now, streak);
        ENTRIES.add(entry);
        if (previous == displayed) {
            displayed = entry;
            displayStart = now;
        }
        // 记进「原版聊天历史」：悬浮聊天不再显示玩家发言，但打开原版聊天（T）时会临时回放这些记录
        VANILLA_HISTORY.addLast(new History(displayName, text, now));
        while (VANILLA_HISTORY.size() > 120) {
            VANILLA_HISTORY.removeFirst();
        }
        version++;
        trimEntries(minecraft, now);

        if (newSpeaker && CommsConfig.blipEnabled.get()) {
            playNewMessage();
        }
        return true;
    }

    /** G 键预览：不用第二名玩家也能检查 HUD 是否正常。 */
    public static void preview(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        UUID sender = minecraft.player.getUUID();
        String name = minecraft.player.getGameProfile().getName();
        long now = System.currentTimeMillis();
        boolean newSpeaker = speaker == null || !speaker.equals(sender);
        streak = newSpeaker ? 0 : Math.min(12, streak + 1);
        speaker = sender;
        speakerName = name;
        speakerLastMessage = now;
        portrait = PortraitSource.resolve(minecraft, sender, name);
        Entry previousPreview = null;
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            if (ENTRIES.get(i).sender().equals(sender)) {
                previousPreview = ENTRIES.remove(i);
                break;
            }
        }
        Entry previewEntry = new Entry(sender, name, Component.translatable("text.comms.preview_message").getString(),
                Component.translatable("text.comms.preview_message"), now, streak);
        ENTRIES.add(previewEntry);
        if (previousPreview == displayed || displayed == null) {
            displayed = previewEntry;
            displayStart = now;
        }
        version++;
        trimEntries(minecraft, now);
        if (CommsConfig.blipEnabled.get() && newSpeaker) {
            playNewMessage();
        }
    }

    /**
     * 不滚动时限制屏幕上显示的聊天行数：只裁剪「显示列表」(trimmedMessages)，
     * 历史列表(allMessages / recentChat)保持完整 —— 按 T 滚动仍能读到全部历史。
     */
    /**
     * 路线B：不删任何聊天数据，只把原版两个「聊天高度」选项压到配置的行数。
     * 原来的循环只改到了一个字段（先匹配到谁就算谁），所以「聊天界面行数」一直没生效；
     * 现在两个分别对应：
     * <ul>
     *   <li>{@code chatHeightUnfocused}（{@code f_92133_}）← 「屏幕显示聊天行数」（平时左下角）；</li>
     *   <li>{@code chatHeightFocused}（{@code f_92134_}）← 「聊天栏最多显示行数」（按 T 打开时）。</li>
     * </ul>
     * 两者都是「显示几行」，历史数据一个字都不删，滚轮照样能翻到全部。
     */
    private static void applyChatHeight(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null) {
            return;
        }

        applyChatHeightOption(minecraft, "chatHeightUnfocused", "f_92133_",
                CommsConfig.chatVisibleLines.get(), "平时屏幕");
        applyChatHeightOption(minecraft, "chatHeightFocused", "f_92134_",
                CommsConfig.chatScreenLines.get(), "聊天界面(T)");
    }

    /** 把某一个「聊天高度」选项压到指定行数；lines &lt;= 0 表示不干预。 */
    private static void applyChatHeightOption(Minecraft minecraft, String officialName, String srgName, int lines, String label) {
        if (lines <= 0) {
            return;   // 0 = 不干预，保持原版设置
        }

        double target = Math.max(0.0D, Math.min(1.0D, lines / 10.0D));
        try {
            java.lang.reflect.Field field = null;
            for (String name : new String[]{officialName, srgName}) {
                try {
                    field = net.minecraft.client.Options.class.getDeclaredField(name);
                    field.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                    field = null;
                }
            }

            if (field == null) {
                warnHeightOnce("找不到原版选项字段 " + officialName + "/" + srgName);
                return;
            }

            Object holder = field.get(minecraft.options);
            double current;
            if (holder instanceof Number number) {
                current = number.doubleValue();
            } else {
                // OptionInstance<Double>：只能反射读写。注意【生产包运行时方法名是 SRG】，
                // 实测旧代码只试官方名 "get" → NoSuchMethodException: OptionInstance.get()，
                // 于是整个「屏幕显示聊天行数」功能静默失效（日志里每次进游戏都会看到这条警告）。
                java.lang.reflect.Method getter = findOptionMethod(holder.getClass(), "get", "m_231551_");
                if (getter == null) {
                    warnHeightOnce("找不到 OptionInstance 的取值方法（官方名 get 与 SRG 名 m_231551_ 都不匹配）");
                    return;
                }

                current = ((Number) getter.invoke(holder)).doubleValue();
            }

            if (Math.abs(current - target) <= 0.005D) {
                return;
            }

            if (holder instanceof Number) {
                field.set(minecraft.options, target);
            } else {
                java.lang.reflect.Method setter = findOptionMethod(holder.getClass(), "set", "m_231514_");
                if (setter == null) {
                    warnHeightOnce("找不到 OptionInstance 的赋值方法（官方名 set 与 SRG 名 m_231514_ 都不匹配）");
                    return;
                }

                setter.invoke(holder, target);
            }

            minecraft.options.save();
            if (heightLogged.add(label)) {
                LOGGER.info("[acechat] 已把{}的聊天高度压到约 {} 行（原 {}）", label, lines, current);
            }
        } catch (RuntimeException | ReflectiveOperationException | LinkageError error) {
            if (heightLogged.add("失败-" + label)) {
                LOGGER.warn("[acechat] 调整{}聊天高度失败（不影响其它功能）", label, error);
            }
        }
    }

    /**
     * 读/写 {@code OptionInstance} 用的反射方法：官方名（开发环境）与 SRG 名（生产包）都试一遍，
     * 无参与一个参数两个形状都试。
     */
    private static java.lang.reflect.Method findOptionMethod(Class<?> type, String officialName, String srgName) {
        Class<?>[][] shapes = {new Class<?>[0], new Class<?>[]{Object.class}};
        for (String name : new String[]{officialName, srgName}) {
            for (Class<?>[] shape : shapes) {
                try {
                    java.lang.reflect.Method method = type.getMethod(name, shape);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    // 试下一个名字 / 形状
                }
            }
        }

        return null;
    }

    /** 一次性提示：把「哪一步断了」写清楚，方便下次靠日志定位。 */
    private static void warnHeightOnce(String reason) {
        if (heightLogged.add("警告")) {
            LOGGER.warn("[acechat] 调整聊天高度失败：{}（不影响其它功能）", reason);
        }
    }

    public static void tick(Minecraft minecraft) {
        applyChatHeight(minecraft);
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            clear();
            return;
        }
        trimEntries(minecraft, System.currentTimeMillis());
    }

    public static List<Entry> entries() {
        // 不再每帧 List.copyOf（那是每帧一次数组分配）；空列表直接返回常量，非空返回只读视图
        return ENTRIES.isEmpty() ? List.of() : VIEW;
    }

    /** 聊天内容的版本号：只在增删条目时变化，供 HUD 缓存换行结果，避免每帧重新折行。 */
    public static int version() {
        return version;
    }

    /**
     * 当前正在「播出」的那一条：聊天区只显示它，形象框也只跟着它。
     * 切换规则见 pumpDisplay —— 有人接着发言时，前一位至少停留 holdMin，再按消息长度停留到最多 holdMax。
     */
    public static Entry displayed() {
        return displayed;
    }

    /** 当前这条已经播了多久（毫秒）。 */
    public static long displayedElapsed() {
        return displayed == null ? 0L : Math.max(0L, System.currentTimeMillis() - displayStart);
    }

    /** 当前这条的停留时长（毫秒）：消息越长越接近 holdMax；holdMin=0 表示下一位一发言就立刻替换。 */
    public static long holdMillis(Entry entry) {
        double min = Math.max(0.0D, Math.min(CommsConfig.holdMinSeconds.get(), CommsConfig.holdMaxSeconds.get()));
        if (min <= 0.0D) {
            // 最短停留设为 0：不论时间长短，下一位一发言立刻替换
            return 0L;
        }

        double max = Math.max(min, CommsConfig.holdMaxSeconds.get());
        if (entry == null) {
            return (long) (min * 1000.0D);
        }

        int length = entry.message().getString().length();
        double ratio = Math.max(0.0D, Math.min(1.0D, length / 60.0D));
        return (long) ((min + (max - min) * ratio) * 1000.0D);
    }

    public static UUID speaker() {
        return speaker;
    }

    public static String speakerName() {
        return speakerName;
    }

    public static long speakerLastMessage() {
        return speakerLastMessage;
    }

    public static PortraitSource portrait() {
        return portrait;
    }

    /**
     * 「重新读取皮肤」：清空皮肤缓存并立即重解析当前发言者（没人发言时解析自己）。
     * 关键点：CommsState.portrait 本身是一个缓存字段，只清 PortraitSource 的缓存是没用的，
     * 必须把这个字段也一起换掉，否则界面里看到的还是旧立绘。
     */
    public static void refreshPortrait(Minecraft minecraft) {
        PortraitSource.clear();
        if (speaker != null && !speakerName.isEmpty()) {
            portrait = PortraitSource.resolve(minecraft, speaker, speakerName);
        } else if (minecraft.player != null) {
            portrait = PortraitSource.resolve(minecraft, minecraft.player.getUUID(),
                    minecraft.player.getGameProfile().getName());
        }
    }

    /** 当前正在显示的那条消息（没有就是 null）。 */
    public static Entry displayedEntry() {
        return displayed;
    }

    public static boolean isSessionEnabled() {
        return CommsConfig.hudEnabled.get();
    }

    /**
     * 每 tick 扫一次玩家血量：某玩家「当前有消息在显示」时死亡 → 记下时间戳，稍后覆盖其形象。
     * 只影响别人（跳过本地玩家）；复活不会清除记录，只有消息消失/被新消息替换才结束。
     */
    public static void scanDeaths(net.minecraft.client.Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }

        for (net.minecraft.world.entity.player.Player player : minecraft.level.players()) {
            if (player == minecraft.player || player.getUUID().equals(minecraft.player.getUUID())) {
                continue;
            }

            boolean dying = player.isDeadOrDying() || player.getHealth() <= 0.0F;
            if (!dying) {
                continue;
            }

            UUID uuid = player.getUUID();
            if (DIED_AT.containsKey(uuid)) {
                continue;
            }

            Long stamp = newestEntryStamp(uuid);
            if (stamp != null) {
                DIED_AT.put(uuid, stamp);
            }
        }
    }

    /**
     * 死亡消息解析（主路径）：原版死亡消息是 {@code death.*} 系列的翻译键，第一个参数就是死者。
     * 匹配到世界里的玩家（且不是自己）就记为死亡。
     *
     * @return true = 这条消息确实是死亡消息
     */
    public static boolean rememberDeathMessage(net.minecraft.network.chat.Component message) {
        if (message == null || !CommsConfig.deathOverlayEnabled.get()) {
            return false;
        }

        if (!(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable)) {
            return false;
        }

        String key = translatable.getKey();
        if (key == null || !key.startsWith("death.")) {
            return false;
        }

        Object[] args = translatable.getArgs();
        if (args.length == 0) {
            return false;
        }

        Object first = args[0];
        String victim = first instanceof net.minecraft.network.chat.Component component
                ? component.getString() : String.valueOf(first);
        if (victim.isEmpty()) {
            return false;
        }

        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return true;   // 是死亡消息，只是当前没法匹配到玩家
        }

        for (net.minecraft.world.entity.player.Player player : minecraft.level.players()) {
            if (player.getUUID().equals(minecraft.player.getUUID())) {
                continue;
            }

            String name = player.getGameProfile().getName();
            String display = player.getDisplayName().getString();
            if ((name != null && name.equals(victim)) || display.equals(victim)) {
                UUID uuid = player.getUUID();
                if (!DIED_AT.containsKey(uuid)) {
                    Long stamp = newestEntryStamp(uuid);
                    if (stamp != null) {
                        DIED_AT.put(uuid, stamp);
                    }
                }

                return true;
            }
        }

        return true;
    }

    /** 该玩家最新一条消息的时间戳（没有消息返回 null）。 */
    private static Long newestEntryStamp(UUID uuid) {
        Long newest = null;
        for (Entry entry : ENTRIES) {
            if (uuid.equals(entry.sender())) {
                newest = newest == null ? entry.createdAt() : Math.max(newest, entry.createdAt());
            }
        }

        return newest;
    }

    /** 该玩家是否正处于「死亡覆盖」状态：死亡时的那条消息还在、且没有被更新的消息替换。 */
    public static boolean deathOverlayActive(UUID uuid) {
        if (!CommsConfig.deathOverlayEnabled.get() || uuid == null) {
            return false;
        }

        Long stamp = DIED_AT.get(uuid);
        if (stamp == null) {
            return false;
        }

        Long newest = newestEntryStamp(uuid);
        return newest != null && newest <= stamp;
    }

    /** 清空死亡记录（退出世界/清空状态时调用）。 */
    private static void clearDeaths() {
        DIED_AT.clear();
    }

    /** 快捷键切换：直接翻转配置里的「显示顶部 HUD」并保存，配置界面会同步显示。 */
    public static void toggleSession() {
        CommsConfig.hudEnabled.set(!CommsConfig.hudEnabled.get());
        CommsConfig.save();
    }

    public static void clear() {
        clearDeaths();
        ENTRIES.clear();
        version++;
        displayed = null;
        displayStart = 0L;
        speaker = null;
        speakerName = "";
        speakerLastMessage = 0L;
        streak = 0;
        portrait = null;
        PortraitSource.clear();
        PortraitBaker.clear();
    }

    private static void trimEntries(Minecraft minecraft, long now) {
        long hold = (long) ((Math.max(CommsConfig.lineHoldSeconds.get(), CommsConfig.holdMaxSeconds.get()) + 2.0D) * 1000.0D);
        Iterator<Entry> iterator = ENTRIES.iterator();
        boolean changed = false;
        while (iterator.hasNext()) {
            if (now - iterator.next().createdAt() > hold) {
                iterator.remove();
                changed = true;
            }
        }
        int max = CommsConfig.maxLines.get();
        while (ENTRIES.size() > max * 2) {
            ENTRIES.remove(0);
            changed = true;
        }
        if (changed) {
            version++;
        }

        pumpDisplay(minecraft, now);
    }

    /**
     * 单条轮播的调度：
     * 1) 有玩家接着发言时，前一位（当前播出的那条）至少停留 holdMin 秒，之后才轮到下一位；
     * 2) 停留时长按前一位消息的长度增长，最长 holdMax 秒；holdMin 设为 0 就是不等待、立刻替换；
     * 3) 同一名玩家再发言 → 直接顶替自己那条（即时替换）；
     * 4) 排队中的消息等到自己上场；过期或被顶掉的就跳过；
     * 5) 形象框始终跟着「当前播出」的那位玩家，最后一条自然消失后形象框一起消失。
     */
    private static void pumpDisplay(Minecraft minecraft, long now) {
        if (displayed != null && !ENTRIES.contains(displayed)) {
            displayed = null;
            playMessageGone();   // 这条自然到期消失：播放「消息消失」音效
        }

        if (displayed == null) {
            if (!ENTRIES.isEmpty()) {
                displayed = ENTRIES.get(0);
                displayStart = now;
                version++;
            }

            syncSpeakerWithDisplay(minecraft);
            return;
        }

        if (now - displayStart >= holdMillis(displayed)) {
            int index = ENTRIES.indexOf(displayed);
            if (index >= 0 && index + 1 < ENTRIES.size()) {
                displayed = ENTRIES.get(index + 1);
                displayStart = now;
                version++;
                playMessageGone();   // 上一条退场：播放「消息消失」音效
            }
        }

        syncSpeakerWithDisplay(minecraft);
    }

    private static void syncSpeakerWithDisplay(Minecraft minecraft) {
        if (displayed == null) {
            speaker = null;
            speakerName = "";
            portrait = null;
            return;
        }

        if (speaker == null || !speaker.equals(displayed.sender()) || portrait == null) {
            speaker = displayed.sender();
            speakerName = displayed.name();
            portrait = PortraitSource.resolve(minecraft, speaker, speakerName);
        }
    }

    private static String cleanForgeChatText(Component message, String name) {
        String text = message == null ? "" : message.getString().trim();
        String[] prefixes = {name + ":", name + "：", "<" + name + ">"};
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return text.substring(prefix.length()).trim();
            }
        }
        return text;
    }

    private static boolean isWhitelisted(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (String item : CommsConfig.whitelist.get()) {
            String candidate = item.trim().toLowerCase(Locale.ROOT);
            if (candidate.equals("*") || candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static String resolveName(Minecraft minecraft, UUID sender) {
        if (minecraft.player != null && minecraft.player.getUUID().equals(sender)) {
            return minecraft.player.getGameProfile().getName();
        }
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(sender);
            if (info != null) {
                return info.getProfile().getName();
            }
        }
        return sender.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** 界面里的「试听」：先播新消息音效，再播消息消失音效。 */
    public static void playTestSounds() {
        playNewMessage();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> {
                try {
                    Thread.sleep(420L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                playMessageGone();
            });
        }
    }
    /** 新消息音效：优先用自定义 new_message.*，否则回落到内置音效（音调略高）。 */
    private static void playNewMessage() {
        float volume = CommsConfig.soundVolume.get().floatValue() * (CommsConfig.newSoundVolume.get() / 100.0F);
        if (!CustomSounds.play("new_message", CommsConfig.newSoundPreset.get(), volume,
                CommsConfig.newSoundPitch.get(), CommsConfig.newSoundSpeed.get(),
                CommsConfig.newSoundFadeIn.get(), CommsConfig.newSoundFadeOut.get())) {
            play(CommsSounds.COMMS_BLIP.get(), 1.35F, volume);
        }
    }

    /** 消息消失音效：优先用自定义 message_gone.*，否则回落到内置音效（音调压低）。 */
    private static void playMessageGone() {
        if (!CommsConfig.goneSoundEnabled.get()) {
            return;
        }

        float volume = CommsConfig.soundVolume.get().floatValue() * (CommsConfig.goneSoundVolume.get() / 100.0F);
        if (!CustomSounds.play("message_gone", CommsConfig.goneSoundPreset.get(), volume,
                CommsConfig.goneSoundPitch.get(), CommsConfig.goneSoundSpeed.get(),
                CommsConfig.goneSoundFadeIn.get(), CommsConfig.goneSoundFadeOut.get())) {
            play(CommsSounds.COMMS_BLIP.get(), 0.7F, volume);
        }
    }
    private static void play(SoundEvent sound, float pitch, float volume) {
        if (volume <= 0.001F || sound == null) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(sound, Math.max(0.5F, Math.min(2.0F, pitch)), volume));
    }
}
