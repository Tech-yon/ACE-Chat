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
    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    /** 皮肤缓存有效期：只作为兜底，主要靠 进游戏/玩家加入/打开配置界面/手动刷新 这些事件来失效。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static ListSnapshot lastCustomList = new ListSnapshot("");
    private static Map<String, ResourceLocation> customMappings = Map.of();

    public static PortraitSource resolve(Minecraft minecraft, UUID sender, String name) {
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(sender);
        if (cached != null && now - cached.createdAt < CACHE_TTL_MS && cached.name.equals(name)) {
            return cached.source;
        }

        SkinData skin = skinFor(minecraft, sender);
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
        CACHE.put(sender, new Cached(name, now, source));
        return source;
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

    private record Cached(String name, long createdAt, PortraitSource source) {
    }

    private record ListSnapshot(String value) {
    }
}
