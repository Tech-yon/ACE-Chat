package cn.acechat.server;

import cn.acechat.AceChat;
import cn.acechat.network.AceChatNetwork;
import cn.acechat.network.StatsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端侧：每秒把在线玩家的数值打包发给装了本模组的客户端。
 *
 * <p>只在<b>专用服务器/联机主端</b>生效（{@code Dist.DEDICATED_SERVER}）；
 * 单人游戏里客户端本来就有自己的数据，不需要这条通道。
 *
 * <p>不修改任何游戏数据、不写任何配置 —— 只是读取并广播。
 */
@Mod.EventBusSubscriber(modid = AceChat.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class ServerStatsSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final int INTERVAL_TICKS = 20;
    private static int counter;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (++counter < INTERVAL_TICKS) {
            return;
        }

        counter = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !AceChatNetwork.isRegistered()) {
            return;
        }

        List<ServerPlayer> players = playersOf(server);
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
            AceChatNetwork.sendTo(player, payload);
        }
    }

    /**
     * 取在线玩家列表。
     *
     * <p>说明：本仓库编译用的 {@code mc-official.jar} 里 {@code MinecraftServer.getPlayerList()}
     * 仍是 SRG 名（{@code m_6846_}），而 {@code PlayerList.getPlayers()} 是官方名 —— 所以这里用
     * 反射双名兜底（与 {@code CommsState.applyChatHeight} 处理聊天高度那次同一套思路）。
     */
    private static List<ServerPlayer> playersOf(MinecraftServer server) {
        try {
            Object list;
            try {
                list = server.getClass().getMethod("getPlayerList").invoke(server);
            } catch (NoSuchMethodException missing) {
                list = server.getClass().getMethod("m_6846_").invoke(server);
            }

            if (list instanceof net.minecraft.server.players.PlayerList playerList) {
                return playerList.getPlayers();
            }
        } catch (Exception error) {
            LOGGER.debug("[acechat] 读取服务端玩家列表失败（服务端同步暂停）", error);
        }

        return List.of();
    }

    private ServerStatsSync() {
    }
}