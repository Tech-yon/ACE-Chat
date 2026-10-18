package cn.acechat.client;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组的自定义素材目录：每次加载进游戏时自动在「游戏版本目录」（即游戏的工作目录）下生成。
 *
 * <pre>
 * ACE_config/README.txt      总说明
 * ACE_config/sounds/         自定义音效（new_message / message_gone，支持 mp3 与 wav）
 * ACE_config/portraits/      自定义立绘（png）
 * ACE_config/filters/        自定义叠加滤镜（png）
 * </pre>
 *
 * 目录不存在就创建；说明文件只在缺失时写入（不会覆盖你改过的内容）。
 * 全部失败都不影响游戏，只记一条日志。
 */
public final class AceChatFolders {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");

    private static boolean readmeChecked;
    /** 目录检查整个会话只做一次（以前挂在每 tick 上，每秒上百次磁盘调用会严重掉帧）。 */
    private static boolean ensured;
    private static boolean loggedFailure;
    /** 老的 ACE_config 只搬迁一次。 */
    private static boolean migrated;

    private AceChatFolders() {
    }

    public static Path rootDir() {
        return Path.of("ACE_config");
    }

    /** 老版本的位置（只用于搬迁与兼容判断）。 */
    public static Path legacyRootDir() {
        return Path.of("config", "acechat");
    }

    /** 整体配置的导出目录（界面里的文件选择框就是列它里面的 json）。 */
    public static Path exportDir() {
        return rootDir().resolve("export");
    }

    /** 角色卡的导出/导入目录。 */
    public static Path cardExportDir() {
        return exportDir().resolve("cards");
    }

    public static Path soundsDir() {
        return rootDir().resolve("sounds");
    }

    public static Path portraitsDir() {
        return rootDir().resolve("portraits");
    }

    public static Path filtersDir() {
        return rootDir().resolve("filters");
    }

    public static Path deathsDir() {
        return rootDir().resolve("deaths");
    }

    /** 角色卡目录：每张卡一个文件（<玩家名>.json，默认卡 _default.json）。 */
    public static Path cardsDir() {
        return rootDir().resolve("cards");
    }


    /**
     * 把老目录 {@code ACE_config/} 搬到 {@code ACE_config/}。
     *
     * <p>整树复制（已存在的文件不覆盖）→ 老目录改名为 {@code ACE_config_backup_before_move} 留备份，
     * <b>不删任何文件</b>；新目录已经有内容也不会被覆盖。整个会话只尝试一次。
     */
    public static void migrateLegacyIfNeeded() {
        if (migrated) {
            return;
        }

        migrated = true;
        try {
            Path legacy = legacyRootDir();
            if (!Files.isDirectory(legacy)) {
                return;
            }

            Path root = rootDir();
            Files.createDirectories(root);
            try (var stream = Files.walk(legacy)) {
                stream.forEach(source -> copyIfMissing(legacy, root, source));
            }

            Path backup = Path.of("config", "acechat_backup_before_move");
            if (!Files.exists(backup)) {
                Files.move(legacy, backup);
                LOGGER.info("[acechat] 已把 config/acechat 搬到 ACE_config/，老目录改名为 config/acechat_backup_before_move 作为备份");
            } else {
                LOGGER.info("[acechat] 已把 config/acechat 的内容补进 ACE_config/（备份目录已存在，未再改名）");
            }
        } catch (Exception error) {
            LOGGER.warn("[acechat] 搬迁 config/acechat 到 ACE_config 失败（可以手工搬，功能与数据都不会丢）", error);
        }
    }

    private static void copyIfMissing(Path legacyRoot, Path newRoot, Path source) {
        try {
            Path target = newRoot.resolve(legacyRoot.relativize(source).toString());
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
            } else if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        } catch (Exception ignored) {
            // 单个文件失败不影响其它文件
        }
    }

    /** 确保所有目录存在；顺手补上缺失的说明文件。每次进入游戏都会调用（幂等、开销极小）。 */
    public static void ensure() {
        if (ensured) {
            return;   // 已检查过就不再碰磁盘
        }

        try {
            Files.createDirectories(rootDir());
            Files.createDirectories(soundsDir());
            Files.createDirectories(portraitsDir());
            Files.createDirectories(filtersDir());
            Files.createDirectories(deathsDir());
            Files.createDirectories(cardsDir());
            Files.createDirectories(exportDir());
            Files.createDirectories(cardExportDir());
            if (!readmeChecked) {
                readmeChecked = true;
                writeIfMissing(rootDir().resolve("README.txt"), ROOT_README);
                writeIfMissing(soundsDir().resolve("README.txt"), SOUND_README);
                writeIfMissing(portraitsDir().resolve("README.txt"), PORTRAIT_README);
                writeIfMissing(filtersDir().resolve("README.txt"), FILTER_README);
                writeIfMissing(deathsDir().resolve("README.txt"), DEATH_README);
            }

            ensured = true;
        } catch (Exception error) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.warn("[acechat] 创建 ACE_config 素材目录失败（可手动创建，功能不受影响）", error);
            }
        }
    }

    /** 客户端 tick 里调用一次即可（构造期目录可能还没就绪）。 */
    /** 客户端 tick 里调用的兜底：已检查过就直接返回（零磁盘开销）。 */
    public static void ensureOnClient() {
        if (!ensured && Minecraft.getInstance() != null) {
            ensure();
        }

        ensured = true;   // 即使这次没成功（例如工作目录未就绪）也不再每 tick 重试
    }

    private static void writeIfMissing(Path path, String content) {
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 写不了就算了，不影响游戏
        }
    }

    private static final String ROOT_README = """
            ACE Chat 自定义素材目录
            ========================

            这个文件夹由模组在每次进入游戏时自动生成。把素材放进对应的子文件夹，
            再回到游戏里点一下配置界面里的「重新扫描…」按钮即可选到。

              sounds/     自定义通讯音效
                          new_message.mp3  或 new_message.wav    → 新聊天消息出现时
                          message_gone.mp3 或 message_gone.wav   → 聊天消息消失时
                          格式：mp3（任意常见码率）或 wav（16 位 PCM）；建议 ≤5 秒，超过 10 秒会截断

              portraits/  自定义立绘（形象框里的人物图）
                          任意 .png；建议竖构图、256×256 以上
                          界面里：半身像 HUD → 立绘 → 重新扫描立绘目录

              filters/    自定义叠加滤镜（铺在整张形象框上的质感层）
                          任意带透明通道的 .png；扫描线/噪点这类平铺质感建议用 16~64 像素的小图
                          界面里：滤镜模板 → 重新扫描滤镜目录

            配置界面路径：选项 → ACE Chat 配置（聊天框 / 半身像 HUD / 滤镜模板 …）
            配置文件：ACE_config/client.json（可用界面里的「导入/导出」备份）
            """;

    private static final String SOUND_README = """
            把自定义音效放在这个文件夹里（文件名必须是下面两个之一）：

              new_message.mp3   或  new_message.wav    → 刚发出/收到新聊天消息时
              message_gone.mp3  或  message_gone.wav   → 聊天消息从屏幕上消失时

            格式要求：
              .mp3  普通 MP3 即可（模组内置解码器，会转成 16 位 PCM 播放）
              .wav  16 位 PCM，单声道或立体声，8k~48k 采样率
              建议时长 ≤ 5 秒；超过 10 秒会被截断（避免盖住后面的消息）
              不支持 ogg（请转成 mp3 或 wav）

            放好后不用重启游戏：下次播放时会自动读取（改了文件也会重新读）。
            没放文件时用模组内置的无线电风格音效（可在界面里换预设、调音量/音调/速度/淡入淡出）。
            音量在「ACE Chat 配置 → 聊天框」里调，同一页有「试听两个音效」按钮。
            """;

    private static final String PORTRAIT_README = """
            把自定义立绘放在这个文件夹里（只支持 PNG，带透明通道更好）：

              *.png   例如  my_portrait.png

            建议：竖构图、256×256 以上（会被等比缩放铺进形象框）。
            放好后进「ACE Chat 配置 → 半身像 HUD → 立绘」，点「重新扫描立绘目录」，
            然后在列表里点一下文件名即可选用；缩放与位置用「立绘缩放 / 立绘 X 偏移 / 立绘 Y 偏移」调。
            没有立绘或读取失败时会自动回落到皮肤绘制。
            """;

    private static final String DEATH_README = """
            把「死亡覆盖图」放在这个文件夹里，文件名用玩家名：

              <玩家名>.png     例如 PilotA.png

            规则：
              只读 1:1 方图（宽高必须相等，非方图会被忽略并在日志里提示一次）；
              读取/显示尺寸上限在「ACE Chat 配置 → 半身像 HUD」里调（64~1024，默认 256）；
              缩放、X/Y 偏移同样在那里调，叠加滤镜也会作用在这一层。

            触发条件：该玩家的聊天消息正在顶部显示时他死亡 → 用这张图覆盖他的立绘/皮肤；
            保留到那条消息消失或被新消息替换为止（复活不会立刻恢复）；只影响别人，自己不变。
            没放图片时使用内置预设（黑底 + 居中白色 NO SIGN），同样刚好铺满背景框。
            """;

    private static final String FILTER_README = """
            把自定义叠加滤镜放在这个文件夹里（PNG，带透明通道）：

              *.png

            滤镜会铺满整张形象框（边框、照片、文字都覆盖），配合「混合模式」使用：
              正常 / 正片叠底（压暗，适合扫描线、暗角）/ 滤色（提亮）/ 加法（发光）

            尺寸随意，会被拉伸铺满；想要平铺质感（扫描线、噪点）建议做小尺寸 PNG（16~64 像素）。
            内置滤镜（干扰线·竖向/横向、噪点、暗角、双色调、点阵）不需要文件。
            放好后回到游戏，点「ACE Chat 配置 → 滤镜模板 → 重新扫描滤镜目录」。
            """;
}
