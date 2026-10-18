package cn.acechat.client;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * NeoForge 端口暂时没有服务端数据通道（网络 API 与 Forge 不同，需要单独实现）。
 * 这里保留同样的接口、永远返回空 → 渲染时自然回到 Unknown，与"服务端没装"表现一致。
 */
public final class RemoteStats {
    public static void update(UUID id, double health, double maxHealth, int food, int armor) {
    }

    public static void clear() {
    }

    public static int size() {
        return 0;
    }

    public static OptionalDouble health(UUID id) {
        return OptionalDouble.empty();
    }

    public static OptionalDouble maxHealth(UUID id) {
        return OptionalDouble.empty();
    }

    public static OptionalInt food(UUID id) {
        return OptionalInt.empty();
    }

    public static OptionalInt armor(UUID id) {
        return OptionalInt.empty();
    }

    private RemoteStats() {
    }
}