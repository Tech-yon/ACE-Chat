package cn.acechat.network;

import cn.acechat.client.RemoteStats;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：所有在线玩家的数值快照（每秒一次）。
 *
 * <p>只发数据，不带任何"要客户端改配置/改卡"的指令 —— 客户端只把它当作
 * 显示用的补充信息（{@link RemoteStats}），过期就回到 Unknown。
 */
public final class StatsPayload {
    /** 一名玩家的数值。 */
    public record Entry(UUID id, float health, float maxHealth, int food, int armor) {
    }

    private final List<Entry> entries;

    public StatsPayload(List<Entry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static void encode(StatsPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buffer.writeUUID(entry.id());
            buffer.writeFloat(entry.health());
            buffer.writeFloat(entry.maxHealth());
            buffer.writeVarInt(entry.food());
            buffer.writeVarInt(entry.armor());
        }
    }

    public static StatsPayload decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buffer.readUUID(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readVarInt(), buffer.readVarInt()));
        }

        return new StatsPayload(entries);
    }

    public static void handle(StatsPayload payload, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        // 只有客户端会收到这个包；服务端上即使收到也只是丢弃
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.apply(payload));
        ctx.setPacketHandled(true);
    }

    private StatsPayload() {
        this.entries = List.of();
    }

    /** 客户端侧的应用逻辑（单独一个内部类，避免服务端加载客户端类）。 */
    private static final class ClientHandler {
        private static void apply(StatsPayload payload) {
            for (Entry entry : payload.entries) {
                RemoteStats.update(entry.id(), entry.health(), entry.maxHealth(), entry.food(), entry.armor());
            }
        }
    }
}