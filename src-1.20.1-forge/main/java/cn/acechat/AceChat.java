package cn.acechat;

import cn.acechat.client.CommsConfig;
import cn.acechat.client.CommsSounds;
import cn.acechat.client.CommsConfigScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** ACE Chat：玩家通讯 HUD（Forge 1.20.1 版）。 */
@Mod(AceChat.MOD_ID)
public final class AceChat {
    public static final String MOD_ID = "acechat";

    public AceChat() {
        cn.acechat.network.AceChatNetwork.register();
        // 每次加载进游戏时，自动在游戏版本目录下生成自定义素材文件夹（sounds / portraits / filters）
        cn.acechat.client.AceChatFolders.ensure();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CommsSounds.SOUND_EVENTS.register(modBus);
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) ->
                        new CommsConfigScreen(parent)));
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
