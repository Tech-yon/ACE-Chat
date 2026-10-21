package cn.acechat.network;

import cn.acechat.client.RemoteStats;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组的网络通道（目前只有一个包：{@link StatsPayload}）。
 *
 * <p>设计要点（与 1.20.1 Forge 版一致）：
 * <ul>
 *   <li><b>单向</b>：服务端 → 客户端；客户端从不向服务端发任何东西；</li>
 *   <li><b>可选</b>：{@code registrar.optional()} —— 服务端没装本模组时客户端一切照旧
 *       （没有数据 → 显示 Unknown），也不会因为"缺少通道"被踢下线；</li>
 *   <li><b>安全</b>：服务端只在 {@code isDedicatedServer()} 时广播（见 {@code ServerStatsSync}）。</li>
 * </ul>
 */
public final class AceChatNetwork {
    /** 协议版本（与 1.20.1 版一致；因为是可选通道，对方版本不同也不会拦人）。 */
    private static final String PROTOCOL = "1";
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");

    private static boolean registered;

    /** 在模组构造阶段调用（mod 事件总线）。 */
    public static void register(IEventBus modBus) {
        if (registered) {
            return;
        }

        registered = true;
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            event.registrar(PROTOCOL)
                    .optional()
                    .playToClient(StatsPayload.TYPE, StatsPayload.STREAM_CODEC,
                            (payload, context) -> context.enqueueWork(() -> apply(payload)));
            LOGGER.info("[acechat] 已注册可选数据通道 acechat:sync（服务端 → 客户端）");
        });
    }

    public static boolean isRegistered() {
        return registered;
    }

    /** 客户端侧的应用逻辑（服务端永远不会走这里）。 */
    private static void apply(StatsPayload payload) {
        for (StatsPayload.Entry entry : payload.entries()) {
            RemoteStats.update(entry.id(), entry.health(), entry.maxHealth(), entry.food(), entry.armor());
        }
    }

    private AceChatNetwork() {
    }
}
