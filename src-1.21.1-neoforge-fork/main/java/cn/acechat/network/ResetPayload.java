package cn.acechat.network;

import cn.acechat.AceChat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：把某个事件组在我这里的「一次性 / 冷却」记录重置掉。
 */
public record ResetPayload(String project, String rule) implements CustomPacketPayload {
    public static final Type<ResetPayload> TYPE = new Type<>(AceChat.id("stage_reset"));

    public static final StreamCodec<ByteBuf, ResetPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ResetPayload::project,
            ByteBufCodecs.STRING_UTF8, ResetPayload::rule,
            ResetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
