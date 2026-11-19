package cn.acechat.client;

import cn.acechat.AceChat;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 一名发言者的立绘来源；自定义 PNG 缺失时自动使用实时皮肤。 */
public record PortraitSource(UUID sender, String name, ResourceLocation skinTexture,
                             ResourceLocation capeTexture, boolean slim,
                             ResourceLocation customTexture, int customWidth, int customHeight) {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("acechat");
    private static String lastPreviewLog = "";
    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    /** 皮肤缓存有效期：只作为兜底，主要靠 进游戏/玩家加入/打开配置界面/手动刷新 这些事件来失效。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static ListSnapshot lastCustomList = new ListSnapshot("");
    private static Map<String, ResourceLocation> customMappings = Map.of();

    public static PortraitSource resolve(Minecraft minecraft, UUID sender, String name) {
        // 本机预览：把“自己”临时替换成正在编辑的那张卡
        boolean previewing = CommsConfig.previewCovers(name);
        String cardName = previewing ? CommsConfig.effectivePreviewCard() : name;
        // 卡牌身份优先：卡里指定了皮肤/立绘就用卡里的（离线也能用）
        boolean realPlayer = minecraft.getConnection() != null && sender != null
                && minecraft.getConnection().getPlayerInfo(sender) != null;
        if (previewing && cardName != null
                && !PlayerCards.exists(cardName) && !CardCharacter.hasRemote(cardName)) {
            if (!("missing:" + cardName).equals(lastPreviewLog)) {
                lastPreviewLog = "missing:" + cardName;
                LOGGER.info("[acechat] 预览卡不存在：" + cardName);
            }
        }
        if (cardName != null && (PlayerCards.exists(cardName) || CardCharacter.hasRemote(cardName))) {
            CardCharacter.Data character = realPlayer ? CardCharacter.read(cardName) : CardCharacter.readStage(cardName);
            boolean remoteAsset = !CardCharacter.preferLocal(cardName)
                    && CardCharacter.hasRemote(cardName) && !realPlayer;
            // 离线 NPC（演出里的说话人）和本机预览：卡里配的皮肤直接生效，不看 boundPlayer；
            // 真实在线玩家仍按 boundPlayer 决定用在线皮肤还是卡皮肤。
            boolean useCardSkin = character.hasSkin()
                    && (previewing || !character.boundPlayer || !realPlayer);
            ResourceLocation cardSkin = useCardSkin
                    ? (remoteAsset
                        ? CardTextures.addRemote("skin", character.skin, ClientAssets.bytesFor("skin", character.skin))
                        : CardTextures.skin(character.skin))
                    : null;
            ResourceLocation cardPortrait = (character.hasPortrait() && CommsConfig.customPortraitEnabled.get())
                    ? (remoteAsset
                        ? CardTextures.addRemote("portrait", character.portrait, ClientAssets.bytesFor("portrait", character.portrait))
                        : CardTextures.portrait(character.portrait))
                    : null;
            if (previewing) {
                String info = "name=" + name + " card=" + cardName + " bound=" + character.boundPlayer
                        + " skin=" + character.skin + " tex=" + cardSkin + " real=" + realPlayer;
                if (!info.equals(lastPreviewLog)) {
                    lastPreviewLog = info;
                    LOGGER.info("[acechat] 预览解析 " + info);
                }
            }
            if (cardSkin != null || cardPortrait != null) {
                SkinData skin = cardSkin != null
                        ? new SkinData(cardSkin, null, character.skin.startsWith(CardAssets.SLIM + "/"))
                        : skinFor(minecraft, sender, name);
                int width = 1;
                int height = 1;
                if (cardPortrait != null) {
                    int[] dimensions = CardTextures.dimensions(character.portrait, false);
                    width = dimensions[0];
                    height = dimensions[1];
                }
                PortraitSource source = new PortraitSource(sender, name, skin.texture(), skin.cape(),
                        skin.slim(), cardPortrait, width, height);
                CACHE.put(sender, new Cached(name, System.currentTimeMillis(), cardStamp(cardName), source));
                return source;
            }
        }
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(sender);
        long stamp = cardStamp(name);
        if (cached != null && now - cached.createdAt < CACHE_TTL_MS
                && cached.name.equals(name) && cached.stamp() == stamp) {
            return cached.source;
        }

        SkinData skin = skinFor(minecraft, sender, name);
        ResourceLocation custom = null;
        int customWidth = 1;
        int customHeight = 1;
        if (CommsConfig.customPortraitEnabled.get()) {
            refreshCustomMappings();
            custom = customMappings.get(name.toLowerCase(Locale.ROOT));
            if (custom == null) {
                custom = findConventionPortrait(minecraft, name);
            }
            if (custom != null) {
                int[] dimensions = readDimensions(minecraft, custom);
                customWidth = dimensions[0];
                customHeight = dimensions[1];
            }
        }

        PortraitSource source = new PortraitSource(sender, name, skin.texture(), skin.cape(),
                skin.slim(), custom, customWidth, customHeight);
        CACHE.put(sender, new Cached(name, now, cardStamp(name), source));
        return source;
    }

    /** 卡文件的修改时间；没有卡返回 -1。 */
    private static long cardStamp(String name) {
        java.nio.file.Path file = PlayerCards.fileOf(name);
        return file == null ? -1L : file.toFile().lastModified();
    }

    public static void clear() {
        CACHE.clear();
    }

    /** 某个玩家的皮肤可能变了（例如刚加入、换了皮肤）时，只失效他这一条，避免全表重算。 */
    public static void invalidate(UUID sender) {
        if (sender != null) {
            CACHE.remove(sender);
        }
    }

    private static SkinData skinFor(Minecraft minecraft, UUID sender) {
        return skinFor(minecraft, sender, null);
    }

    /**
     * 取皮肤：先按 UUID 找实体/玩家列表；都找不到时，若给了名字，再按名字在在线玩家里兜底
     * （NCR 兼容 / 演出里用假 UUID 的虚拟角色靠这个拿到正确皮肤）。
     */
    private static SkinData skinFor(Minecraft minecraft, UUID sender, String name) {
        if (name != null && !name.isBlank()) {
            if (minecraft.player != null && minecraft.player.getGameProfile().getName().equalsIgnoreCase(name)
                    && (sender == null || !minecraft.player.getUUID().equals(sender))) {
                sender = minecraft.player.getUUID();
            } else if (minecraft.getConnection() != null) {
                for (var info : minecraft.getConnection().getOnlinePlayers()) {
                    if (info.getProfile().getName().equalsIgnoreCase(name)) {
                        sender = info.getProfile().getId();
                        break;
                    }
                }
            }
        }
        if (sender == null) {
            sender = new UUID(0L, 0L);
        }
        ResourceLocation entitySkin = null;
        ResourceLocation entityCape = null;
        boolean entitySlim = false;
        if (minecraft.level != null) {
            for (AbstractClientPlayer player : minecraft.level.players()) {
                if (player.getUUID().equals(sender)) {
                    entitySkin = player.getSkin().texture();
                    entityCape = player.getSkin().capeTexture();
                    entitySlim = isSlim(player);
                    break;
                }
            }
        }

        PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(sender);
        // 实体上如果只是默认皮肤（Steve/Alex），而连接里还有一张别的贴图（换肤模组/服务器下发），就用后者。
        // 默认皮肤的二层是空的，用错了就会表现为「形象框不显示二层皮肤」。
        if (isDefaultSkin(entitySkin) && info != null && info.getSkin().texture() != null
                && !isDefaultSkin(info.getSkin().texture())) {
            return new SkinData(info.getSkin().texture(),
                    info.getSkin().capeTexture(),
                    info.getSkin().model() == PlayerSkin.Model.SLIM);
        }

        if (entitySkin != null) {
            return new SkinData(entitySkin, entityCape, entitySlim);
        }

        if (info != null) {
            return new SkinData(info.getSkin().texture(),
                    info.getSkin().capeTexture(),
                    info.getSkin().model() == PlayerSkin.Model.SLIM);
        }

        return new SkinData(DefaultPlayerSkin.get(sender).texture(), null,
                DefaultPlayerSkin.get(sender).model() == PlayerSkin.Model.SLIM);
    }

    /** 是否就是默认的 Steve/Alex 贴图（这种贴图的第二层是空的）。 */
    public static boolean isDefaultSkin(ResourceLocation location) {
        if (location == null) {
            return true;
        }

        String path = location.getPath();
        return location.getNamespace().equals("minecraft")
                && (path.endsWith("textures/entity/steve.png") || path.endsWith("textures/entity/alex.png")
                    || path.contains("textures/entity/player/"));
    }

    private static boolean isSlim(AbstractClientPlayer player) {
        try {
            return player.getSkin().model() == PlayerSkin.Model.SLIM;
        } catch (RuntimeException | LinkageError ignored) {
            return slimFromProfile(player.getGameProfile());
        }
    }

    private static boolean slimFromProfile(GameProfile profile) {
        try {
            return profile != null && profile.getProperties().containsKey("slim");
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String shortId(UUID sender) {
        return sender.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static void refreshCustomMappings() {
        String joined = String.join("\u001F", CommsConfig.customPortraits.get());
        if (joined.equals(lastCustomList.value)) {
            return;
        }
        lastCustomList = new ListSnapshot(joined);
        Map<String, ResourceLocation> result = new HashMap<>();
        for (String entry : CommsConfig.customPortraits.get()) {
            int split = entry.indexOf('=');
            if (split <= 0 || split >= entry.length() - 1) {
                continue;
            }
            String player = entry.substring(0, split).trim().toLowerCase(Locale.ROOT);
            ResourceLocation location = ResourceLocation.tryParse(entry.substring(split + 1).trim());
            if (!player.isEmpty() && location != null) {
                result.put(player, location);
            }
        }
        customMappings = Map.copyOf(result);
    }

    private static ResourceLocation findConventionPortrait(Minecraft minecraft, String name) {
        String safeName = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ResourceLocation own = ResourceLocation.fromNamespaceAndPath(
                AceChat.MOD_ID, "textures/portraits/" + safeName + ".png");
        if (minecraft.getResourceManager().getResource(own).isPresent()) {
            return own;
        }
        ResourceLocation pack = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/portraits/" + safeName + ".png");
        return minecraft.getResourceManager().getResource(pack).isPresent() ? pack : null;
    }

    private static int[] readDimensions(Minecraft minecraft, ResourceLocation location) {
        try {
            Resource resource = minecraft.getResourceManager().getResource(location).orElse(null);
            if (resource == null) {
                return new int[]{1, 1};
            }
            try (NativeImage image = NativeImage.read(resource.open())) {
                return new int[]{Math.max(1, image.getWidth()), Math.max(1, image.getHeight())};
            }
        } catch (IOException | RuntimeException ignored) {
            return new int[]{1, 1};
        }
    }

    private record SkinData(ResourceLocation texture, ResourceLocation cape, boolean slim) {
    }

    private record Cached(String name, long createdAt, long stamp, PortraitSource source) {
    }

    private record ListSnapshot(String value) {
    }
}
