package cn.acechat.network;

import cn.acechat.AceChat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：所有在线玩家的数值快照（每秒一次）。
 *
 * <p>1.21 的写法：实现 {@link CustomPacketPayload}，用 {@link #TYPE} 声明通道 id、
 * 用 {@link #STREAM_CODEC} 声明编解码；注册在 {@link AceChatNetwork}。
 *
 * <p>只发数据，不带任何"要客户端改配置/改卡"的指令 —— 客户端只把它当作
 * 显示用的补充信息（{@code RemoteStats}），过期就回到 Unknown。
 */
public record StatsPayload(List<Entry> entries) implements CustomPacketPayload {
    /** 一名玩家的数值。 */
    public record Entry(UUID id, float health, float maxHealth, int food, int armor) {
    }

    /** 通道 id：acechat:sync（与 1.20.1 版同名，方便日志对照）。 */
    public static final CustomPacketPayload.Type<StatsPayload> TYPE =
            new CustomPacketPayload.Type<>(AceChat.id("sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatsPayload> STREAM_CODEC =
            CustomPacketPayload.codec(StatsPayload::encode, StatsPayload::decode);

    public StatsPayload {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(StatsPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buffer.writeUUID(entry.id());
            buffer.writeFloat(entry.health());
            buffer.writeFloat(entry.maxHealth());
            buffer.writeVarInt(entry.food());
            buffer.writeVarInt(entry.armor());
        }
    }

    private static StatsPayload decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buffer.readUUID(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readVarInt(), buffer.readVarInt()));
        }

        return new StatsPayload(entries);
    }
}
