package cn.acechat.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 顶部聊天框的排版（纯逻辑，不碰渲染，方便无头验证）。
 *
 * <p>布局形状：<pre>
 *   名字                          ← 第一行：只有玩家名（整行居中）
 *   &lt;&lt;&lt; 正文开头……            ← 正文第一行：「&lt;&lt;&lt;」与正文最左端同行
 *   正文中段……                    ← 中间行：只有正文，按字符填满一行才换行
 *   正文结尾 &gt;&gt;&gt;              ← 正文最后一行：「&gt;&gt;&gt;」与正文最右端同行
 * </pre>
 *
 * <p><b>为什么不用 {@code Font.split()}（踩坑记录）</b>：它是「按空格断词」的。
 * 中文正文整段没有空格，于是断点落在「&lt;」后面那个间隔空格上，结果是
 * 「名字 + &lt;」一行、「正文 + &gt;」另一行 —— 用户截图里那个错位。
 * 现在按<b>字符</b>折行，并按用户 2026-10-12 的要求保证：
 * <ol>
 *   <li>正文过长时自动换行，每行填满才换（内容尽量往上堆，不留半空行）；</li>
 *   <li>单行最大宽度 = 聊天框宽度 − 左右内边距（由调用方传入 {@code wrap}）；</li>
 *   <li>名字独占第一行；「&lt;&lt;&lt;」与正文最左端在同一行，「&gt;&gt;&gt;」与正文最右端在同一行；</li>
 *   <li>行数超过上限时，最后一行用「…」收尾并补上「&gt;」（不再直接把尾巴截掉）。</li>
 * </ol>
 */
final class ChatLayout {
    /** 一行要画的内容：{@code first} 表示带「名字 + &lt;」前缀，{@code close} 表示带「&gt;」后缀。 */
    record Line(String body, boolean first, boolean close, boolean ellipsis) {
    }

    /** 排版结果：实际显示的名字（可能被截断成 …）+ 若干行。 */
    record Result(String name, List<Line> lines) {
    }

    private ChatLayout() {
    }

    /**
     * @param name  玩家名（会按可用宽度截断）
     * @param body  正文
     * @param open  第一行前缀里的符号部分（例如 {@code " <"}，含间隔空格）
     * @param close 最后一行后缀里的符号部分（例如 {@code ">"}，含间隔空格）
     * @param width 文本宽度测量函数（游戏里传 {@code font::width}，探针里传假实现）
     * @param wrap  单行最大宽度（像素）
     * @param maxLines 最多显示几行（&gt;= 1）
     */
    static Result layout(String name, String body, String open, String close,
                         ToIntFunction<String> width, int wrap, int maxLines) {
        String text = body == null ? "" : body;
        int limit = Math.max(1, wrap);
        int max = Math.max(1, maxLines);
        int openWidth = width.applyAsInt(open);
        int closeWidth = width.applyAsInt(close);

        // 名字单独占第一行：先按「名字最大宽度」截断过（调用方做的），
        // 这里再兜一层 —— 名字也不能超过聊天框宽度，否则会顶出框外。
        String shownName = shorten(name == null ? "" : name, width, limit);

        // 正文：第一行要给「<<<」留位置，其余行（含最后一行）统一预留「>>>」的宽度。
        // 一律预留（而不是先切一遍再判断重切）—— 规则确定，单行/多行都不会再出现超宽的那一行。
        List<String> chunks = split(text, width, Math.max(1, limit - openWidth), Math.max(1, limit - closeWidth));

        boolean truncated = chunks.size() > max;
        int count = Math.min(chunks.size(), max);
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean first = i == 0;
            boolean last = i == count - 1;
            lines.add(new Line(chunks.get(i), first, last, last && truncated));
        }

        return new Result(shownName, lines);
    }

    /** 按字符贪心切行：第一行用 firstLimit，其余用 limit。 */
    private static List<String> split(String text, ToIntFunction<String> width, int firstLimit, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lineLimit = firstLimit;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            // 放不下就换行；current 为空说明一个字就超过整行宽度，也只能画上去（避免死循环）
            if (current.length() > 0 && width.applyAsInt(current.toString()) + width.applyAsInt(character) > lineLimit) {
                chunks.add(current.toString());
                current.setLength(0);
                lineLimit = limit;
                continue;
            }

            current.append(character);
            index += Character.charCount(codePoint);
        }

        chunks.add(current.toString());
        return chunks;
    }

    /** 按可用宽度截断字符串（截断了就补「…」），按码点切，不会切坏中文/emoji。 */
    static String shorten(String text, ToIntFunction<String> width, int budget) {
        if (text.isEmpty() || width.applyAsInt(text) <= budget) {
            return text;
        }

        String ellipsis = "…";
        int ellipsisWidth = width.applyAsInt(ellipsis);
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String next = builder.toString() + new String(Character.toChars(codePoint));
            if (width.applyAsInt(next) + ellipsisWidth > budget) {
                break;
            }

            builder.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }

        return builder + ellipsis;
    }

    private static String firstCodePoint(String text) {
        return text.substring(0, text.offsetByCodePoints(0, 1));
    }
}
