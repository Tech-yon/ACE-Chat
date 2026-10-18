package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 原版聊天的「历史回放」。
 *
 * <p>需求：玩家发言<b>只</b>出现在屏幕中上方的 HUD 里 —— 左下角原版聊天区（悬浮显示）永远只显示
 * 非玩家消息（系统提示、服务器广播等）；只有按 T 打开原版聊天框时，才看得到先前的玩家发言历史。
 *
 * <p>做法：
 * <ul>
 *   <li>玩家发言由本模组接管（{@code removeFromVanillaChat}），根本不进入原版聊天 → 左下角天然不显示；</li>
 *   <li>打开原版聊天框（屏幕是 ChatScreen）时，把最近的历史<b>临时注入</b>进去 → T 里能看到全部历史；</li>
 *   <li>聊天框一关，客户端 tick 就把注入的条目<b>摘掉</b> → 左下角恢复干净。</li>
 * </ul>
 *
 * <p><b>关键实现细节（踩过坑）</b>：原版 {@code ChatComponent} 里两个列表是
 * {@code List<GuiMessage>}（历史）与 {@code List<GuiMessage$Line>}（悬浮行），
 * 而 {@code GuiMessage} / {@code GuiMessage$Line} 是 record，正式包里访问器名是 SRG 混淆名
 * （{@code f_240363_()} 之类），所以这里**全部按类型查找**（找返回 {@code Component} /
 * {@code FormattedCharSequence} 的无参方法），不依赖任何字段名或方法名。
 *
 * <p>另外：<b>只有确认「将来能删掉」时才注入</b> —— 两个列表字段必须先解析成功，
 * 否则宁可不注入（最坏情况是按 T 看不到历史），也绝不让玩家发言留在左下角。
 */
public final class VanillaChatReplay {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final int MAX_REPLAY = 120;

    /** 本次注入的原文（用于精确摘除）。 */
    private static final List<String> INJECTED = new ArrayList<>();
    private static boolean injected;
    /** 被「左下角显示其它信息=关」取消掉的原版信息（系统消息/命令反馈等），按文本 + 组件留存，供打开聊天框时回放。 */
    private static final List<Component> OTHER = new ArrayList<>();
    private static final List<String> OTHER_TEXT = new ArrayList<>();
    private static boolean unavailableLogged;
    private static Field historyField;
    private static Field lineField;

    private VanillaChatReplay() {
    }

    /** 记下一条被我们取消显示的「其它信息」，等玩家打开聊天框时再回放到历史里。 */
    public static void rememberOther(Component message) {
        if (message == null) {
            return;
        }

        if (OTHER.size() >= MAX_REPLAY) {
            OTHER.remove(0);
            OTHER_TEXT.remove(0);
        }

        OTHER.add(message);
        OTHER_TEXT.add(message.getString());
    }

    /** 打开原版聊天框时调用：把先前发言注入原版聊天历史（只有能摘除时才注入）。 */
    public static void inject(Minecraft minecraft) {
        // 顶部 HUD 关掉时=「模组不接管聊天」：原版聊天里本来就有全部记录，再注入只会重复
        if (!CommsConfig.hudEnabled.get()) {
            return;
        }
        // 用户要求：左下角玩家聊天开关关闭时，无论如何都不把玩家聊天放进原版聊天
        boolean allowPlayer = CommsConfig.vanillaPlayerChat.get();
        boolean allowOther = CommsConfig.vanillaOtherMessages.get();
        if (!allowPlayer && !allowOther) {
            return;
        }
        boolean replayPlayer = !CommsConfig.vanillaPlayerChat.get();
        boolean replayOther = !CommsConfig.vanillaOtherMessages.get();
        if (injected || minecraft == null || (!replayPlayer && !replayOther)) {
            return;
        }

        List<CommsState.History> history = replayPlayer ? CommsState.vanillaHistory() : List.of();
        if (history.isEmpty() && (!replayOther || OTHER.isEmpty())) {
            return;
        }

        ChatComponent chat = minecraft.gui.getChat();
        resolveFields(chat);
        if (historyField == null || lineField == null) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                LOGGER.warn("[acechat] 无法访问原版聊天数据结构，已停用「打开聊天框回放历史」"
                        + "（玩家发言依旧不会出现在左下角，只是按 T 看不到历史）");
            }

            return;
        }

        try {
            int from = Math.max(0, history.size() - MAX_REPLAY);
            for (int i = from; i < history.size(); i++) {
                CommsState.History item = history.get(i);
                // 匹配键必须取「实际注入的那个组件」的文本：组件里符号两侧有 chatSymbolPadding 折出来的空格，
                // 自己拼 "名字 <正文>" 会漏掉空格 → retract 匹配不上 → 关掉聊天框后那几行摘不掉（且静默无日志）。
                net.minecraft.network.chat.Component replayLine = CommsConfig.chatLine(item.name(), item.text(),
                        CommsConfig.nameStyleFor(null, item.name()));
                chat.addMessage(replayLine);
                INJECTED.add(replayLine.getString());
            }

            if (replayOther) {
                for (int i = 0; i < OTHER.size(); i++) {
                    chat.addMessage(OTHER.get(i));
                    INJECTED.add(OTHER_TEXT.get(i));
                }
            }

            injected = true;
        } catch (RuntimeException | LinkageError error) {
            INJECTED.clear();
            LOGGER.warn("[acechat] 回放聊天历史失败（HUD 仍正常工作）", error);
        }
    }

    /** 聊天框关闭后调用：把回放注入的条目摘掉，保证左下角不再出现玩家发言。 */
    /**
     * 立刻清空<b>整个</b>原版聊天的历史显示（原版 clearMessages，连带系统消息与指令反馈一起清）。
     *
     * <p><b>注意（2026-10-13）</b>：tick 循环<b>不再</b>调用它 —— 之前「左下角显示玩家聊天=关」时
     * 每 tick 都清一次，导致按 T 看不到任何历史（连指令反馈都没了）。只想清掉本模组注入的行，
     * 用 {@link #retract(Minecraft)} 就够了（它按文本只删自己注入的条目）。
     * 保留本方法仅为「需要彻底清屏」的场合备用。
     */
    public static void clearVanillaChatNow(Minecraft minecraft) {
        if (minecraft == null || minecraft.gui == null) {
            return;
        }

        // 先用原版自己的 API（最可靠）；万一该版本没有这个方法，再退回反射清字段
        try {
            minecraft.gui.getChat().clearMessages(false);
        } catch (RuntimeException | LinkageError error) {
            clearByReflection(minecraft);
        }

        INJECTED.clear();
        reset();
    }

    /** 兜底：反射清空原版聊天的内部列表。 */
    private static void clearByReflection(Minecraft minecraft) {
        try {
            Object chat = minecraft.gui.getChat();
            for (String fieldName : new String[]{"allMessages", "trimmedMessages"}) {
                try {
                    java.lang.reflect.Field field = chat.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(chat);
                    if (value instanceof java.util.List<?> list) {
                        list.clear();
                    }
                } catch (NoSuchFieldException ignored) {
                    // 该版本没有这个字段
                }
            }
        } catch (RuntimeException | ReflectiveOperationException | LinkageError ignored) {
            // 兜底失败也不影响主要功能
        }
    }

    public static void retract(Minecraft minecraft) {

        injected = false;
        if (minecraft == null) {
            INJECTED.clear();
            return;
        }

        try {
            ChatComponent chat = minecraft.gui.getChat();
            resolveFields(chat);
            removeMatching(chat, historyField);
            removeMatching(chat, lineField);
            // 自查：万一还有残留（例如原版数据结构变了），记一条日志，下次好定位
            if (anyRemains(chat) && !unavailableLogged) {
                unavailableLogged = true;
                LOGGER.warn("[acechat] 回放的消息未能完全从原版聊天里摘除"
                        + "（残留会在约 10 秒内自然淡出；玩家新发言依旧不会进入左下角）");
            }
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("[acechat] 收回聊天历史失败（原版聊天仍可正常使用）", error);
        } finally {
            INJECTED.clear();
        }
    }

    /** 两个列表里是否还有我们注入的条目。 */
    private static boolean anyRemains(ChatComponent chat) {
        return containsMatching(chat, historyField) || containsMatching(chat, lineField);
    }

    private static boolean containsMatching(ChatComponent chat, Field field) {
        if (field == null) {
            return false;
        }

        try {
            field.setAccessible(true);
            if (field.get(chat) instanceof List<?> list) {
                for (Object entry : list) {
                    if (isReplayed(entry)) {
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 忽略
        }

        return false;
    }

    /** 切换会话/退出世界时重置状态。 */
    public static void reset() {
        injected = false;
        INJECTED.clear();
    }

    private static void removeMatching(ChatComponent chat, Field field) {
        if (field == null) {
            return;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(chat);
            if (!(value instanceof List<?> raw)) {
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) raw;
            List<Object> keep = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (!isReplayed(entry)) {
                    keep.add(entry);
                }
            }

            if (keep.size() != entries.size()) {
                entries.clear();
                entries.addAll(keep);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 单项失败就跳过，不影响游戏
        }
    }

    /** 这条原版聊天记录是不是我们注入的（按文本匹配，不看任何名字）。 */
    private static boolean isReplayed(Object entry) {
        if (entry == null || INJECTED.isEmpty()) {
            return false;
        }

        try {
            String text = plainText(entry);
            if (text == null || text.isEmpty()) {
                return false;
            }

            // 比较时忽略空格：符号间距（chatSymbolPadding）折出来的空格数量会随设置变化，
            // 去掉空格再比对，格式怎么调都不会再出现"摘不掉"的情况（双保险）。
            String tightText = text.replace(" ", "");
            for (String full : INJECTED) {
                // 折行后每一行都是原文的一段，所以双向匹配
                if (full.startsWith(text) || full.contains(text) || full.replace(" ", "").contains(tightText)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 忽略
        }

        return false;
    }

    /** 按类型把一条记录的文本取出来：GuiMessage → Component，GuiMessage$Line → FormattedCharSequence。 */
    private static String plainText(Object entry) {
        Object content = byType(entry, Component.class);
        if (content instanceof Component component) {
            return component.getString();
        }

        Object sequence = byType(entry, FormattedCharSequence.class);
        if (sequence instanceof FormattedCharSequence chars) {
            StringBuilder builder = new StringBuilder();
            chars.accept((index, style, codepoint) -> {
                builder.appendCodePoint(codepoint);
                return true;
            });
            return builder.toString();
        }

        return null;
    }

    /**
     * 按<b>类型</b>取 record 组件（不依赖访问器名字）：
     * 先找返回该类型的无参方法，再退回同类型的字段。SRG 与开发命名都适用。
     */
    private static Object byType(Object entry, Class<?> wanted) {
        for (Method method : entry.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && wanted.isAssignableFrom(method.getReturnType())) {
                try {
                    return method.invoke(entry);
                } catch (ReflectiveOperationException ignored) {
                    // 继续尝试别的
                }
            }
        }

        for (Field field : entry.getClass().getDeclaredFields()) {
            if (wanted.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    return field.get(entry);
                } catch (ReflectiveOperationException ignored) {
                    // 继续尝试别的
                }
            }
        }

        return null;
    }

    /** 解析 ChatComponent 里的两个列表字段（按泛型实参类型识别，不看字段名）。 */
    private static void resolveFields(ChatComponent chat) {
        if (historyField != null && lineField != null) {
            return;
        }

        for (Field field : ChatComponent.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }

            Type generic = field.getGenericType();
            if (!(generic instanceof ParameterizedType parameterized) || parameterized.getActualTypeArguments().length != 1) {
                continue;
            }

            String name = parameterized.getActualTypeArguments()[0].getTypeName();
            if (name.endsWith("GuiMessage$Line")) {
                field.setAccessible(true);
                lineField = field;
            } else if (name.endsWith("GuiMessage")) {
                field.setAccessible(true);
                historyField = field;
            }
        }
    }
}
