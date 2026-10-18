package cn.acechat.client;

import cn.acechat.AceChat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 模组总线上的客户端注册：快捷键。 */
@Mod.EventBusSubscriber(modid = AceChat.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModClientEvents {
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CommsKeybinds.TOGGLE);
        event.register(CommsKeybinds.PREVIEW);
        event.register(CommsKeybinds.CONFIG);
    }

    private ModClientEvents() {
    }
}
