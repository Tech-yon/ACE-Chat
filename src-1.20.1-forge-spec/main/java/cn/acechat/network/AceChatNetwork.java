package cn.acechat.network;

import cn.acechat.AceChat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 模组的网络通道（目前只有一个包：{@link StatsPayload}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>单向</b>：服务端 → 客户端；客户端从不向服务端发任何东西；</li>
 *   <li><b>可选</b>：服务端没装本模组时，客户端一切照旧（没有通道 = 没有数据 = 显示 Unknown）；</li>
 *   <li><b>安全</b>：发送前用 {@link SimpleChannel#isRemotePresent} 判断对方装没装，不装就不发，
 *       避免 Forge 在日志里刷"缺少通道"的警告。</li>
 * </ul>
 */
public final class AceChatNetwork {
    private static final String PROTOCOL = "1";

    private static SimpleChannel channel;

    /** 在模组构造阶段调用（两侧都会调；服务端只是登记，不发包）。 */
    public static void register() {
        if (channel != null) {
            return;
        }

        channel = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(AceChat.MOD_ID, "sync"))
                // 关键：接受任意版本号（含"对方没有这条通道"的情况）。
                // 只接受自己的 "1" 会让 Forge 把通道当成必装：客户端会被
                // "Channels [acechat:sync] rejected their server side version number" 拦下，
                // 表现为"连不上没装模组的服务器"。这里放开后通道纯属可选：
                // 对方有就通信、没有就当作没有数据（显示 Unknown）。
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .networkProtocolVersion(() -> PROTOCOL)
                .simpleChannel();

        channel.messageBuilder(StatsPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatsPayload::encode)
                .decoder(StatsPayload::decode)
                .consumerMainThread(StatsPayload::handle)
                .add();
    }

    /** 对方客户端装没装本模组（没装就不要发，否则 Forge 会告警）。 */
    public static boolean canReceive(ServerPlayer player) {
        if (channel == null || player == null || player.connection == null) {
            return false;
        }

        try {
            return channel.isRemotePresent(player.connection.connection);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void sendTo(ServerPlayer player, StatsPayload payload) {
        if (channel == null || payload == null || !canReceive(player)) {
            return;
        }

        channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static boolean isRegistered() {
        return channel != null;
    }

    private AceChatNetwork() {
    }
}