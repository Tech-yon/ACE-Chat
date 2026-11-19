package cn.acechat.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 按「逐码点换色」重建一条消息：<b>只改指定字符的颜色，其余一切原样保留</b>
 * （尖括号、正文、队标前缀、字体、粗体斜体下划线、点击/悬停事件都不动）。
 *
 * <p><b>为什么要单独写一个类（踩坑记录）</b>：最早的实现是把整条消息 {@code getString()} 拍平成纯文本、
 * 再拼成「前缀 + 名字 + 后缀」三个 literal —— 尖括号颜色、正文格式、点击/悬停事件、整合包与服务器的
 * 各种样式全都会丢。现在按 {@link Component#toFlatList(Style)} 的「每段文本 + 该段的最终样式」重建：
 * 渲染结果与原来逐段一致，只有落进覆盖区间的那几个字符换色。
 *
 * <p>{@code toFlatList} 取到的样式是<b>已经合并过父层</b>的最终样式，这一点是实测确认的、不是猜的：
 * 见 {@code _build\tools\StyleProbe.java}；本类的行为用 {@code _build\tools\NameRecolorProbe.java} 无头验证。
 */
final class NameRecolor {
    /** 覆盖表里的「不改动」标记。 */
    static final int KEEP = 0;

    private NameRecolor() {
    }

    /** 只改「名字那几个字」的颜色（找不到名字时原样返回同一个实例）。 */
    static Component recolorName(Component message, String name, int argb) {
        return recolor(message, overridesFor(message, name, argb, KEEP));
    }

    /**
     * 名字用 {@code nameArgb}，名字两侧的「&lt;&gt;」符号用 {@code bracketArgb}
     * ——与顶部 HUD 用同一个符号颜色设置，保证两处的「&lt;&gt;」同色。
     */
    static Component recolorNameAndBrackets(Component message, String name, int nameArgb, int bracketArgb) {
        return recolor(message, overridesFor(message, name, nameArgb, bracketArgb));
    }

    /**
     * 生成覆盖表：下标 = 码点序号，值 = 该码点要用的 ARGB（{@link #KEEP} 表示保持原样）。
     * 名字区间整段换色；尖括号只换「名字前最近的 &lt;」与「名字后最近的 &gt;」两个字符本身，
     * 所以 {@code <[管理]Name>} 里的队标颜色不受影响。
     */
    private static int[] overridesFor(Component message, String name, int nameArgb, int bracketArgb) {
        if (message == null || name == null || name.isEmpty()) {
            return null;
        }

        String flat = message.getString();
        int index = flat.indexOf(name);
        if (index < 0) {
            return null;
        }

        int[] overrides = new int[flat.codePointCount(0, flat.length())];
        int from = flat.substring(0, index).codePointCount(0, index);
        int to = from + name.codePointCount(0, name.length());
        for (int i = from; i < to && i < overrides.length; i++) {
            overrides[i] = nameArgb;
        }

        if (bracketArgb != KEEP) {
            markBracket(overrides, flat, flat.lastIndexOf('<', index - 1), index, 24, bracketArgb);
            int close = flat.indexOf('>', index + name.length());
            markBracket(overrides, flat, close, index, 24, bracketArgb);
        }

        return overrides;
    }

    /** 把找到的括号字符（charIndex）换算成码点下标并写进覆盖表；距离名字超过 maxDistance 就不认。 */
    private static void markBracket(int[] overrides, String flat, int charIndex, int nameCharIndex, int maxDistance, int argb) {
        if (charIndex < 0 || Math.abs(charIndex - nameCharIndex) > maxDistance) {
            return;
        }

        int codePoint = flat.substring(0, charIndex).codePointCount(0, charIndex);
        if (codePoint >= 0 && codePoint < overrides.length) {
            overrides[codePoint] = argb;
        }
    }

    /** 按覆盖表重建消息（覆盖表为 null 时原样返回，调用方靠 {@code rebuilt != original} 判断要不要写回）。 */
    private static Component recolor(Component message, int[] overrides) {
        if (message == null || overrides == null) {
            return message;
        }

        MutableComponent result = Component.empty();
        int[] cursor = {0};
        for (Component run : message.toFlatList(Style.EMPTY)) {
            appendRun(result, run, cursor, overrides);
        }

        return result;
    }

    /** 把一段按「覆盖表里颜色相同」切成若干小段追加进去；没被覆盖的码点连样式一起原样保留。 */
    private static void appendRun(MutableComponent target, Component run, int[] cursor, int[] overrides) {
        String text = run.getString();
        int length = text.codePointCount(0, text.length());
        int start = cursor[0];
        cursor[0] = start + length;
        if (length == 0) {
            return;
        }

        Style style = run.getStyle();
        int offset = 0;
        while (offset < length) {
            int argb = argbAt(overrides, start + offset);
            int end = offset;
            while (end < length && argbAt(overrides, start + end) == argb) {
                end++;
            }

            Style partStyle = argb == KEEP ? style : style.withColor(TextColor.fromRgb(argb & 0xFFFFFF));
            target.append(Component.literal(slice(text, offset, end)).withStyle(partStyle));
            offset = end;
        }
    }

    private static int argbAt(int[] overrides, int codePoint) {
        return codePoint >= 0 && codePoint < overrides.length ? overrides[codePoint] : KEEP;
    }

    /** 按码点切子串（消息里可能有中文/emoji，不能按 char 切）。 */
    private static String slice(String text, int fromCodePoint, int toCodePoint) {
        int from = text.offsetByCodePoints(0, fromCodePoint);
        int to = text.offsetByCodePoints(0, toCodePoint);
        return text.substring(from, to);
    }
}
