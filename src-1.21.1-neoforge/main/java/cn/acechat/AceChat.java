package cn.acechat;

import cn.acechat.client.CommsConfigScreen;
import cn.acechat.client.CommsSounds;
import cn.acechat.network.AceChatNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** ACE Chat：玩家通讯 HUD（NeoForge 1.21.1 版）。 */
@Mod(AceChat.MOD_ID)
public final class AceChat {
    public static final String MOD_ID = "acechat";

    public AceChat(IEventBus modBus, ModContainer container) {
        // 每次加载进游戏时，自动在游戏版本目录下生成自定义素材文件夹（sounds / portraits / filters）
        cn.acechat.client.AceChatFolders.ensure();

        CommsSounds.SOUND_EVENTS.register(modBus);
        // 可选数据通道（服务端 → 客户端），通道本身是 optional 的，服务端没装也不影响进服
        AceChatNetwork.register(modBus);
        // NeoForge 21.1：配置界面用 IConfigScreenFactory 扩展点
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new CommsConfigScreen(parent));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
