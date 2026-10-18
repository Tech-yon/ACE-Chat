package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>SPEC 版专用</b>：把「被 NoChatReports 之类模组转成系统消息的玩家聊天」认回来。
 *
 * <p>NCR 的 {@code convertToGameMessage} 不删文字，只把玩家聊天改成系统消息 ——
 * 于是 {@code ClientChatReceivedEvent} 里 {@code sender == null}、{@code isSystem() == true}，
 * 文字本身完好。这里按常见格式把「名字 + 正文」拆出来，并要求这个名字
 * <b>正好是在线玩家</b>，才当成玩家聊天（避免把系统广播误判成玩家发言）。
 *
 * <pre>
 *   &lt;名字&gt; 正文        原版格式，也是最常见的情况
 *   [名字] 正文         部分服务端插件
 *   名字 » 正文         常见服务端插件（中文服多用这个分隔符）
 *   名字 &gt; 正文         少数插件
 * </pre>
 */
final class NcrChat {
    private static final int MAX_NAME = 32;

    private static final Pattern[] PATTERNS = {
        Pattern.compile("^<([^<>]{1," + MAX_NAME + "})>\\s*(.+)$", Pattern.DOTALL),
        Pattern.compile("^\\[([^\\[\\]]{1," + MAX_NAME + "})\\]\\s*(.+)$", Pattern.DOTALL),
        Pattern.compile("^([^\\s<>\\[\\]»:]{1," + MAX_NAME + "})\\s*»\\s*(.+)$", Pattern.DOTALL),
        Pattern.compile("^([^\\s<>\\[\\]»:]{1," + MAX_NAME + "})\\s*>\\s*(.+)$", Pattern.DOTALL),
    };

    /** 认回来的一条玩家聊天。 */
    record Recovered(UUID sender, String name, String text) {
    }

    /**
     * 纯解析：把「名字 + 正文」从原文里拆出来（不做在线校验，方便无头测试）。
     *
     * @return {@code {名字, 正文}}；拆不出来返回 {@code null}
     */
    static String[] splitNameAndText(String raw) {
        if (raw == null) {
            return null;
        }

        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }

        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.matches()) {
                continue;
            }

            String name = matcher.group(1).strip();
            String body = matcher.group(2).strip();
            if (name.isEmpty() || body.isEmpty()) {
                continue;
            }

            return new String[]{name, body};
        }

        return null;
    }

    /** 认出「被转成系统消息的玩家聊天」；名字不在线或格式不认识都返回 null。 */
    static Recovered recover(Minecraft minecraft, Component message) {
        if (minecraft == null || minecraft.getConnection() == null || message == null) {
            return null;
        }

        String[] split = splitNameAndText(message.getString());
        if (split == null) {
            return null;
        }

        PlayerInfo info = minecraft.getConnection().getPlayerInfo(split[0]);
        if (info == null) {
            return null;
        }

        String realName = info.getProfile().getName();
        return new Recovered(info.getProfile().getId(), realName, split[1]);
    }

    private NcrChat() {
    }
}
