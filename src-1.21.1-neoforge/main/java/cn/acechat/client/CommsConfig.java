package cn.acechat.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** ACE Chat 的客户端配置。主文件是 config/acechat/client.json，旧版 TOML 只作兼容读取。 */
public final class CommsConfig {
    public enum ChatFilter { ALL_PLAYERS, WHITELIST_ONLY }
    public enum Anchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }
    public enum Horizontal { LEFT, CENTER, RIGHT }
    public enum Vertical { TOP, CENTER, BOTTOM }

    public static final class ValueAdapter {
        private final java.util.function.IntSupplier getter;
        private final java.util.function.IntConsumer setter;
        public ValueAdapter(java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) { this.getter = getter; this.setter = setter; }
        public int get() { return getter.getAsInt(); }
        public void set(int value) { setter.accept(value); }
    }

    public static final class Value<T> {
        private T value;
        private final T fallback;
        private final Function<T, T> clamp;
        private Value(T fallback, Function<T, T> clamp) { this.fallback = fallback; this.value = fallback; this.clamp = clamp; }
        public T get() { return value; }
        public void set(T value) { this.value = clamp.apply(value == null ? fallback : value); }
        public void reset() { value = fallback; }
    }

    public static final class NameColor {
        public String uuid = "";
        public String name = "";
        public int color = 0xFFFFFFFF;
        public String note = "";
        public NameColor() { }
        public NameColor(String uuid, String name, int color, String note) { this.uuid = uuid; this.name = name; this.color = color; this.note = note; }
    }

    public static final class TextElement {
        public String content = "%player_name%";
        public Horizontal horizontal = Horizontal.CENTER;
        public Vertical vertical = Vertical.BOTTOM;
        public int x = 0;
        public int y = -4;
        public int size = 9;
        public int color = 0xFFFFFFFF;
        public boolean shadow = true;
        public int maxWidth = 120;
        /** 是否显示（老配置没有这个字段，读出来是 null，按「显示」处理）。 */
        public Boolean visible = Boolean.TRUE;
        /** 元素种类：name / vitals 是固定的两个，custom 可增删改。 */
        public String kind = KIND_CUSTOM;
        public TextElement() { }
        public TextElement(String content, Horizontal horizontal, Vertical vertical, int x, int y, int size, int color, boolean shadow, int maxWidth) {
            this.content = content; this.horizontal = horizontal; this.vertical = vertical; this.x = x; this.y = y; this.size = size; this.color = color; this.shadow = shadow; this.maxWidth = maxWidth;
        }
        public TextElement copy() {
            TextElement copy = new TextElement(content, horizontal, vertical, x, y, size, color, shadow, maxWidth);
            copy.visible = visible == null ? Boolean.TRUE : visible;
            copy.kind = kind;
            return copy;
        }
    }

    public static final Value<Boolean> hudEnabled = bool(true);
    public static final Value<Integer> centerXOffset = integer(0, -1000, 1000);
    public static final Value<Integer> topYOffset = integer(24, 0, 1000);
    public static final Value<Integer> maxLines = integer(5, 1, 12);
    public static final Value<Integer> boxWidth = integer(300, 120, 900);
    public static final Value<Double> backgroundAlpha = decimal(0.62D, 0.0D, 1.0D);
    public static final Value<Double> lineHoldSeconds = decimal(6.0D, 0.5D, 60.0D);
    /** 每条发言的【最短】停留时间（秒）。设为 0 = 下一位玩家一发言立刻替换。 */
    public static final Value<Double> holdMinSeconds = decimal(1.0D, 0.0D, 5.0D);
    /** 每条发言的【最长】停留时间（秒）：消息越长越接近这个值。 */
    public static final Value<Double> holdMaxSeconds = decimal(6.0D, 1.0D, 15.0D);
    public static final Value<Integer> chatBackgroundColor = color(0xFF1C2838);
    /** 聊天框右端与形象框之间至少留出的间距（像素）。 */
    public static final Value<Integer> chatRightGap = integer(12, 0, 240);
    /** 聊天框距屏幕左右两边的最小留白（像素）。 */
    public static final Value<Integer> chatLeftMargin = integer(8, 0, 240);
    /** 聊天里「<>」符号与内容之间的内侧间隔（像素；按 4px 粒度折算，左右半边同时生效）。 */
    public static final Value<Integer> chatSymbolPadding = integer(4, 0, 16);
    /** 聊天里「<>」符号的颜色（ARGB）。默认白色：顶部 HUD、按 T 的历史行、左下角原版聊天三处统一用它。 */
    public static final Value<Integer> chatSymbolColor = integer(0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF);
    /** 聊天里「玩家名」最多占用多宽（像素），超过就截断成 …  */
    public static final Value<Integer> chatNameMaxWidth = integer(120, 20, 400);
    /** 顶部聊天里玩家名的初始颜色（没在名单里配色时用它），默认蓝色。 */
    public static final Value<Integer> chatNameColor = color(0xFF4C9AFF);
    public static final Value<Integer> chatPaddingX = integer(8, 0, 8);
    public static final Value<Integer> chatPaddingY = integer(5, 0, 8);
    public static final Value<Boolean> chatBorderEnabled = bool(false);
    public static final Value<Integer> chatBorderColor = color(0xFF63E66B);
    public static final Value<Integer> chatBorderThickness = integer(1, 0, 2);

    public static final Value<ChatFilter> filter = new Value<>(ChatFilter.ALL_PLAYERS, v -> v == null ? ChatFilter.ALL_PLAYERS : v);
    public static final Value<Boolean> removeFromVanillaChat = bool(false);

    /**
     * 预览页的拖动目标：0 = 关闭拖动，1 = 只拖卡片，2 = 只拖文字元素，3 = 全部。
     * 默认只拖卡片，避免在预览区随手一按就抓到别的部件（误触）。
     */
    public static final Value<Integer> previewDragTarget = integer(1, 0, 3);

    /**
     * 不滚动时，屏幕上最多显示多少条聊天（0 = 不限制）。
     * 只裁剪「显示列表」，历史列表不动 —— 按 T 滚动仍能读到全部历史。
     */
    public static final Value<Integer> chatVisibleLines = integer(5, 0, 30);
    /** 按 T 打开的聊天栏里，最多显示多少行（0 = 不限制）；超出的用滚轮查看。 */
    public static final Value<Integer> chatScreenLines = integer(10, 0, 30);

    /** 性能诊断：开启后 HUD 每秒打印一次各阶段耗时（排查掉帧用）。 */
    public static final Value<Boolean> debugTiming = bool(false);

    // ---- 角色卡 ----
    /** 当前编辑/应用的角色卡名（默认卡为 _default）。 */
    public static final Value<String> activeCard = text(PlayerCards.DEFAULT_CARD);
    /** 是否按「当前显示玩家」自动切换角色卡（关掉就一直用当前卡）。 */
    public static final Value<Boolean> autoCardByPlayer = bool(true);

    /** 左下角原版位置是否显示【玩家聊天】（默认关：玩家聊天只在顶部 HUD 出现）。 */
    public static final Value<Boolean> vanillaPlayerChat = bool(false);
    /** 左下角原版位置是否显示【其它信息】（系统消息 / 命令反馈 / 成就等，默认开）。 */
    public static final Value<Boolean> vanillaOtherMessages = bool(true);
    public static final Value<Boolean> includeActions = bool(false);
    public static final Value<List<String>> whitelist = list(List.of());

    public static final Value<Boolean> portraitEnabled = bool(true);
    public static final Value<Integer> portraitWidth = integer(122, 80, 300);
    public static final Value<Integer> portraitHeight = integer(138, 90, 340);
    public static final Value<Integer> portraitXOffset = integer(0, -1000, 1000);
    public static final Value<Integer> portraitYOffset = integer(0, -1000, 1000);
    public static final Value<Double> portraitScale = decimal(1.0D, 0.5D, 2.0D);
    public static final Value<Anchor> portraitAnchor = new Value<>(Anchor.TOP_RIGHT, v -> v == null ? Anchor.TOP_RIGHT : v);
    public static final Value<Double> portraitXRatio = decimal(0.98D, 0.0D, 1.0D);
    public static final Value<Double> portraitYRatio = decimal(0.08D, 0.0D, 1.0D);
    public static final Value<Boolean> portraitFollowChat = bool(false);
    /** 常驻开关：打开后没有聊天时也一直显示形象框（显示自己的形象），关闭则只在玩家发言期间显示。 */
    public static final Value<Boolean> portraitAlwaysVisible = bool(false);
    public static final Value<Integer> portraitTint = color(0xFFFFFFFF);
    public static final Value<Integer> portraitOpacity = integer(100, 0, 100);
    public static final Value<Integer> portraitBrightness = integer(100, 30, 100);
    public static final Value<Integer> portraitPanelColor = color(0xFF1C2838);
    public static final Value<Integer> portraitPanelAlpha = integer(92, 0, 100);
    public static final Value<Integer> portraitBorderColor = color(0xFF63E66B);
    public static final Value<Integer> portraitBorderThickness = integer(1, 0, 2);
    // 半身像里那块「背景板」：默认是深色板，可单独改颜色/透明度/圆角/内缩
    public static final Value<Boolean> portraitBackdropEnabled = bool(true);
    public static final Value<Integer> portraitBackdropColor = color(0xFF0B1016);
    public static final Value<Integer> portraitBackdropAlpha = integer(85, 0, 100);
    public static final Value<Integer> portraitBackdropPadding = integer(5, 0, 16);
    /** 半身像人物相对照片框的大小（%）。 */
    public static final Value<Integer> portraitBustScale = integer(100, 40, 150);
    // 证件卡式布局：上半是照片框（只画头/头肩/半身），下半是文字区
    /** 0=只画头像 1=头肩（默认） 2=半身 */
    public static final Value<Integer> portraitPhotoMode = integer(4, 0, 4);
    /** 照片区占面板内区高度的百分比（%）。 */
    public static final Value<Integer> portraitPhotoHeight = integer(60, 30, 90);
    /** 照片区与文字区之间的细分隔线。 */
    public static final Value<Boolean> portraitPhotoDivider = bool(true);
    // ================= 形象框滤镜模板 =================
    /** 叠加滤镜总开关。 */
    public static final Value<Boolean> filterEnabled = bool(false);
    /** 当前滤镜 id：内置用 builtin:xxx，用户 PNG 用文件名。 */
    public static final Value<String> filterName = text("");
    /** 滤镜强度（%）。 */
    /** 叠加滤镜的「颜色蒙版」：与滤镜图层做乘法叠加（白色 = 不改色）。 */
    public static final Value<Integer> filterTint = integer(0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF);
    /** 滤镜自身调色：亮度 / 对比度 / 饱和度 / 色相（烘焙进滤镜贴图，不影响别的模块）。 */
    public static final Value<Integer> filterBrightness = integer(100, 30, 180);
    public static final Value<Integer> filterContrast = integer(100, 30, 200);
    public static final Value<Integer> filterSaturation = integer(100, 0, 200);
    public static final Value<Integer> filterHue = integer(0, -180, 180);
    /** 每个内置特效自己的颜色（"id=ARGB" 列表，没配置就用默认）。 */
    public static final Value<List<String>> filterEffectColors = list(List.of());

    public static final Value<Integer> filterAlpha = integer(65, 0, 100);
    /** 混合模式：0 正常 1 正片叠底 2 滤色 3 加法。 */
    public static final Value<Integer> filterBlend = integer(1, 0, 3);
    /** 调色（CPU 烘焙）总开关。 */
    public static final Value<Boolean> gradeEnabled = bool(false);
    public static final Value<Integer> gradeBrightness = integer(100, 30, 180);
    public static final Value<Integer> gradeContrast = integer(100, 30, 200);
    public static final Value<Integer> gradeSaturation = integer(100, 0, 200);
    public static final Value<Integer> gradeHue = integer(0, -180, 180);
    public static final Value<Boolean> duotoneEnabled = bool(false);
    public static final Value<Integer> duotoneDark = color(0xFF14202B);
    public static final Value<Integer> duotoneLight = color(0xFF63E66B);
    public static final Value<Integer> duotoneAmount = integer(70, 0, 100);
    /** 保存下来的滤镜模板。 */
    public static final List<FilterTemplate> filterTemplates = new ArrayList<>();

    /** 二层皮肤总开关：帽子/外套/袖子/裤子这几块外层贴图（原来的「显示帽子层」已并入这一项）。 */
    public static final Value<Boolean> portraitOuterLayer = bool(true);
    public static final Value<Boolean> capeEnabled = bool(true);
    public static final Value<Boolean> customPortraitEnabled = bool(true);
    public static final Value<List<String>> customPortraits = list(List.of());
    public static final Value<List<String>> displayNames = list(List.of());
    public static final Value<List<String>> customInfo = list(List.of());
    public static final Value<Boolean> blipEnabled = bool(true);
    public static final Value<Double> soundVolume = decimal(0.55D, 0.0D, 1.0D);
    // ---- 通讯音效参数（出现音 / 消失音各自独立）----
    /** 出现音预设（合成器预设 id）。 */
    public static final Value<String> newSoundPreset = text("radio_open");
    public static final Value<Integer> newSoundVolume = integer(100, 0, 200);
    public static final Value<Integer> newSoundPitch = integer(100, 50, 200);
    public static final Value<Integer> newSoundFadeIn = integer(0, 0, 200);
    public static final Value<Integer> newSoundFadeOut = integer(120, 0, 400);
    public static final Value<Integer> newSoundSpeed = integer(100, 25, 400);
    /** 消失音预设（合成器预设 id）。 */
    public static final Value<String> goneSoundPreset = text("radio_close");
    public static final Value<Integer> goneSoundVolume = integer(100, 0, 200);
    public static final Value<Integer> goneSoundPitch = integer(100, 50, 200);
    public static final Value<Integer> goneSoundFadeIn = integer(0, 0, 200);
    public static final Value<Integer> goneSoundFadeOut = integer(200, 0, 400);
    public static final Value<Integer> goneSoundSpeed = integer(100, 25, 400);

    /** 消息消失时的音效开关（与新消息音效分开设置）。 */
    public static final Value<Boolean> goneSoundEnabled = bool(true);
    // ---- 形象框：立绘（自定义 PNG）----
    /** 立绘/皮肤切换：true = 用 config/acechat/portraits/ 里的 PNG 当立绘。 */
    public static final Value<Boolean> portraitUseImage = bool(false);
    /** 皮肤/立绘绘制时的 X/Y 偏移（像素），各显示模式通用。 */
    public static final Value<Integer> skinOffsetX = integer(0, -120, 120);
    public static final Value<Integer> skinOffsetY = integer(0, -120, 120);

    // ---- 死亡覆盖图 ----
    /** 是否启用死亡覆盖图（玩家发言期间死亡时覆盖其形象）。 */
    public static final Value<Boolean> deathOverlayEnabled = bool(true);
    /** 死亡覆盖图预设（"" = 只用 deaths 目录里的文件，builtin:no_signal = 黑底 NO SIGN）。 */
    public static final Value<String> deathPreset = text("builtin:no_signal");
    /** 死亡覆盖图读取/显示尺寸上限（像素），最高 1024。 */
    public static final Value<Integer> deathImageMaxSize = integer(256, 64, 1024);
    public static final Value<Integer> deathImageScale = integer(100, 20, 400);
    public static final Value<Integer> deathImageOffsetX = integer(0, -200, 200);
    public static final Value<Integer> deathImageOffsetY = integer(0, -200, 200);
    /** 选中的立绘文件名（留空 = 用目录里第一个 PNG）。 */
    public static final Value<String> portraitImageName = text("");
    /** 立绘缩放（%）。 */
    public static final Value<Integer> portraitImageScale = integer(100, 10, 400);
    public static final Value<Integer> portraitImageOffsetX = integer(0, -200, 200);
    public static final Value<Integer> portraitImageOffsetY = integer(0, -200, 200);

    public static final List<NameColor> nameColors = new ArrayList<>();
    public static final List<TextElement> textElements = new ArrayList<>();
    /** 模组总目录：游戏主文件夹下的 ACE_config/（角色卡、素材、导出都集中在里面）。 */
    public static final Path CONFIG_DIR = AceChatFolders.rootDir();
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("client.json");
    public static final Path EXPORT_DIR = CONFIG_DIR.resolve("export");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;

    static {
        load();
    }

    private static Value<Boolean> bool(boolean value) { return new Value<>(value, v -> v); }
    private static Value<Integer> integer(int value, int min, int max) { return new Value<>(value, v -> Math.max(min, Math.min(max, v == null ? value : v))); }
    private static Value<Double> decimal(double value, double min, double max) { return new Value<>(value, v -> Math.max(min, Math.min(max, v == null ? value : v))); }
    private static Value<Integer> color(int value) { return new Value<>(value, v -> v == null ? value : v); }
    private static Value<String> text(String value) { return new Value<>(value, v -> v == null ? value : v); }
    private static Value<List<String>> list(List<String> value) { return new Value<>(List.copyOf(value), v -> v == null ? List.of() : List.copyOf(v)); }

    /** 一套滤镜模板：叠加滤镜 + 调色参数。 */
    public static final class FilterTemplate {
        public String name = "";
        public boolean filterEnabled;
        public String filterName = "";
        public int filterAlpha = 65;
        public int filterBlend = 1;
        public boolean gradeEnabled;
        public int gradeBrightness = 100;
        public int gradeContrast = 100;
        public int gradeSaturation = 100;
        public int gradeHue;
        public boolean duotoneEnabled;
        public int duotoneDark = 0xFF14202B;
        public int duotoneLight = 0xFF63E66B;
        public int duotoneAmount = 70;

        public FilterTemplate() { }
    }

    /** 把当前滤镜设置抓成一套模板。 */
    public static FilterTemplate captureTemplate(String name) {
        FilterTemplate t = new FilterTemplate();
        t.name = name == null ? "" : name;
        t.filterEnabled = filterEnabled.get();
        t.filterName = filterName.get();
        t.filterAlpha = filterAlpha.get();
        t.filterBlend = filterBlend.get();
        t.gradeEnabled = gradeEnabled.get();
        t.gradeBrightness = gradeBrightness.get();
        t.gradeContrast = gradeContrast.get();
        t.gradeSaturation = gradeSaturation.get();
        t.gradeHue = gradeHue.get();
        t.duotoneEnabled = duotoneEnabled.get();
        t.duotoneDark = duotoneDark.get();
        t.duotoneLight = duotoneLight.get();
        t.duotoneAmount = duotoneAmount.get();
        return t;
    }

    /** 套用一套模板。 */
    public static void applyTemplate(FilterTemplate t) {
        if (t == null) return;
        filterEnabled.set(t.filterEnabled);
        filterName.set(t.filterName == null ? "" : t.filterName);
        filterAlpha.set(t.filterAlpha);
        filterBlend.set(t.filterBlend);
        gradeEnabled.set(t.gradeEnabled);
        gradeBrightness.set(t.gradeBrightness);
        gradeContrast.set(t.gradeContrast);
        gradeSaturation.set(t.gradeSaturation);
        gradeHue.set(t.gradeHue);
        duotoneEnabled.set(t.duotoneEnabled);
        duotoneDark.set(t.duotoneDark);
        duotoneLight.set(t.duotoneLight);
        duotoneAmount.set(t.duotoneAmount);
        PortraitGrade.invalidate();
        save();
    }

    /** 调色参数的指纹（换了参数就要重新烘焙）。 */
    public static String gradeKey() {
        return gradeBrightness.get() + "/" + gradeContrast.get() + "/" + gradeSaturation.get() + "/" + gradeHue.get()
                + "/" + duotoneEnabled.get() + "/" + duotoneDark.get() + "/" + duotoneLight.get() + "/" + duotoneAmount.get();
    }
    // ==================== 文字元素：两个固定内置 + 任意自定义 ====================
    public static final String KIND_NAME = "name";
    public static final String KIND_VITALS = "vitals";
    /** 第三条固定内置：饥饿（生命下面一行，内容与位置由模组负责）。 */
    public static final String KIND_FOOD = "food";
    /** 第四条固定内置：护甲（饥饿下面一行，渲染与生命/饥饿完全一致）。 */
    public static final String KIND_ARMOR = "armor";
    public static final String KIND_CUSTOM = "custom";
    /** 生命/饱食度那一行的固定内容。 */
    public static final String VITALS_CONTENT = "生命 %health%/%max_health%";
    /** 饱食度单独一行（与生命拆成两个文字元素）。 */
    public static final String FOOD_CONTENT = "饥饿 %food%/%max_food%";
    /** 护甲单独一行（与生命/饥饿同规格）。 */
    public static final String ARMOR_CONTENT = "护甲 %armor%";
    /**
     * v13/v14 时代的「生命 + 饥饿」同一行文案。
     *
     * <p><b>踩坑记录</b>：v13/v14 的迁移函数本来是把血条文案改成「这一行也带饥饿」，当时直接赋
     * {@link #VITALS_CONTENT}；后来 v17 把生命与饥饿拆成两个元素，这个常量被改成了「只含生命」，
     * 于是 v13 的迁移变成「把饥饿去掉」的同一件事 —— 老配置（v11/v12）升级时
     * 「v12 去饥饿 → v13 本该加回来（实际没加）→ v14 空转 → v17 不触发」，
     * <b>饥饿就被永久丢掉了</b>（1.21.1 实例那份 v11 配置就是这么丢的）。
     * 现在这两个迁移函数改用这个专用常量，v17 的拆分迁移随后就能正常把它拆成两个元素。
     */
    private static final String LEGACY_VITALS_WITH_FOOD = "生命 %health%/%max_health%  饥饿 %food%/%max_food%";
    /** 读不到就算 Unknown（用于别人的护甲/饥饿/延迟等客户端拿不到的数据）。 */
    public static final String UNKNOWN = "Unknown";
    /**
     * 文字元素布局的「基准卡片宽度」：在重置位置/首次进入时记录当时的卡片宽度。
     * 之后的卡片缩放都以它为 1.0 倍基准 —— 这样预设坐标在最开始那个尺寸下是精确的，
     * 只有当你再改卡片大小/缩放时文字才等比缩放（以前拿 122 当基准，会把 80 宽的卡算成 0.66 倍，导致文字跑位）。
     */
    public static int elementBaseWidth;

    /** 元素随卡片缩放的系数（基准尺寸下为 1.0）。 */
    public static float elementScale(int innerWidth) {
        int base = elementBaseWidth > 0 ? elementBaseWidth : Math.max(1, portraitWidth.get());
        if (base <= 0) {
            return 1.0F;
        }

        return Math.max(0.6F, Math.min(1.8F, innerWidth / (float) base));
    }



    /** 元素是否显示（老配置没有这个字段，视为显示）。 */
    public static boolean isVisible(TextElement element) {
        return element == null || element.visible == null || element.visible;
    }

    private static TextElement createBuiltIn(String kind) {
        TextElement element;
        if (KIND_NAME.equals(kind)) {
            element = new TextElement("%player_name%", Horizontal.LEFT, Vertical.TOP, -1, 76, 8, 0xFFD6DEE8, true, 160);
        } else if (KIND_FOOD.equals(kind)) {
            // 第三条固定内置：紧贴「生命」下面一行；字号/颜色/阴影与生命一致
            element = new TextElement(FOOD_CONTENT, Horizontal.LEFT, Vertical.TOP, -2, 100, 6, 0xFF9AA6B2, false, 160);
        } else if (KIND_ARMOR.equals(kind)) {
            // 第四条固定内置：紧贴「饥饿」下面一行；字号/颜色/阴影与生命、饥饿完全一致
            element = new TextElement(ARMOR_CONTENT, Horizontal.LEFT, Vertical.TOP, 40, 92, 6, 0xFF9AA6B2, false, 160);
        } else {
            element = new TextElement(VITALS_CONTENT, Horizontal.LEFT, Vertical.TOP, -2, 100, 6, 0xFF9AA6B2, false, 160);
        }
        element.kind = kind;
        element.visible = Boolean.TRUE;
        return element;
    }

    /** 取固定的内置元素（玩家名称 / 生命·饱食度），没有就补一个。 */
    public static TextElement builtIn(String kind) {
        for (TextElement element : textElements) {
            if (kind.equals(element.kind)) {
                return element;
            }
        }

        TextElement created = createBuiltIn(kind);
        if (KIND_ARMOR.equals(kind)) {
            int at = textElements.size();
            for (int i = 0; i < textElements.size(); i++) {
                String existing = textElements.get(i).kind;
                if (KIND_VITALS.equals(existing) || KIND_FOOD.equals(existing)) {
                    at = i + 1;
                }
            }
            textElements.add(at, created);
        } else if (KIND_FOOD.equals(kind)) {
            // 饥饿排在「生命」后面，保证列表顺序始终是 玩家名称 / 生命 / 饥饿
            int at = textElements.size();
            for (int i = 0; i < textElements.size(); i++) {
                if (KIND_VITALS.equals(textElements.get(i).kind)) {
                    at = i + 1;
                    break;
                }
            }
            textElements.add(at, created);
        } else {
            textElements.add(0, created);
        }
        return created;
    }

    /** 自定义元素（可以增删改内容）。 */
    public static List<TextElement> customElements() {
        List<TextElement> result = new ArrayList<>();
        for (TextElement element : textElements) {
            if (!KIND_NAME.equals(element.kind) && !KIND_VITALS.equals(element.kind)
                    && !KIND_FOOD.equals(element.kind) && !KIND_ARMOR.equals(element.kind)) {
                result.add(element);
            }
        }

        return result;
    }

    /** 保证两个内置元素存在、且排在列表最前；顺便给老元素补上 kind/visible 字段。 */
    public static void ensureBuiltInElements() {
        TextElement name = null;
        TextElement vitals = null;
        TextElement food = null;
        TextElement armor = null;
        for (TextElement element : textElements) {
            if (element.kind == null || element.kind.isEmpty()) {
                // 老配置没有 kind：按内容认领；含 %food% 的按「饥饿」这条固定内置认领
                element.kind = "%player_name%".equals(element.content) ? KIND_NAME
                        : (element.content != null && element.content.contains("%health%")) ? KIND_VITALS
                        : (element.content != null && element.content.contains("%food%")) ? KIND_FOOD
                        : (element.content != null && element.content.contains("%armor%")) ? KIND_ARMOR : KIND_CUSTOM;
            }

            if (element.visible == null) {
                element.visible = Boolean.TRUE;
            }

            if (KIND_NAME.equals(element.kind) && name == null) {
                name = element;
            } else if (KIND_VITALS.equals(element.kind) && vitals == null) {
                vitals = element;
            } else if (KIND_FOOD.equals(element.kind) && food == null) {
                food = element;
            } else if (KIND_ARMOR.equals(element.kind) && armor == null) {
                armor = element;
            }
        }

        if (name == null) {
            name = createBuiltIn(KIND_NAME);
        }

        if (vitals == null) {
            vitals = createBuiltIn(KIND_VITALS);
        }

        if (food == null) {
            food = createBuiltIn(KIND_FOOD);
        }

        if (armor == null) {
            // 老配置没有护甲：补一条，放在饥饿下面一行（沿用饥饿的样式）
            armor = createBuiltIn(KIND_ARMOR);
            if (food != null) {
                armor.y = food.y + 10;
            }
        }

        // 顺序固定：玩家名称 / 生命 / 饥饿 / 其余自定义元素
        List<TextElement> ordered = new ArrayList<>();
        ordered.add(name);
        ordered.add(vitals);
        if (food != vitals) {
            ordered.add(food);
        }

        if (armor != food && armor != vitals) {
            ordered.add(armor);
        }
        for (TextElement element : textElements) {
            if (element != name && element != vitals && element != food && element != armor) {
                ordered.add(element);
            }
        }

        textElements.clear();
        textElements.addAll(ordered);
    }

    /** 各设置分区（用于「只恢复本页默认」）。 */
    public enum Section { CHAT, PORTRAIT, PREVIEW, NAMES, ELEMENTS, FILTERS }

    /** 只恢复某一个分区的默认值。 */
    public static void resetSection(Section section) {
        switch (section) {
            case CHAT -> {
                hudEnabled.reset(); centerXOffset.reset(); topYOffset.reset(); maxLines.reset(); boxWidth.reset();
                backgroundAlpha.reset(); lineHoldSeconds.reset(); holdMinSeconds.reset(); holdMaxSeconds.reset();
                chatBackgroundColor.reset(); chatNameColor.reset(); chatRightGap.reset(); chatLeftMargin.reset(); chatNameMaxWidth.reset(); chatSymbolPadding.reset(); chatSymbolColor.reset(); chatPaddingX.reset(); chatPaddingY.reset();
                chatBorderEnabled.reset(); chatBorderColor.reset(); chatBorderThickness.reset();
                filter.reset(); removeFromVanillaChat.reset(); vanillaPlayerChat.reset();
                vanillaOtherMessages.reset(); includeActions.reset(); whitelist.reset();
                chatVisibleLines.reset(); chatScreenLines.reset();
                blipEnabled.reset(); goneSoundEnabled.reset(); soundVolume.reset();
                newSoundPreset.reset(); newSoundVolume.reset(); newSoundPitch.reset(); newSoundSpeed.reset();
                newSoundFadeIn.reset(); newSoundFadeOut.reset();
                goneSoundPreset.reset(); goneSoundVolume.reset(); goneSoundPitch.reset(); goneSoundSpeed.reset();
                goneSoundFadeIn.reset(); goneSoundFadeOut.reset();
            }
            case PORTRAIT -> {
                portraitUseImage.reset(); portraitImageName.reset(); portraitImageScale.reset();
                deathOverlayEnabled.reset(); deathPreset.reset(); deathImageMaxSize.reset();
                deathImageScale.reset(); deathImageOffsetX.reset(); deathImageOffsetY.reset(); portraitImageOffsetX.reset(); portraitImageOffsetY.reset();
                portraitEnabled.reset(); portraitWidth.reset(); portraitHeight.reset(); portraitXOffset.reset();
                portraitYOffset.reset(); portraitScale.reset(); portraitAnchor.reset(); previewDragTarget.reset(); portraitXRatio.reset();
                portraitYRatio.reset(); portraitFollowChat.reset(); portraitAlwaysVisible.reset(); portraitTint.reset();
                portraitOpacity.reset(); portraitBrightness.reset(); portraitPanelColor.reset(); portraitPanelAlpha.reset();
                portraitBorderColor.reset(); portraitBorderThickness.reset(); portraitBackdropEnabled.reset();
                portraitBackdropColor.reset(); portraitBackdropAlpha.reset(); portraitBackdropPadding.reset();
                portraitBustScale.reset(); skinOffsetX.reset(); skinOffsetY.reset(); portraitPhotoMode.reset(); portraitPhotoHeight.reset();
                portraitPhotoDivider.reset(); portraitOuterLayer.reset(); capeEnabled.reset();
                customPortraitEnabled.reset(); customPortraits.reset();
            }
            case PREVIEW -> {
                portraitWidth.reset(); portraitHeight.reset(); portraitScale.reset(); portraitAnchor.reset();
                previewDragTarget.reset(); portraitXRatio.reset(); portraitYRatio.reset(); portraitXOffset.reset(); portraitYOffset.reset();
                centerXOffset.reset(); topYOffset.reset(); boxWidth.reset();
            }
            case NAMES -> nameColors.clear();
            case ELEMENTS -> resetElements();
            case FILTERS -> {

                filterEnabled.reset(); filterName.reset(); filterAlpha.reset(); filterBlend.reset();
                filterTint.reset(); filterBrightness.reset(); filterContrast.reset();
                filterSaturation.reset(); filterHue.reset(); filterEffectColors.reset();
                gradeEnabled.reset(); gradeBrightness.reset(); gradeContrast.reset(); gradeSaturation.reset();
                gradeHue.reset(); duotoneEnabled.reset(); duotoneDark.reset(); duotoneLight.reset();
                duotoneAmount.reset(); filterTemplates.clear();
                PortraitGrade.invalidate();
            }
        }

        save();
    }

    public static String displayName(String playerName) { return mappedValue(displayNames.get(), playerName, playerName); }
    public static String customInfo(String playerName) { return mappedValue(customInfo.get(), playerName, ""); }
    public static int nameColor(String uuid, String name) {
        for (NameColor item : nameColors) {
            if ((uuid != null && !uuid.isBlank() && uuid.equalsIgnoreCase(item.uuid)) || (name != null && name.equalsIgnoreCase(item.name))) return item.color;
        }
        return -1;
    }

    /**
     * 聊天里玩家名的样式：名单里配过色就用那个颜色，否则用「玩家名初始颜色」（默认蓝色）。
     */
    /** 某个内置特效的颜色（没配置就用 fallback）。 */
    public static int effectColorFor(String filterId, int fallback) {
        if (filterId == null) {
            return fallback;
        }

        for (String entry : filterEffectColors.get()) {
            int eq = entry.indexOf('=');
            if (eq > 0 && entry.substring(0, eq).equals(filterId)) {
                try {
                    return (int) Long.parseLong(entry.substring(eq + 1));
                } catch (NumberFormatException error) {
                    return fallback;
                }
            }
        }

        return fallback;
    }

    /** 设置某个内置特效的颜色。 */
    public static void setEffectColor(String filterId, int argb) {
        java.util.List<String> next = new java.util.ArrayList<>();
        for (String entry : filterEffectColors.get()) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !entry.substring(0, eq).equals(filterId)) {
                next.add(entry);
            }
        }

        next.add(filterId + "=" + (argb & 0xFFFFFFFFL));
        filterEffectColors.set(next);
    }

    /** 「&lt;&gt;」与内容之间的间隔（按 4px 粒度折算成空格，左右半边同时生效）。 */
    public static String chatSymbolPad() {
        int padding = Math.max(0, Math.min(16, chatSymbolPadding.get()));
        return " ".repeat(Math.round(padding / 4.0F));
    }

    /** 「&lt;&gt;」符号的样式（颜色取 {@link #chatSymbolColor}）。 */
    public static net.minecraft.network.chat.Style chatSymbolStyle() {
        return net.minecraft.network.chat.Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(chatSymbolColor.get() & 0xFFFFFF));
    }

    /** 聊天正文的样式（固定白色）。 */
    public static net.minecraft.network.chat.Style chatBodyStyle() {
        return net.minecraft.network.chat.Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(0xFFFFFF));
    }

    /**
     * 拼一条聊天行：<b>玩家名称</b> + <b>&lt;&gt; 符号</b> + <b>内容</b> 三段。
     *
     * <p>符号与内容之间的间隔由 {@link #chatSymbolPadding} 决定（按 4px 粒度折算成空格，
     * 左右半边同时生效）；符号颜色用 {@link #chatSymbolColor}；内容固定白色。
     *
     * <p>顶部 HUD 的聊天框不走这里：它由 {@link ChatLayout} 自己按行排版（名字/符号/正文同行规则），
     * 但符号与样式取的是同一批方法（{@link #chatSymbolPad()} / {@link #chatSymbolStyle()} /
     * {@link #chatBodyStyle()}），保证「HUD 里看到的」和「按 T 注入的历史行」长得一样。
     */
    public static net.minecraft.network.chat.Component chatLine(String name, String text, net.minecraft.network.chat.Style nameStyle) {
        String pad = chatSymbolPad();
        net.minecraft.network.chat.Style symbolStyle = chatSymbolStyle();

        return net.minecraft.network.chat.Component.literal(name == null ? "" : name)
                .withStyle(nameStyle == null ? net.minecraft.network.chat.Style.EMPTY : nameStyle)
                .append(net.minecraft.network.chat.Component.literal(" <" + pad).withStyle(symbolStyle))
                .append(net.minecraft.network.chat.Component.literal(text == null ? "" : text).withStyle(chatBodyStyle()))
                .append(net.minecraft.network.chat.Component.literal(pad + ">").withStyle(symbolStyle));
    }

    public static Style nameStyleFor(String uuid, String name) {
        int color = nameColor(uuid, name);
        if (color == -1) {
            color = chatNameColor.get();
        }

        return Style.EMPTY.withColor(TextColor.fromRgb(color & 0xFFFFFF));
    }

    /** 保存当前状态到角色卡文件（每张卡一个文件；改动即时写盘）。 */
    private static String appliedCard;

    /**
     * 渲染前调用：按「当前显示的那位玩家」自动切换到他的角色卡（可用开关关掉）。
     * 只在目标卡变化时真正应用，避免每帧重复读取文件。
     */
    public static void autoApplyCardFor(String playerName) {
        if (!autoCardByPlayer.get()) {
            return;
        }

        String target = cardForPlayer(displayName(playerName));
        if (target.equals(appliedCard)) {
            return;
        }

        // 注意：这里【不】改 activeCard —— 渲染期间套用对方的卡，画完会由 restoreEditingCard() 还原，
        // 所以在配置界面里编辑的永远是「你自己选中的那张卡」，不会被别人的卡顶掉。
        appliedCard = target;
        applyCard(target);
    }

    /** 渲染结束后恢复「正在编辑的卡」，保证编辑写入正确的卡文件。 */
    public static void restoreEditingCard() {
        if (appliedCard != null && !appliedCard.equals(activeCard.get())) {
            appliedCard = activeCard.get();
            applyCard(appliedCard);
        }
    }

    /** 手动选中一张卡（界面里点击时用）。 */
    public static void selectCard(String cardName) {
        appliedCard = cardName;
        activeCard.set(cardName);
        applyCard(cardName);
        save();
    }

    public static void saveCard() {
        PlayerCards.write(activeCard.get(), toJson());
    }

    /**
     * 应用一张角色卡：把卡里的快照读进配置字段，然后<b>把全局项再覆盖回去</b>，
     * 保证聊天框、音效、显示开关、过滤名单、素材目录这些全局设置不会被卡改掉。
     */
    public static void applyCard(String cardName) {
        JsonObject data = PlayerCards.read(cardName);
        if (data == null) {
            return;
        }

        // 全局项先存下来
        boolean hud = hudEnabled.get();
        int centerX = centerXOffset.get();
        int topY = topYOffset.get();
        int lines = maxLines.get();
        int width = boxWidth.get();
        double alpha = backgroundAlpha.get();
        double hold = lineHoldSeconds.get();
        double holdMin = holdMinSeconds.get();
        double holdMax = holdMaxSeconds.get();
        int bg = chatBackgroundColor.get();
        int nameColor = chatNameColor.get();
        int rightGap = chatRightGap.get();
        int leftMargin = chatLeftMargin.get();
        int nameMaxWidth = chatNameMaxWidth.get();
        int symbolPad = chatSymbolPadding.get();
        int symbolColor = chatSymbolColor.get();
        int padX = chatPaddingX.get();
        int padY = chatPaddingY.get();
        boolean border = chatBorderEnabled.get();
        int borderColor = chatBorderColor.get();
        int borderThickness = chatBorderThickness.get();
        ChatFilter mode = filter.get();
        boolean vanillaPlayer = vanillaPlayerChat.get();
        boolean vanillaOther = vanillaOtherMessages.get();
        boolean savedIncludeActions = includeActions.get();
        java.util.List<String> whitelistCopy = new java.util.ArrayList<>(whitelist.get());
        boolean blip = blipEnabled.get();
        double volume = soundVolume.get();
        boolean goneSound = goneSoundEnabled.get();
        String newPreset = newSoundPreset.get();
        int newVolume = newSoundVolume.get();
        int newPitch = newSoundPitch.get();
        int newSpeed = newSoundSpeed.get();
        int newFadeIn = newSoundFadeIn.get();
        int newFadeOut = newSoundFadeOut.get();
        String gonePreset = goneSoundPreset.get();
        int goneVolume = goneSoundVolume.get();
        int gonePitch = goneSoundPitch.get();
        int goneSpeed = goneSoundSpeed.get();
        int goneFadeIn = goneSoundFadeIn.get();
        int goneFadeOut = goneSoundFadeOut.get();
        java.util.List<String> displayNamesCopy = new java.util.ArrayList<>(displayNames.get());
        java.util.List<String> customInfoCopy = new java.util.ArrayList<>(customInfo.get());
        java.util.List<String> customPortraitsCopy = new java.util.ArrayList<>(customPortraits.get());
        java.util.List<FilterTemplate> templatesCopy = new java.util.ArrayList<>(filterTemplates);
        String card = activeCard.get();
        boolean auto = autoCardByPlayer.get();
        double lineHold = 0.0D;

        // 卡内容
        apply(data);

        // 全局项恢复
        hudEnabled.set(hud);
        centerXOffset.set(centerX);
        topYOffset.set(topY);
        maxLines.set(lines);
        boxWidth.set(width);
        backgroundAlpha.set(alpha);
        lineHoldSeconds.set(hold);
        holdMinSeconds.set(holdMin);
        holdMaxSeconds.set(holdMax);
        chatBackgroundColor.set(bg);
        chatNameColor.set(nameColor);
        chatRightGap.set(rightGap);
        chatLeftMargin.set(leftMargin);
        chatNameMaxWidth.set(nameMaxWidth);
        chatSymbolPadding.set(symbolPad);
        chatSymbolColor.set(symbolColor);
        chatPaddingX.set(padX);
        chatPaddingY.set(padY);
        chatBorderEnabled.set(border);
        chatBorderColor.set(borderColor);
        chatBorderThickness.set(borderThickness);
        filter.set(mode);
        vanillaPlayerChat.set(vanillaPlayer);
        vanillaOtherMessages.set(vanillaOther);
        includeActions.set(savedIncludeActions);
        whitelist.set(whitelistCopy);
        blipEnabled.set(blip);
        soundVolume.set(volume);
        goneSoundEnabled.set(goneSound);
        newSoundPreset.set(newPreset);
        newSoundVolume.set(newVolume);
        newSoundPitch.set(newPitch);
        newSoundSpeed.set(newSpeed);
        newSoundFadeIn.set(newFadeIn);
        newSoundFadeOut.set(newFadeOut);
        goneSoundPreset.set(gonePreset);
        goneSoundVolume.set(goneVolume);
        goneSoundPitch.set(gonePitch);
        goneSoundSpeed.set(goneSpeed);
        goneSoundFadeIn.set(goneFadeIn);
        goneSoundFadeOut.set(goneFadeOut);
        displayNames.set(displayNamesCopy);
        customInfo.set(customInfoCopy);
        customPortraits.set(customPortraitsCopy);
        filterTemplates.clear();
        filterTemplates.addAll(templatesCopy);
        activeCard.set(card);
        autoCardByPlayer.set(auto);
        ensureBuiltInElements();
        PortraitGrade.invalidate();
    }

    /** 某玩家应该用哪张卡：有专属卡就用它，否则用默认卡。 */
    public static String cardForPlayer(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return PlayerCards.DEFAULT_CARD;
        }

        // 每帧都会调用：用内存里的卡名集合判定，不做磁盘 stat
        return PlayerCards.existsCached(playerName) ? playerName : PlayerCards.DEFAULT_CARD;
    }

    /** 正在使用的卡名（一级菜单顶部显示）。 */
    public static String currentCard() {
        return activeCard.get();
    }

    /** 启动/进世界时：确保默认卡存在，并把当前卡应用回来。 */
    public static void bootstrapCards() {
        if (!PlayerCards.exists(PlayerCards.DEFAULT_CARD)) {
            PlayerCards.write(PlayerCards.DEFAULT_CARD, toJson());
        }

        String current = activeCard.get();
        if (!PlayerCards.exists(current)) {
            activeCard.set(PlayerCards.DEFAULT_CARD);
            current = PlayerCards.DEFAULT_CARD;
        }

        applyCard(current);
    }

    public static void save() {
        // 每张卡的改动都即时写盘（卡文件与全局文件同时保持最新）
        try {
            PlayerCards.write(activeCard.get(), toJson());
        } catch (RuntimeException | LinkageError ignored) {
            // 卡写不进去也不影响主配置
        }

        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, GSON.toJson(toJson()), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    public static Path export() {
        try {
            Files.createDirectories(EXPORT_DIR);
            Path target = EXPORT_DIR.resolve("client-export-"
                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now())
                    + ".json");
            Files.writeString(target, GSON.toJson(toJson()), StandardCharsets.UTF_8);
            return target;
        } catch (IOException ignored) { return null; }
    }

    public static String exportString() { return GSON.toJson(toJson()); }

    public static boolean importString(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return false;
            Files.createDirectories(CONFIG_DIR);
            if (Files.exists(CONFIG_FILE)) Files.copy(CONFIG_FILE, CONFIG_FILE.resolveSibling("client.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            apply(parsed.getAsJsonObject());
            save();
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static boolean importFile(Path path) {
        try { return importString(Files.readString(path, StandardCharsets.UTF_8)); } catch (IOException ignored) { return false; }
    }

    /**
     * 默认文字元素：证件卡式排版 —— 大字名字 + 一行小字血量 + 一行青色小字维度，
     * 都靠文字区左边、自下往上排，和参考卡片的「W. COSTER / NOISE / -LT FCU NAVY」一个意思。
     */
    /**
     * 恢复默认：回到「玩家名称 / 生命 / 饥饿」三条 —— 生命在上、饥饿在下（分两行），
     * 并清空其它自定义元素。
     */
    public static void resetElements() {
        textElements.clear();
        TextElement vitals = createBuiltIn(KIND_VITALS);
        textElements.add(createBuiltIn(KIND_NAME));
        textElements.add(vitals);
        textElements.add(createBuiltIn(KIND_FOOD));
        textElements.add(createBuiltIn(KIND_ARMOR));
        save();
    }

    public static String resolve(String input, String playerName, String uuid) {
        if (input == null) return "";
        Minecraft mc = Minecraft.getInstance();
        String result = input.replace("%player_name%", playerName == null ? "" : playerName).replace("%player_uuid%", uuid == null ? "" : uuid);
        // 数值一律按【被显示的那位玩家】取：自己用本地玩家，别人用世界里同步到的实体；
        // 取不到（离线/未加载/未同步）就显示 Unknown，绝不拿自己的数据冒充别人。
        net.minecraft.world.entity.LivingEntity target = living(mc, playerName, uuid);
        boolean self = target != null && mc.player != null && target.getUUID().equals(mc.player.getUUID());
        // 服务端模块（可选）每秒下发一份数值快照：先读本地缓存，没有就仍然 Unknown
        java.util.UUID targetId = null;
        if (uuid != null && !uuid.isBlank()) {
            try {
                targetId = java.util.UUID.fromString(uuid.trim());
            } catch (IllegalArgumentException ignored) {
                targetId = null;
            }
        }

        java.util.OptionalDouble remoteHealth = RemoteStats.health(targetId);
        java.util.OptionalDouble remoteMaxHealth = RemoteStats.maxHealth(targetId);
        java.util.OptionalInt remoteFood = RemoteStats.food(targetId);
        java.util.OptionalInt remoteArmor = RemoteStats.armor(targetId);
        String health = target != null ? String.valueOf(Math.round(target.getHealth()))
                : remoteHealth.isPresent() ? String.valueOf(Math.round(remoteHealth.getAsDouble())) : UNKNOWN;
        String maxHealth = target != null ? String.valueOf(Math.round(target.getMaxHealth()))
                : remoteMaxHealth.isPresent() ? String.valueOf(Math.round(remoteMaxHealth.getAsDouble())) : UNKNOWN;
        String food = (self && mc.player != null) ? String.valueOf(mc.player.getFoodData().getFoodLevel())
                : remoteFood.isPresent() ? String.valueOf(remoteFood.getAsInt()) : UNKNOWN;
        // 饥饿上限固定 20：自己、或服务端给了数据时都能显示
        String maxFood = (self && mc.player != null) ? "20"
                : remoteFood.isPresent() ? "20" : UNKNOWN;
        String armor = (self && mc.player != null) ? String.valueOf(mc.player.getArmorValue())
                : target != null ? String.valueOf(target.getArmorValue())
                : remoteArmor.isPresent() ? String.valueOf(remoteArmor.getAsInt()) : UNKNOWN;
        String ping = pingOf(mc, uuid);
        String dimension = mc.level == null ? "" : mc.level.dimension().location().toString();
        String worldTime = mc.level == null ? "" : (mc.level.getDayTime() % 24000L < 12000L ? "白天" : "夜晚");
        result = result
                .replace("%health%", health)
                .replace("%max_health%", maxHealth)
                .replace("%max_food%", maxFood)
                .replace("%food%", food)
                .replace("%armor%", armor)
                .replace("%ping%", ping)
                .replace("%dimension%", dimension)
                .replace("%world_time%", worldTime);
        return result;
    }

    /** 按 UUID / 名字找一位【活着的实体】（自己或世界里的玩家）；找不到返回 null。 */
    private static net.minecraft.world.entity.LivingEntity living(Minecraft mc, String playerName, String uuid) {
        if (mc == null || mc.level == null) {
            return null;
        }

        if (uuid != null && mc.player != null && uuid.equals(mc.player.getUUID().toString())) {
            return mc.player;
        }

        for (net.minecraft.world.entity.player.Player player : mc.level.players()) {
            if (uuid != null && uuid.equals(player.getUUID().toString())) {
                return player;
            }

            if (playerName != null && !playerName.isEmpty() && playerName.equals(player.getGameProfile().getName())) {
                return player;
            }
        }

        return null;
    }

    /** 该玩家的延迟（客户端同步值）；自己或拿不到时返回 Unknown。 */
    private static String pingOf(Minecraft mc, String uuid) {
        try {
            if (mc == null || mc.getConnection() == null || uuid == null) {
                return UNKNOWN;
            }

            net.minecraft.client.multiplayer.PlayerInfo info =
                    mc.getConnection().getPlayerInfo(java.util.UUID.fromString(uuid));
            return info == null ? UNKNOWN : (info.getLatency() + " ms");
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    private static JsonObject toJson() {
        JsonObject root = new JsonObject(); root.addProperty("version", 18);
        JsonObject chat = new JsonObject(); add(chat, "enabled", hudEnabled.get());
        chat.addProperty("elementBaseWidth", elementBaseWidth); add(chat, "centerXOffset", centerXOffset.get()); add(chat, "topYOffset", topYOffset.get()); add(chat, "maxLines", maxLines.get()); add(chat, "boxWidth", boxWidth.get()); add(chat, "backgroundAlpha", backgroundAlpha.get()); add(chat, "lineHoldSeconds", lineHoldSeconds.get()); add(chat, "holdMinSeconds", holdMinSeconds.get()); add(chat, "holdMaxSeconds", holdMaxSeconds.get()); add(chat, "backgroundColor", chatBackgroundColor.get()); add(chat, "nameColor", chatNameColor.get()); add(chat, "rightGap", chatRightGap.get()); add(chat, "leftMargin", chatLeftMargin.get()); add(chat, "nameMaxWidth", chatNameMaxWidth.get()); add(chat, "paddingX", chatPaddingX.get()); add(chat, "paddingY", chatPaddingY.get()); add(chat, "symbolColor", chatSymbolColor.get()); add(chat, "symbolPadding", chatSymbolPadding.get()); add(chat, "borderEnabled", chatBorderEnabled.get()); add(chat, "borderColor", chatBorderColor.get()); add(chat, "borderThickness", chatBorderThickness.get()); root.add("chat", chat);
        JsonObject portrait = new JsonObject(); add(portrait, "enabled", portraitEnabled.get()); add(portrait, "width", portraitWidth.get()); add(portrait, "height", portraitHeight.get()); add(portrait, "xOffset", portraitXOffset.get()); add(portrait, "yOffset", portraitYOffset.get()); add(portrait, "scale", portraitScale.get()); add(portrait, "anchor", portraitAnchor.get().name()); add(portrait, "xRatio", portraitXRatio.get()); add(portrait, "yRatio", portraitYRatio.get()); add(portrait, "followChat", portraitFollowChat.get()); add(portrait, "alwaysVisible", portraitAlwaysVisible.get()); add(portrait, "tint", portraitTint.get()); add(portrait, "opacity", portraitOpacity.get()); add(portrait, "brightness", portraitBrightness.get()); add(portrait, "panelColor", portraitPanelColor.get()); add(portrait, "panelAlpha", portraitPanelAlpha.get()); add(portrait, "borderColor", portraitBorderColor.get()); add(portrait, "borderThickness", portraitBorderThickness.get()); add(portrait, "backdropEnabled", portraitBackdropEnabled.get()); add(portrait, "backdropColor", portraitBackdropColor.get()); add(portrait, "backdropAlpha", portraitBackdropAlpha.get()); add(portrait, "backdropPadding", portraitBackdropPadding.get()); add(portrait, "bustScale", portraitBustScale.get()); add(portrait, "photoMode", portraitPhotoMode.get()); add(portrait, "photoHeight", portraitPhotoHeight.get()); add(portrait, "photoDivider", portraitPhotoDivider.get()); add(portrait, "outerLayer", portraitOuterLayer.get()); add(portrait, "filterEnabled", filterEnabled.get()); add(portrait, "filterName", filterName.get()); add(portrait, "filterAlpha", filterAlpha.get()); add(portrait, "filterBlend", filterBlend.get()); add(portrait, "gradeEnabled", gradeEnabled.get()); add(portrait, "gradeBrightness", gradeBrightness.get()); add(portrait, "gradeContrast", gradeContrast.get()); add(portrait, "gradeSaturation", gradeSaturation.get()); add(portrait, "gradeHue", gradeHue.get()); add(portrait, "duotoneEnabled", duotoneEnabled.get()); add(portrait, "duotoneDark", duotoneDark.get()); add(portrait, "duotoneLight", duotoneLight.get()); add(portrait, "duotoneAmount", duotoneAmount.get()); add(portrait, "cape", capeEnabled.get()); root.add("portrait", portrait);
        root.add("filter", array(whitelist.get())); root.add("customPortraits", array(customPortraits.get())); root.add("displayNames", array(displayNames.get())); root.add("customInfo", array(customInfo.get())); root.addProperty("removeFromVanillaChat", removeFromVanillaChat.get()); root.addProperty("vanillaPlayerChat", vanillaPlayerChat.get()); root.addProperty("vanillaOtherMessages", vanillaOtherMessages.get()); root.addProperty("filterMode", filter.get().name()); root.addProperty("blipSound", blipEnabled.get()); root.addProperty("soundVolume", soundVolume.get()); root.addProperty("goneSound", goneSoundEnabled.get()); root.addProperty("newSoundPreset", newSoundPreset.get()); root.addProperty("newSoundVolume", newSoundVolume.get()); root.addProperty("newSoundPitch", newSoundPitch.get()); root.addProperty("newSoundFadeIn", newSoundFadeIn.get()); root.addProperty("newSoundFadeOut", newSoundFadeOut.get()); root.addProperty("newSoundSpeed", newSoundSpeed.get()); root.addProperty("goneSoundPreset", goneSoundPreset.get()); root.addProperty("goneSoundVolume", goneSoundVolume.get()); root.addProperty("goneSoundPitch", goneSoundPitch.get()); root.addProperty("goneSoundFadeIn", goneSoundFadeIn.get()); root.addProperty("goneSoundFadeOut", goneSoundFadeOut.get()); root.addProperty("goneSoundSpeed", goneSoundSpeed.get()); root.addProperty("portraitUseImage", portraitUseImage.get()); root.addProperty("portraitImageName", portraitImageName.get()); root.addProperty("skinOffsetX", skinOffsetX.get()); root.addProperty("skinOffsetY", skinOffsetY.get()); root.addProperty("deathOverlay", deathOverlayEnabled.get()); root.addProperty("chatVisibleLines", chatVisibleLines.get()); root.addProperty("chatScreenLines", chatScreenLines.get()); root.addProperty("previewDragTarget", previewDragTarget.get()); root.addProperty("debugTiming", debugTiming.get()); root.addProperty("activeCard", activeCard.get()); root.addProperty("autoCardByPlayer", autoCardByPlayer.get()); root.addProperty("deathPreset", deathPreset.get()); root.addProperty("deathImageMaxSize", deathImageMaxSize.get()); root.addProperty("deathImageScale", deathImageScale.get()); root.addProperty("deathImageOffsetX", deathImageOffsetX.get()); root.addProperty("deathImageOffsetY", deathImageOffsetY.get()); root.addProperty("portraitImageScale", portraitImageScale.get()); root.addProperty("portraitImageOffsetX", portraitImageOffsetX.get()); root.addProperty("portraitImageOffsetY", portraitImageOffsetY.get());
        JsonArray names = new JsonArray(); nameColors.forEach(n -> names.add(GSON.toJsonTree(n))); root.add("nameColors", names); JsonArray templates = new JsonArray(); filterTemplates.forEach(t -> templates.add(GSON.toJsonTree(t))); root.add("filterTemplates", templates);
        JsonArray elements = new JsonArray(); textElements.forEach(e -> elements.add(GSON.toJsonTree(e))); root.add("textElements", elements);
        return root;
    }

    private static void apply(JsonObject root) {
        JsonObject chat = object(root, "chat"); JsonObject portrait = object(root, "portrait");
        set(chat, "enabled", hudEnabled, JsonElement::getAsBoolean);
        if (chat.has("elementBaseWidth")) {
            try {
                elementBaseWidth = chat.get("elementBaseWidth").getAsInt();
            } catch (RuntimeException ignored) {
                // 老配置没有或非法：保持 0，渲染时退回当前卡片宽度
            }
        } set(chat, "centerXOffset", centerXOffset, JsonElement::getAsInt); set(chat, "topYOffset", topYOffset, JsonElement::getAsInt); set(chat, "maxLines", maxLines, JsonElement::getAsInt); set(chat, "boxWidth", boxWidth, JsonElement::getAsInt); set(chat, "backgroundAlpha", backgroundAlpha, JsonElement::getAsDouble); set(chat, "lineHoldSeconds", lineHoldSeconds, JsonElement::getAsDouble); set(chat, "holdMinSeconds", holdMinSeconds, JsonElement::getAsDouble); set(chat, "holdMaxSeconds", holdMaxSeconds, JsonElement::getAsDouble); set(chat, "backgroundColor", chatBackgroundColor, JsonElement::getAsInt); set(chat, "nameColor", chatNameColor, JsonElement::getAsInt); set(chat, "rightGap", chatRightGap, JsonElement::getAsInt); set(chat, "leftMargin", chatLeftMargin, JsonElement::getAsInt); set(chat, "nameMaxWidth", chatNameMaxWidth, JsonElement::getAsInt); set(chat, "paddingX", chatPaddingX, JsonElement::getAsInt); set(chat, "paddingY", chatPaddingY, JsonElement::getAsInt); set(chat, "symbolColor", chatSymbolColor, JsonElement::getAsInt); set(chat, "symbolPadding", chatSymbolPadding, JsonElement::getAsInt); set(chat, "borderEnabled", chatBorderEnabled, JsonElement::getAsBoolean); set(chat, "borderColor", chatBorderColor, JsonElement::getAsInt); set(chat, "borderThickness", chatBorderThickness, JsonElement::getAsInt);
        set(portrait, "enabled", portraitEnabled, JsonElement::getAsBoolean); set(portrait, "width", portraitWidth, JsonElement::getAsInt); set(portrait, "height", portraitHeight, JsonElement::getAsInt); set(portrait, "xOffset", portraitXOffset, JsonElement::getAsInt); set(portrait, "yOffset", portraitYOffset, JsonElement::getAsInt); set(portrait, "scale", portraitScale, JsonElement::getAsDouble); setEnum(portrait, "anchor", portraitAnchor, Anchor.class); set(portrait, "xRatio", portraitXRatio, JsonElement::getAsDouble); set(portrait, "yRatio", portraitYRatio, JsonElement::getAsDouble); set(portrait, "followChat", portraitFollowChat, JsonElement::getAsBoolean); set(portrait, "alwaysVisible", portraitAlwaysVisible, JsonElement::getAsBoolean); set(portrait, "tint", portraitTint, JsonElement::getAsInt); set(portrait, "opacity", portraitOpacity, JsonElement::getAsInt); set(portrait, "brightness", portraitBrightness, JsonElement::getAsInt); set(portrait, "panelColor", portraitPanelColor, JsonElement::getAsInt); set(portrait, "panelAlpha", portraitPanelAlpha, JsonElement::getAsInt); set(portrait, "borderColor", portraitBorderColor, JsonElement::getAsInt); set(portrait, "borderThickness", portraitBorderThickness, JsonElement::getAsInt); set(portrait, "backdropEnabled", portraitBackdropEnabled, JsonElement::getAsBoolean); set(portrait, "backdropColor", portraitBackdropColor, JsonElement::getAsInt); set(portrait, "backdropAlpha", portraitBackdropAlpha, JsonElement::getAsInt); set(portrait, "backdropPadding", portraitBackdropPadding, JsonElement::getAsInt); set(portrait, "bustScale", portraitBustScale, JsonElement::getAsInt); set(portrait, "photoMode", portraitPhotoMode, JsonElement::getAsInt); set(portrait, "photoHeight", portraitPhotoHeight, JsonElement::getAsInt); set(portrait, "photoDivider", portraitPhotoDivider, JsonElement::getAsBoolean); set(portrait, "outerLayer", portraitOuterLayer, JsonElement::getAsBoolean); set(portrait, "filterEnabled", filterEnabled, JsonElement::getAsBoolean); set(portrait, "filterName", filterName, JsonElement::getAsString); set(portrait, "filterAlpha", filterAlpha, JsonElement::getAsInt); set(portrait, "filterBlend", filterBlend, JsonElement::getAsInt); set(portrait, "gradeEnabled", gradeEnabled, JsonElement::getAsBoolean); set(portrait, "gradeBrightness", gradeBrightness, JsonElement::getAsInt); set(portrait, "gradeContrast", gradeContrast, JsonElement::getAsInt); set(portrait, "gradeSaturation", gradeSaturation, JsonElement::getAsInt); set(portrait, "gradeHue", gradeHue, JsonElement::getAsInt); set(portrait, "duotoneEnabled", duotoneEnabled, JsonElement::getAsBoolean); set(portrait, "duotoneDark", duotoneDark, JsonElement::getAsInt); set(portrait, "duotoneLight", duotoneLight, JsonElement::getAsInt); set(portrait, "duotoneAmount", duotoneAmount, JsonElement::getAsInt); set(portrait, "cape", capeEnabled, JsonElement::getAsBoolean);
        set(root, "removeFromVanillaChat", removeFromVanillaChat, JsonElement::getAsBoolean); set(root, "vanillaPlayerChat", vanillaPlayerChat, JsonElement::getAsBoolean); set(root, "vanillaOtherMessages", vanillaOtherMessages, JsonElement::getAsBoolean); setEnum(root, "filterMode", filter, ChatFilter.class); set(root, "blipSound", blipEnabled, JsonElement::getAsBoolean); set(root, "soundVolume", soundVolume, JsonElement::getAsDouble); set(root, "goneSound", goneSoundEnabled, JsonElement::getAsBoolean); set(root, "newSoundPreset", newSoundPreset, JsonElement::getAsString); set(root, "newSoundVolume", newSoundVolume, JsonElement::getAsInt); set(root, "newSoundPitch", newSoundPitch, JsonElement::getAsInt); set(root, "newSoundFadeIn", newSoundFadeIn, JsonElement::getAsInt); set(root, "newSoundFadeOut", newSoundFadeOut, JsonElement::getAsInt); set(root, "newSoundSpeed", newSoundSpeed, JsonElement::getAsInt); set(root, "goneSoundPreset", goneSoundPreset, JsonElement::getAsString); set(root, "goneSoundVolume", goneSoundVolume, JsonElement::getAsInt); set(root, "goneSoundPitch", goneSoundPitch, JsonElement::getAsInt); set(root, "goneSoundFadeIn", goneSoundFadeIn, JsonElement::getAsInt); set(root, "goneSoundFadeOut", goneSoundFadeOut, JsonElement::getAsInt); set(root, "goneSoundSpeed", goneSoundSpeed, JsonElement::getAsInt); set(root, "portraitUseImage", portraitUseImage, JsonElement::getAsBoolean); set(root, "portraitImageName", portraitImageName, JsonElement::getAsString); set(root, "skinOffsetX", skinOffsetX, JsonElement::getAsInt); set(root, "skinOffsetY", skinOffsetY, JsonElement::getAsInt); set(root, "deathOverlay", deathOverlayEnabled, JsonElement::getAsBoolean); set(root, "chatVisibleLines", chatVisibleLines, JsonElement::getAsInt); set(root, "chatScreenLines", chatScreenLines, JsonElement::getAsInt); set(root, "previewDragTarget", previewDragTarget, JsonElement::getAsInt); set(root, "debugTiming", debugTiming, JsonElement::getAsBoolean); set(root, "activeCard", activeCard, JsonElement::getAsString); set(root, "autoCardByPlayer", autoCardByPlayer, JsonElement::getAsBoolean); set(root, "deathPreset", deathPreset, JsonElement::getAsString); set(root, "deathImageMaxSize", deathImageMaxSize, JsonElement::getAsInt); set(root, "deathImageScale", deathImageScale, JsonElement::getAsInt); set(root, "deathImageOffsetX", deathImageOffsetX, JsonElement::getAsInt); set(root, "deathImageOffsetY", deathImageOffsetY, JsonElement::getAsInt); set(root, "portraitImageScale", portraitImageScale, JsonElement::getAsInt); set(root, "portraitImageOffsetX", portraitImageOffsetX, JsonElement::getAsInt); set(root, "portraitImageOffsetY", portraitImageOffsetY, JsonElement::getAsInt);
        whitelist.set(readStrings(root, "filter")); customPortraits.set(readStrings(root, "customPortraits")); displayNames.set(readStrings(root, "displayNames")); customInfo.set(readStrings(root, "customInfo"));
        nameColors.clear(); filterTemplates.clear(); if (root.has("filterTemplates") && root.get("filterTemplates").isJsonArray()) for (JsonElement e : root.getAsJsonArray("filterTemplates")) try { filterTemplates.add(GSON.fromJson(e, FilterTemplate.class)); } catch (RuntimeException ignored) { } if (root.has("nameColors") && root.get("nameColors").isJsonArray()) for (JsonElement e : root.getAsJsonArray("nameColors")) try { nameColors.add(GSON.fromJson(e, NameColor.class)); } catch (RuntimeException ignored) { }
        textElements.clear(); if (root.has("textElements") && root.get("textElements").isJsonArray()) for (JsonElement e : root.getAsJsonArray("textElements")) try { TextElement item = GSON.fromJson(e, TextElement.class); if (item != null) textElements.add(item); } catch (RuntimeException ignored) { }
        if (textElements.isEmpty()) resetElements();
        migrateLegacyDefaults(root);
    }

    /**
     * r8 及更早版本把聊天/面板底色默认成近黑色 0xE810222E，而且颜色自带的 alpha 会和“不透明度”二次相乘，
     * 结果看上去就是一块纯黑。r9 起颜色只取 RGB，透明度完全由“不透明度”设置决定；
     * 这里把仍是旧默认值的配置迁移到新的深蓝灰默认色，用户自己改过的颜色不动。
     */
    private static void migrateLegacyDefaults(JsonObject root) {
        int version = 0;
        try { if (root.has("version")) version = root.get("version").getAsInt(); } catch (RuntimeException ignored) { }
        if (version < 4) {
            if (chatBackgroundColor.get() == 0xE810222E) chatBackgroundColor.set(0xFF1C2838);
            if (portraitPanelColor.get() == 0xE810222E) portraitPanelColor.set(0xFF1C2838);
            if (backgroundAlpha.get() <= 0.0D) backgroundAlpha.set(0.62D);
        }

        // v5：半身像改成证件卡排版。如果文字元素还是旧的「名字居中 + 血量右上」默认两条，
        // 就换成新的「大字名字 + 血量小字 + 维度青色小字」，用户自己调过的元素不动。
        if (version < 5 && isOldDefaultElements()) {
            resetElements();
        }

        // v6：聊天区改成「单条轮播」，并且不再把玩家发言从原版聊天里删掉，
        // 保证按 T 打开原版聊天仍能看到前面所有发言（旧默认值是删掉，这里迁移成保留）。
        if (version < 6) {
            removeFromVanillaChat.set(false);
        }

        // v7：照片内容默认改成「全身（人物・含二层）」。旧版本默认值是 3（完整贴图原图），
        // 那一档不把二层合成到人物身上，容易被误认为「不显示二层皮肤」，所以顺手迁移掉。
        if (version < 7 && portraitPhotoMode.get() == 3) {
            portraitPhotoMode.set(4);
        }

        // v8：玩家发言改为「HUD 独占 + 打开原版聊天时临时回放」，
        // 所以重新打开接管开关（不再让原版悬浮聊天同时显示一份）。
        if (version < 8) {
            removeFromVanillaChat.set(true);
        }

        // v11：文字元素分成「固定的玩家名称 / 生命·饱食度」+ 可增删的自定义元素
        if (version < 12) { migrateVitalsToHealthOnly(); }
        if (version < 13) { migrateVitalsShowFood(); }
        if (version < 14) { migrateVitalsShowMaxFood(); }
        if (version < 15) { migrateNoBackdropBorder(); }
        if (version < 16) { migrateRestoreBorder(); }
        if (version < 17) { migrateSplitVitals(); }
        if (version < 18) { migrateFoodBelowVitals(); }
        if (version < 11) {
            ensureBuiltInElements();
        }

        // v10：名单里自动分配的初始颜色从红色改成蓝色（只改仍是旧默认红色的那些条目）
        if (version < 10) {
            for (NameColor item : nameColors) {
                if (item.color == 0xFFFF5555) {
                    item.color = chatNameColor.get();
                }
            }
        }
    }

    /** v14：饥饿也显示成「当前/最大」。 */
    private static void migrateVitalsShowMaxFood() {
        for (TextElement element : builtInElements()) {
            if (element.content != null && element.content.contains("%food%")
                    && !element.content.contains("%max_food%")) {
                element.content = LEGACY_VITALS_WITH_FOOD;
            }
        }
    }

    /** v13：恢复显示饥饿（自己的是真实值，别人的显示 Unknown）。随后 v17 会把它拆成独立元素。 */
    private static void migrateVitalsShowFood() {
        for (TextElement element : builtInElements()) {
            if (element.content != null && element.content.contains("%health%")
                    && !element.content.contains("%food%")) {
                element.content = LEGACY_VITALS_WITH_FOOD;
            }
        }
    }

    /**
     * 安全重置：只把文字元素的<b>锚点与偏移</b>摆回卡片内的安全位置，
     * 内容、颜色、字号、阴影、最大宽度全部保留；两个内置元素会重新设为可见。
     *
     * @return 受影响的元素数量
     */
    public static int resetElementPositions() {
        // 默认布局 = 当前实机调好的那套（照片下方：名字 / 自定义 / 生命+护甲同一行 / 饥饿），包含字号
        // 记下这次布局所针对的卡片宽度：之后缩放都以它为 1.0 倍
        elementBaseWidth = Math.max(1, portraitWidth.get());

        // 文字区起点 = 照片底边（那条绿线）+ 内边距 + 4px 间隙，保证四条都在绿线下面
        int lineTop = Math.max(0, portraitPhotoHeight.get()) + Math.max(2, portraitBackdropPadding.get()) + 20;

        int custom = 0;
        for (int i = 0; i < textElements.size(); i++) {
            TextElement element = textElements.get(i);
            element.horizontal = Horizontal.LEFT;
            element.vertical = Vertical.TOP;
            if (KIND_NAME.equals(element.kind)) {
                element.x = -1;
                element.y = lineTop;
                element.size = 8;
                element.color = 0xFFD6DEE8;
                element.shadow = true;
                element.visible = true;
            } else if (KIND_VITALS.equals(element.kind)) {
                element.x = -2;
                element.y = lineTop + 16;
                element.size = 6;
                element.color = 0xFF9AA6B2;
                element.shadow = false;
                element.visible = true;
            } else if (KIND_FOOD.equals(element.kind)) {
                element.x = -2;
                element.y = lineTop + 24;
                element.size = 6;
                element.color = 0xFF9AA6B2;
                element.shadow = false;
                element.visible = true;
            } else if (KIND_ARMOR.equals(element.kind)) {
                // 护甲与生命同一行（生命左边、护甲右边）
                element.x = 40;
                element.y = lineTop + 16;
                element.size = 6;
                element.color = 0xFF9AA6B2;
                element.shadow = false;
                element.visible = true;
            } else {
                // 自定义元素：名字下面一行，再往下依次排
                element.x = -1;
                element.y = lineTop + 9 + custom * 8;
                custom++;
            }
        }

        save();
        return textElements.size();
    }

    /** 三个固定内置元素（玩家名称 / 生命 / 饥饿）。 */
    public static java.util.List<TextElement> builtInElements() {
        java.util.List<TextElement> result = new java.util.ArrayList<>(4);
        for (TextElement element : textElements) {
            if (KIND_NAME.equals(element.kind) || KIND_VITALS.equals(element.kind)
                    || KIND_FOOD.equals(element.kind) || KIND_ARMOR.equals(element.kind)) {
                result.add(element);
            }
        }

        return result;
    }

    /** v16：恢复形象框边框（v15 误把整卡边框也归零了）。 */
    private static void migrateRestoreBorder() {
        if (portraitBorderThickness.get() == 0) {
            portraitBorderThickness.set(1);
        }
    }

    /** v17：生命与饱食度拆成两个元素（分行显示）。 */
    private static void migrateSplitVitals() {
        for (int i = 0; i < textElements.size(); i++) {
            TextElement element = textElements.get(i);
            if (element.content == null || !element.content.contains("%health%") || !element.content.contains("%food%")) {
                continue;
            }

            // 原元素只留生命
            element.content = VITALS_CONTENT;
            // 新增一个饱食度元素：同锚点、往下挪一行（字号 + 2 像素）
            TextElement food = new TextElement();
            food.kind = "custom";
            food.content = FOOD_CONTENT;
            food.horizontal = element.horizontal;
            food.vertical = element.vertical;
            food.x = element.x;
            food.y = element.y - Math.max(6, element.size + 2);
            food.size = element.size;
            food.color = element.color;
            food.shadow = element.shadow;
            food.maxWidth = element.maxWidth;
            food.visible = element.visible;
            textElements.add(i + 1, food);
            break;
        }
    }

    /**
     * 新建一条「饥饿」元素：与生命同锚点、同横偏移、同字号、同颜色，紧贴在生命<b>下面一行</b>。
     * <p>它和「玩家名称 / 生命」一样由模组负责：内容固定、删不掉（下次加载缺了就重建），
     * 客户端只能改「显示 / 缩放 / 位置」。
     */
    public static TextElement createFoodElement(TextElement vitals) {
        TextElement food = new TextElement();
        food.kind = KIND_FOOD;
        food.content = FOOD_CONTENT;
        food.horizontal = vitals == null ? Horizontal.LEFT : vitals.horizontal;
        food.vertical = vitals == null ? Vertical.BOTTOM : vitals.vertical;
        food.x = vitals == null ? 6 : vitals.x;
        food.y = (vitals == null ? -14 : vitals.y) + Math.max(6, (vitals == null ? 8 : vitals.size) + 2);
        food.size = vitals == null ? 8 : vitals.size;
        food.color = vitals == null ? 0xFF9AA6B2 : vitals.color;
        food.shadow = vitals == null ? Boolean.FALSE : vitals.shadow;
        food.maxWidth = vitals == null ? 160 : vitals.maxWidth;
        food.visible = Boolean.TRUE;
        return food;
    }

    /** 找「生命」元素：优先按 kind，找不到再按内容（老配置可能没有 kind 字段）。 */
    private static TextElement findVitalsElement() {
        TextElement byKind = null;
        TextElement byContent = null;
        for (TextElement element : textElements) {
            if (element.content == null) continue;
            if (KIND_VITALS.equals(element.kind) && byKind == null) byKind = element;
            if (element.content.contains("%health%") && byContent == null) byContent = element;
        }
        return byKind != null ? byKind : byContent;
    }

    /** 列表里有没有任何一条会显示饥饿的元素。 */
    private static boolean hasFoodElement() {
        for (TextElement element : textElements) {
            if (element.content != null && element.content.contains("%food%")) return true;
        }
        return false;
    }

    /**
     * v18：让「饥饿」成为默认配置的一部分，并且<b>默认就是「生命在上、饥饿在下」</b>。
     *
     * <p>顺序由模组保证，不依赖每个客户端自己摆：
     * ① 一条含 %food% 的元素都没有 → 补一条（放在生命下面一行）；
     * ② 与生命成对摆放的饥饿（同锚点、同横偏移）若排在生命<b>上面</b> → <b>调换两者位置</b>；
     * 摆到别的锚点/横偏移上的（明显是单独放的一处）不动。
     *
     * <p>注意：顶部锚点与底部锚点的 y 都是「越大越靠下」（见 CommsHud 的 ty 计算），
     * 所以「下面一行」一律是 y + max(6, size + 2)。
     */
    private static void migrateFoodBelowVitals() {
        TextElement vitals = findVitalsElement();
        if (vitals != null) {
            for (TextElement element : textElements) {
                if (element == vitals || element.content == null || !element.content.contains("%food%")) continue;
                // 老配置里那条饥饿是普通自定义元素 → 认领成固定内置（保留它的位置与外观）
                element.kind = KIND_FOOD;
                // 只处理「和生命成对」的那一条：同锚点、同横偏移
                if (element.vertical != vitals.vertical || element.x != vitals.x) continue;
                if (element.y <= vitals.y) {
                    int swap = element.y;
                    element.y = vitals.y;
                    vitals.y = swap;
                }
            }
        }
        if (!hasFoodElement()) {
            textElements.add(createFoodElement(vitals));
        }
    }

    /** v15：背景板不再有边框（默认 0）。 */
    private static void migrateNoBackdropBorder() {
        portraitBorderThickness.set(0);
    }

    /** v12：血条文案里的饥饿去掉（客户端拿不到别人的饥饿，改成只显示血量）。 */
    private static void migrateVitalsToHealthOnly() {
        for (TextElement element : textElements) {
            if (element.content != null && element.content.contains("饥饿 %food%")
                    && element.content.contains("%health%")) {
                element.content = VITALS_CONTENT;
            }
        }
    }

    private static boolean isOldDefaultElements() {
        if (textElements.size() != 2) return false;
        TextElement name = textElements.get(0);
        TextElement health = textElements.get(1);
        return "%player_name%".equals(name.content) && name.horizontal == Horizontal.CENTER && name.vertical == Vertical.BOTTOM
                && name.size == 9 && name.color == 0xFFFFFFFF
                && "%health%/%max_health%".equals(health.content) && health.horizontal == Horizontal.RIGHT
                && health.vertical == Vertical.TOP && health.size == 8 && health.color == 0xFFFFD27A;
    }

    private static void load() {
        if (loaded) return; loaded = true;
        AceChatFolders.migrateLegacyIfNeeded();   // 老目录 config/acechat → ACE_config（只搬一次，留备份）
        AceChatFolders.ensure();
        try { if (Files.exists(CONFIG_FILE)) apply(JsonParser.parseString(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8)).getAsJsonObject()); else { loadLegacy(); save(); } } catch (Exception ignored) { resetElements(); save(); } ensureBuiltInElements();
    }

    private static void loadLegacy() {
        Path legacy = Path.of("config", "ace_chat.toml"); if (!Files.exists(legacy)) { resetElements(); return; }
        try { String text = Files.readString(legacy); hudEnabled.set(readLegacy(text, "enabled", true)); maxLines.set(readLegacy(text, "max_lines", 5)); boxWidth.set(readLegacy(text, "box_width", 300)); backgroundAlpha.set(readLegacy(text, "background_alpha", 1.0D)); } catch (IOException ignored) { }
        resetElements();
    }
    private static boolean readLegacy(String text, String key, boolean fallback) { String v = legacyValue(text, key); return v == null ? fallback : Boolean.parseBoolean(v); }
    private static int readLegacy(String text, String key, int fallback) { try { String v = legacyValue(text, key); return v == null ? fallback : Integer.parseInt(v); } catch (RuntimeException e) { return fallback; } }
    private static double readLegacy(String text, String key, double fallback) { try { String v = legacyValue(text, key); return v == null ? fallback : Double.parseDouble(v); } catch (RuntimeException e) { return fallback; } }
    private static String legacyValue(String text, String key) { for (String line : text.split("\\R")) if (line.trim().startsWith(key + " =")) return line.substring(line.indexOf('=') + 1).trim().replace("\"", ""); return null; }
    private static JsonObject object(JsonObject root, String key) { return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject(); }
    private static <T> void set(JsonObject object, String key, Value<T> value, Function<JsonElement, T> reader) { try { if (object.has(key)) value.set(reader.apply(object.get(key))); } catch (RuntimeException ignored) { } }
    private static <T extends Enum<T>> void setEnum(JsonObject object, String key, Value<T> value, Class<T> type) { try { if (object.has(key)) value.set(Enum.valueOf(type, object.get(key).getAsString().toUpperCase(Locale.ROOT))); } catch (RuntimeException ignored) { } }
    private static List<String> readStrings(JsonObject root, String key) { if (!root.has(key) || !root.get(key).isJsonArray()) return List.of(); List<String> result = new ArrayList<>(); for (JsonElement e : root.getAsJsonArray(key)) if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) result.add(e.getAsString()); return result; }
    private static JsonArray array(List<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private static void add(JsonObject o, String key, Object value) { o.add(key, GSON.toJsonTree(value)); }
    private static String mappedValue(List<String> mappings, String playerName, String fallback) { for (String entry : mappings) { int split = entry.indexOf('='); if (split > 0 && split < entry.length() - 1 && entry.substring(0, split).trim().equalsIgnoreCase(playerName)) return entry.substring(split + 1).trim(); } return fallback; }
    public static void saveAndResetDefaults() { resetAll(); resetElements(); save(); }
    private static void resetAll() { hudEnabled.reset(); centerXOffset.reset(); topYOffset.reset(); maxLines.reset(); boxWidth.reset(); backgroundAlpha.reset(); lineHoldSeconds.reset(); holdMinSeconds.reset(); holdMaxSeconds.reset(); chatBackgroundColor.reset(); chatNameColor.reset(); chatRightGap.reset(); chatLeftMargin.reset(); chatNameMaxWidth.reset(); chatSymbolPadding.reset(); chatSymbolColor.reset(); chatPaddingX.reset(); chatPaddingY.reset(); chatBorderEnabled.reset(); chatBorderColor.reset(); chatBorderThickness.reset(); filter.reset(); removeFromVanillaChat.reset(); vanillaPlayerChat.reset(); vanillaOtherMessages.reset(); includeActions.reset(); whitelist.reset(); chatVisibleLines.reset(); chatScreenLines.reset(); portraitEnabled.reset(); portraitWidth.reset(); portraitHeight.reset(); portraitXOffset.reset(); portraitYOffset.reset(); portraitScale.reset(); portraitAnchor.reset(); previewDragTarget.reset(); portraitXRatio.reset(); portraitYRatio.reset(); portraitFollowChat.reset(); portraitAlwaysVisible.reset(); portraitTint.reset(); portraitOpacity.reset(); portraitBrightness.reset(); portraitPanelColor.reset(); portraitPanelAlpha.reset(); portraitBorderColor.reset(); portraitBorderThickness.reset(); portraitBackdropEnabled.reset(); portraitBackdropColor.reset(); portraitBackdropAlpha.reset(); portraitBackdropPadding.reset(); portraitBustScale.reset(); skinOffsetX.reset(); skinOffsetY.reset(); portraitPhotoMode.reset(); portraitPhotoHeight.reset(); portraitPhotoDivider.reset(); portraitOuterLayer.reset(); capeEnabled.reset(); customPortraitEnabled.reset(); customPortraits.reset(); displayNames.reset(); customInfo.reset(); blipEnabled.reset(); goneSoundEnabled.reset(); soundVolume.reset(); newSoundPreset.reset(); newSoundVolume.reset(); newSoundPitch.reset(); newSoundSpeed.reset(); newSoundFadeIn.reset(); newSoundFadeOut.reset(); goneSoundPreset.reset(); goneSoundVolume.reset(); goneSoundPitch.reset(); goneSoundSpeed.reset(); goneSoundFadeIn.reset(); goneSoundFadeOut.reset(); portraitUseImage.reset(); portraitImageName.reset(); portraitImageScale.reset();
                deathOverlayEnabled.reset(); deathPreset.reset(); deathImageMaxSize.reset();
                deathImageScale.reset(); deathImageOffsetX.reset(); deathImageOffsetY.reset(); portraitImageOffsetX.reset(); portraitImageOffsetY.reset(); nameColors.clear(); filterTemplates.clear();
        // 滤镜模板区块（之前漏了：主界面「恢复默认」不会重置这些）
        filterEnabled.reset(); filterName.reset(); filterAlpha.reset(); filterBlend.reset();
                filterTint.reset(); filterBrightness.reset(); filterContrast.reset();
                filterSaturation.reset(); filterHue.reset(); filterEffectColors.reset();
        gradeEnabled.reset(); gradeBrightness.reset(); gradeContrast.reset(); gradeSaturation.reset();
        gradeHue.reset(); duotoneEnabled.reset(); duotoneDark.reset(); duotoneLight.reset();
        duotoneAmount.reset();
        PortraitGrade.invalidate(); }
    private CommsConfig() { }
}
