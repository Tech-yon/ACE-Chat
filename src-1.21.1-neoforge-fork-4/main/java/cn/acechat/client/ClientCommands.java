package cn.acechat.client;

import cn.acechat.AceChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 实验版客户端命令（只在本地生效，不影响服务器、也不需要权限）：
 * <pre>
 * /acechat stage play &lt;工程&gt; [规则]        —— 播工程：单规则工程可省略规则
 * /acechat stage group &lt;工程&gt; &lt;组&gt;        —— 直接播某一组
 * /acechat stage groups &lt;工程&gt;             —— 列出组
 * /acechat stage rules &lt;工程&gt;              —— 列出规则（含取组方式）
 * /acechat stage line &lt;说话人&gt; &lt;文本&gt;       —— 播一条（默认 50 tick）
 * /acechat stage hold &lt;说话人&gt; &lt;tick&gt; &lt;文本&gt;
 * /acechat stage pause | resume | next | stop | status
 * /acechat stage example                    —— 生成示例工程
 * </pre>
 */
@EventBusSubscriber(modid = AceChat.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("acechat")
                        .then(Commands.literal("stage")
                                .then(Commands.literal("play")
                                        .then(Commands.argument("project", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String project = StringArgumentType.getString(ctx, "project");
                                                    report(StagePlayer.play(Minecraft.getInstance(), project),
                                                            "播放 " + project);
                                                    return 1;
                                                })
                                                .then(Commands.argument("rule", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            String project = StringArgumentType.getString(ctx, "project");
                                                            String rule = StringArgumentType.getString(ctx, "rule");
                                                            report(StagePlayer.playRule(Minecraft.getInstance(), project, rule),
                                                                    "播放 " + project + " / 规则 " + rule);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("group")
                                        .then(Commands.argument("project", StringArgumentType.word())
                                                .then(Commands.argument("group", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            String project = StringArgumentType.getString(ctx, "project");
                                                            String group = StringArgumentType.getString(ctx, "group");
                                                            report(StagePlayer.playGroup(Minecraft.getInstance(), project, group),
                                                                    "播放 " + project + " / 组 " + group);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("groups")
                                        .then(Commands.argument("project", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    reply("组: " + StagePlayer.groupsOf(StringArgumentType.getString(ctx, "project")));
                                                    return 1;
                                                })))
                                .then(Commands.literal("rules")
                                        .then(Commands.argument("project", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    reply("规则: " + StagePlayer.rulesOf(StringArgumentType.getString(ctx, "project")));
                                                    return 1;
                                                })))
                                .then(Commands.literal("line")
                                        .then(Commands.argument("speaker", StringArgumentType.word())
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            StagePlayer.line(Minecraft.getInstance(),
                                                                    StringArgumentType.getString(ctx, "speaker"),
                                                                    StringArgumentType.getString(ctx, "text"),
                                                                    50);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("hold")
                                        .then(Commands.argument("speaker", StringArgumentType.word())
                                                .then(Commands.argument("ticks", IntegerArgumentType.integer(4, 2400))
                                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    StagePlayer.line(Minecraft.getInstance(),
                                                                            StringArgumentType.getString(ctx, "speaker"),
                                                                            StringArgumentType.getString(ctx, "text"),
                                                                            IntegerArgumentType.getInteger(ctx, "ticks"));
                                                                    return 1;
                                                                })))))
                                .then(Commands.literal("pause")
                                        .executes(ctx -> {
                                            StagePlayer.pause();
                                            reply("已暂停（时间轴停住，屏幕上那一条继续挂着）");
                                            return 1;
                                        }))
                                .then(Commands.literal("resume")
                                        .executes(ctx -> {
                                            StagePlayer.resume();
                                            reply("已继续");
                                            return 1;
                                        }))
                                .then(Commands.literal("next")
                                        .executes(ctx -> {
                                            StagePlayer.next(Minecraft.getInstance());
                                            reply("下一句：" + StagePlayer.progress());
                                            return 1;
                                        }))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> {
                                            StagePlayer.stop();
                                            reply("已停止演出");
                                            return 1;
                                        }))
                                .then(Commands.literal("status")
                                        .executes(ctx -> {
                                            reply(statusText());
                                            return 1;
                                        }))
                                .then(Commands.literal("example")
                                        .executes(ctx -> {
                                            reply("示例工程: " + StageScripts.writeExample());
                                            return 1;
                                        }))));
    }

    private static void report(boolean ok, String what) {
        if (ok) {
            reply(what + " → " + StagePlayer.progress());
        } else {
            reply("失败: " + StagePlayer.lastError());
        }
    }

    private static String statusText() {
        if (StagePlayer.isPlaying()) {
            StringBuilder text = new StringBuilder("演出中: ").append(StagePlayer.progress());
            if (!StagePlayer.ruleName().isEmpty()) {
                text.append("（规则 ").append(StagePlayer.ruleName()).append('）');
            }
            if (StagePlayer.isPaused()) {
                text.append("（已暂停）");
            }
            if (StagePlayer.isWaiting()) {
                text.append("（等待 next）");
            }
            return text.toString();
        }
        if (CommsState.stageActive()) {
            return "单条演出中（剩余 " + CommsState.stageRemaining() + " ms）";
        }
        return "空闲";
    }

    private static void reply(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[acechat] " + text));
        }
    }

    private ClientCommands() {
    }
}
