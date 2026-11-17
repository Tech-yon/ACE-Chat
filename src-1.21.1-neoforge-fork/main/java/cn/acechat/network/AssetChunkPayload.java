package cn.acechat.network;

import cn.acechat.AceChat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 服务端 → 客户端：资源分块（kind|id|sha|index|total|bytes）。 */
public record AssetChunkPayload(String kind, String id, String sha, int index, int total, byte[] data)
        implements CustomPacketPayload {
    public static final Type<AssetChunkPayload> TYPE = new Type<>(AceChat.id("asset_chunk"));

    public static final StreamCodec<ByteBuf, AssetChunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AssetChunkPayload::kind,
            ByteBufCodecs.STRING_UTF8, AssetChunkPayload::id,
            ByteBufCodecs.STRING_UTF8, AssetChunkPayload::sha,
            ByteBufCodecs.VAR_INT, AssetChunkPayload::index,
            ByteBufCodecs.VAR_INT, AssetChunkPayload::total,
            ByteBufCodecs.BYTE_ARRAY, AssetChunkPayload::data,
            AssetChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
