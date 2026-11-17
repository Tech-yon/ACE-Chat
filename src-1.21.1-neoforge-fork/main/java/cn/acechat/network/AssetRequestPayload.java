package cn.acechat.network;

import cn.acechat.AceChat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端 → 服务端：请求缺少的资源（JSON：{"keys":["skin|slim/x.png|sha", ...]}）。 */
public record AssetRequestPayload(String json) implements CustomPacketPayload {
    public static final Type<AssetRequestPayload> TYPE = new Type<>(AceChat.id("asset_request"));

    public static final StreamCodec<ByteBuf, AssetRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AssetRequestPayload::json, AssetRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
