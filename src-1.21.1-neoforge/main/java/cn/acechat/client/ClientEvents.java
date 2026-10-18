package cn.acechat.client;

import cn.acechat.AceChat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.UUID;

/** Forge 总线上的客户端事件：拦截玩家聊天、渲染 HUD、清理状态、响应快捷键。 */
@EventBusSubscriber(modid = AceChat.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        UUID sender = event.getSender();
        // 聊天栏（T）开着时一律不拦截：回放只在打开瞬间注入一次，吞掉新消息会导致聊天栏「不刷新」
        boolean chatScreenOpen = minecraft.screen instanceof net.minecraft.client.gui.screens.ChatScreen;
        // 没有 sender / 系统消息 / 命令反馈：属于「其它信息」，由第二个开关控制。
        if (sender == null || event.isSystem() || event.getBoundChatType() == null) {
            // 死亡消息（death.*）在这里解析——与「左下角显示其它信息」开关无关，照样记录
            CommsState.rememberDeathMessage(event.getMessage());
            // 顶部 HUD 关掉时一切回归原版：不拦截，系统消息/指令反馈照常进原版聊天
            if (CommsConfig.hudEnabled.get() && !CommsConfig.vanillaOtherMessages.get() && !chatScreenOpen) {
                // 与玩家聊天一致：不在左下角显示，但记下来，按 T 打开聊天框时仍能看到
                VanillaChatReplay.rememberOther(event.getMessage());
                event.setCanceled(true);
            }

            return;
        }
        // 左下角聊天的玩家名字统一由「角色卡 → 玩家名字颜色」控制：
        // 无论左下角开关是开是关、也不管消息是原版显示还是重放注入，名字颜色都一致。
        applyConfiguredNameColor(event, sender, CommsState.resolveName(minecraft, sender));
        // 顶部 HUD 关掉时=「模组不接管聊天」，一切回归原版：
        // 不隐藏这条消息、不写自己的历史、也不注入——原版聊天里本来就有全部记录。
        if (!CommsConfig.hudEnabled.get()) {
            return;
        }
        // 先按开关决定「左下角要不要显示这条玩家聊天」——与是否成功路由无关，
        // 否则联机时某些消息（插件格式化、路由被过滤）会漏到左下角滞留。
        boolean showVanilla = CommsConfig.vanillaPlayerChat.get();
        CommsState.route(minecraft, sender, event.getMessage(), event.getBoundChatType());
        // 聊天栏开着时不取消：否则新发言被吞掉，看起来就是「聊天栏不刷新」
        if (!showVanilla && !chatScreenOpen) {
            event.setCanceled(true);
        }
    }


    /**
     * 按「角色卡 → 玩家名字颜色」改写消息里的玩家名字颜色，让左下角原版聊天也和顶部 HUD 同色。
     *
     * <p><b>这里原本是坏的（实机日志定位）</b>：旧代码写的是 {@code if (argb < 0) return;} 当「未配置」处理，
     * 但 {@link CommsConfig#nameColor} 返回的是 ARGB —— 带 FF alpha 的正常颜色本来就是负数
     * （本机名单蓝 {@code #FF4C9AFF} = {@code -11756801}），所以左下角<b>一次都没被改写过</b>，
     * 一直显示原版/整合包自己的颜色（实测 {@code #FF00AA00} 深绿），与 HUD 的名单蓝对不上。
     * 「未配置」的哨兵值是 {@code -1}（见 {@code nameColor}），不是「负数」。
     *
     * <p>现在与 HUD / T 注入走同一条规则：名单色优先，没配过就用「玩家名初始颜色」
     * （{@link CommsConfig#chatNameColor}）——三处因此必然同色。
     */
    private static void applyConfiguredNameColor(ClientChatReceivedEvent event, UUID sender, String name) {
        Component original = event.getMessage();
        int argb = configuredNameColor(sender, name);
        // 名字用名单色/初始色；名字两侧的「<>」与顶部 HUD 用同一个「「<>」符号颜色」设置（默认白）
        Component rebuilt = NameRecolor.recolorNameAndBrackets(original, name, argb, CommsConfig.chatSymbolColor.get());
        if (rebuilt != original) {
            event.setMessage(rebuilt);
        }
    }

    /** 与 {@link CommsConfig#nameStyleFor} 完全一致的取色：名单色优先，未配置（-1）用「玩家名初始颜色」。 */
    private static int configuredNameColor(UUID sender, String name) {
        int argb = CommsConfig.nameColor(sender == null ? null : sender.toString(), name);
        return argb == -1 ? CommsConfig.chatNameColor.get() : argb;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen screen) {
            int x = screen.width / 2 - 100;
            int y = screen.height - 52;
            event.addListener(Button.builder(Component.translatable("screen.acechat.config"), button ->
                    Minecraft.getInstance().setScreen(new CommsConfigScreen(screen)))
                    .bounds(x, y, 200, 20).build());
        }

        // 打开配置界面时重新读一次皮肤，保证看到的是玩家「当前」的皮肤（含 CustomSkinLoader 加载的）
        if (event.getScreen() instanceof CommsConfigScreen) {
            PortraitSource.clear();
        }

        // 打开原版聊天框：把先前发言临时回放进去，这样 T 打开时能看到全部历史
        if (event.getScreen() instanceof ChatScreen) {
            // 只有聊天界面（按 T）才需要重放历史；其它界面（背包/箱子/配置…）绝不能注入，
        // 否则每次开界面都会把旧聊天重新塞进原版列表，表现为「旧消息永远残留」。
        // 聊天界面（按 T）打开时重放历史 —— 开关关闭时也要重放，否则聊天界面里看不到玩家历史。
        // 打开其它界面（背包/箱子/配置…）绝不注入，否则旧聊天会被反复塞回左下角。
        if (event.getScreen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
            VanillaChatReplay.inject(Minecraft.getInstance());
        }
        }
    }

    /**
     * 玩家加入场景时只失效他一个人的皮肤缓存。
     * 皮肤解析只发生在 进游戏 / 玩家加入 / 收到消息 / 打开配置界面 / 手动刷新 这些时机，
     * 渲染阶段不做任何查找，避免每帧扫描玩家列表带来的性能开销。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Player player) {
            PortraitSource.invalidate(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiEvent.Post event) {
        CommsHud.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        AceChatFolders.ensureOnClient();   // 仅首次真正检查磁盘（内部 guard）
        CommsState.tick(minecraft);
        CommsState.scanDeaths(minecraft);   // 死亡覆盖：谁在发言期间死了就记下来
        // 原版聊天框关掉了 → 把【本模组注入的】回放条目摘掉（retract 只删自己注入的那些，
        // 系统消息 / 指令反馈 / 成就等原版历史一概不动）。
        // 这里曾经还调过 clearVanillaChatNow（原版 clearMessages），那会把整个原版聊天历史清空 ——
        // 结果就是「按 T 看不到任何历史（连指令反馈都没有）」，已去掉。
        if (!(minecraft.screen instanceof ChatScreen)) {
            VanillaChatReplay.retract(minecraft);
        }
        while (CommsKeybinds.TOGGLE.consumeClick()) {
            CommsState.toggleSession();
            if (minecraft.level != null) {
                minecraft.gui.getChat().addMessage(Component.translatable(CommsState.isSessionEnabled()
                        ? "text.comms.enabled" : "text.comms.disabled"));
            }
        }
        while (CommsKeybinds.CONFIG.consumeClick()) {
            minecraft.setScreen(new CommsConfigScreen(minecraft.screen));
        }
        while (CommsKeybinds.PREVIEW.consumeClick()) {
            CommsState.preview(minecraft);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == Minecraft.getInstance().player) {
            CommsState.clear();
        VanillaChatReplay.reset();
        }
    }

    private ClientEvents() {
    }
}
