package cn.acechat.client;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 服务端模块下发的「其它玩家数值」缓存（只读，不写配置文件）。
 *
 * <p>服务端每 20 tick 发一次快照；这里存 3 秒，过期就当作"没有"，
 * 于是渲染时仍然回到 Unknown —— 与"服务端没装"时行为完全一致。
 */
public final class RemoteStats {
    /** 一条快照：血量（含上限）、饥饿、护甲。 */
    public record Snapshot(double health, double maxHealth, int food, int armor, long receivedAt) {
        boolean fresh(long now) {
            return now - receivedAt <= TTL_MS;
        }
    }

    private static final long TTL_MS = 3000L;
    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    public static void update(UUID id, double health, double maxHealth, int food, int armor) {
        if (id == null) {
            return;
        }

        CACHE.put(id, new Snapshot(health, maxHealth, food, armor, System.currentTimeMillis()));
    }

    /** 断线/换服时清空，避免拿到上一个服务器的旧数据。 */
    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }

    private static Snapshot fresh(UUID id) {
        if (id == null) {
            return null;
        }

        Snapshot snapshot = CACHE.get(id);
        return snapshot != null && snapshot.fresh(System.currentTimeMillis()) ? snapshot : null;
    }

    public static OptionalDouble health(UUID id) {
        Snapshot s = fresh(id);
        return s == null ? OptionalDouble.empty() : OptionalDouble.of(s.health());
    }

    public static OptionalDouble maxHealth(UUID id) {
        Snapshot s = fresh(id);
        return s == null ? OptionalDouble.empty() : OptionalDouble.of(s.maxHealth());
    }

    public static OptionalInt food(UUID id) {
        Snapshot s = fresh(id);
        return s == null ? OptionalInt.empty() : OptionalInt.of(s.food());
    }

    public static OptionalInt armor(UUID id) {
        Snapshot s = fresh(id);
        return s == null ? OptionalInt.empty() : OptionalInt.of(s.armor());
    }

    private RemoteStats() {
    }
}
