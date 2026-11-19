package cn.acechat.client;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 取组策略（纯逻辑，便于无头测试）。
 *
 * <ul>
 *   <li>{@code sequential}：永远取第一组（顺序剧本）；</li>
 *   <li>{@code random}：按权重随机；{@code noRepeat} 时排除上一次抽到的组；</li>
 *   <li>{@code cycle}：按顺序轮换（录制可复现）。</li>
 * </ul>
 */
public final class StagePick {
    public static int choose(StageScripts.Rule rule, Random rng, String lastPicked, int cycleCount) {
        List<String> play = rule.play();
        int size = play.size();
        if (size <= 1) {
            return 0;
        }
        String pick = rule.pick() == null ? "" : rule.pick().toLowerCase(Locale.ROOT);
        if (pick.isEmpty()) {
            pick = "random";
        }
        if ("cycle".equals(pick)) {
            return Math.floorMod(cycleCount, size);
        }
        if (!"random".equals(pick) && !"weighted".equals(pick)) {
            return 0;   // sequential
        }

        int[] weights = new int[size];
        boolean weighted = rule.weights().size() == size;
        for (int i = 0; i < size; i++) {
            weights[i] = weighted ? Math.max(0, rule.weights().get(i)) : 1;
        }
        if (rule.noRepeat() && lastPicked != null) {
            for (int i = 0; i < size; i++) {
                if (play.get(i).equals(lastPicked)) {
                    weights[i] = 0;
                }
            }
        }
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        if (total <= 0) {
            // 权重全是 0（或全被 noRepeat 排除）：退回等权，仍尽量避开上一次
            for (int i = 0; i < size; i++) {
                weights[i] = rule.noRepeat() && play.get(i).equals(lastPicked) ? 0 : 1;
            }
            total = 0;
            for (int weight : weights) {
                total += weight;
            }
            if (total <= 0) {
                return 0;
            }
        }
        int roll = rng.nextInt(total);
        for (int i = 0; i < size; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return i;
            }
        }
        return size - 1;
    }

    private StagePick() {
    }
}
