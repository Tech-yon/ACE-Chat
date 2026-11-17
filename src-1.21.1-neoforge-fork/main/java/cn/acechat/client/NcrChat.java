package cn.acechat.client;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NoChatReports 兼容：把「被转成系统消息 / 发送者变空 UUID 的玩家聊天」认回「名字 + 正文」。
 *
 * <p>支持：{@code <Steve> hi}、{@code [Steve] hi}、{@code Steve » hi}、{@code Steve > hi}、
 * {@code Steve: hi}（含中文冒号）。名字必须是已知玩家或角色卡名，避免误伤服务器广播。
 */
public final class NcrChat {
    public record Parsed(String name, String text) {
    }

    private static final int MAX_NAME = 16;
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("^<([^<>]{1," + MAX_NAME + "})>\\s*(.+)$", Pattern.DOTALL),
            Pattern.compile("^\\[([^\\[\\]]{1," + MAX_NAME + "})\\]\\s*(.+)$", Pattern.DOTALL),
            Pattern.compile("^([^\\s<>\\[\\]»:]{1," + MAX_NAME + "})\\s*»\\s*(.+)$", Pattern.DOTALL),
            Pattern.compile("^([^\\s<>\\[\\]»:]{1," + MAX_NAME + "})\\s*>\\s*(.+)$", Pattern.DOTALL),
            Pattern.compile("^([^\\s<>\\[\\]»:：]{1," + MAX_NAME + "})\\s*[:：]\\s*(.+)$", Pattern.DOTALL)
    );
    private static final List<String> BLOCKED = List.of("server", "system", "admin", "notice", "broadcast");

    public static Parsed parse(String raw) {
        return parse(raw, NcrChat::isKnownPlayer);
    }

    /** 可注入「已知玩家」判断（无头测试用）。 */
    public static Parsed parse(String raw, java.util.function.Predicate<String> known) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() < 3) {
            return null;
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1).trim();
            String body = matcher.group(2).trim();
            if (name.isEmpty() || body.isEmpty()) {
                continue;
            }
            if (BLOCKED.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (known != null && !known.test(name)) {
                continue;
            }
            return new Parsed(name, body);
        }
        return null;
    }

    /** 名字是否为已知玩家：自己 / 在线玩家 / 角色卡名。 */
    public static boolean isKnownPlayer(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.getGameProfile().getName().equalsIgnoreCase(name)) {
            return true;
        }
        if (minecraft.getConnection() != null) {
            for (var info : minecraft.getConnection().getOnlinePlayers()) {
                if (info.getProfile().getName().equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        for (String card : PlayerCards.list()) {
            if (card.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private NcrChat() {
    }
}
