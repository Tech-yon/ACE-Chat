package cn.acechat.network;

import cn.acechat.AceChat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：本次工程用到的角色资源清单（JSON 字符串）。
 *
 * <pre>
 * {"cards":[{"id":"队长","character":{...}}],
 *  "files":[{"kind":"skin","id":"slim/队长.png","sha":"..","size":8123},
 *           {"kind":"portrait","id":"队长立绘.png","sha":"..","size":40211}]}
 * </pre>
 */
public record AssetManifestPayload(String json) implements CustomPacketPayload {
    public static final Type<AssetManifestPayload> TYPE = new Type<>(AceChat.id("asset_manifest"));

    public static final StreamCodec<ByteBuf, AssetManifestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AssetManifestPayload::json, AssetManifestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
