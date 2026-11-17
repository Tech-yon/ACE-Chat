package cn.acechat.network;

import cn.acechat.AceChat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：玩家按下了某条「快捷键触发」的触发器。
 *
 * <p>服务端把它当成一次一次性信号 {@code acechat.manual}（keyword 为 {@code #n}），
 * 仍按事件组的与/或/非关系判定，触发一次后信号被消费掉。
 */
public record ManualPayload(String project, String rule, int index) implements CustomPacketPayload {
    public static final Type<ManualPayload> TYPE = new Type<>(AceChat.id("manual"));

    public static final StreamCodec<ByteBuf, ManualPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManualPayload::project,
            ByteBufCodecs.STRING_UTF8, ManualPayload::rule,
            ByteBufCodecs.VAR_INT, ManualPayload::index,
            ManualPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
