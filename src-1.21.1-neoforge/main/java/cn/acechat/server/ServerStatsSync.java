package cn.acechat.server;

import cn.acechat.AceChat;
import cn.acechat.network.AceChatNetwork;
import cn.acechat.network.StatsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端侧：每秒把在线玩家的数值打包发给装了本模组的客户端。
 *
 * <p>只在<b>专用服务器</b>生效（运行时判 {@code server.isDedicatedServer()}）；
 * 单人游戏里客户端本来就有自己的数据，不需要这条通道。
 *
 * <p>不修改任何游戏数据、不写任何配置 —— 只是读取并广播。
 */
@EventBusSubscriber(modid = AceChat.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ServerStatsSync {
    private static final int INTERVAL_TICKS = 20;
    private static int counter;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++counter < INTERVAL_TICKS) {
            return;
        }

        counter = 0;
        MinecraftServer server = event.getServer();
        if (server == null || !server.isDedicatedServer() || !AceChatNetwork.isRegistered()) {
            return;
        }

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        List<StatsPayload.Entry> entries = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            entries.add(new StatsPayload.Entry(
                    player.getUUID(),
                    player.getHealth(),
                    player.getMaxHealth(),
                    player.getFoodData().getFoodLevel(),
                    player.getArmorValue()));
        }

        StatsPayload payload = new StatsPayload(entries);
        for (ServerPlayer player : players) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private ServerStatsSync() {
    }
}
