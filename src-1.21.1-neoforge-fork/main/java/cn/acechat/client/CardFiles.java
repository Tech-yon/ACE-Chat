package cn.acechat.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 卡文件的纯文件操作（不依赖客户端类，服务端也能用）。 */
public final class CardFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() {
        return AceChatFolders.cardsDir();
    }

    public static String fileNameOf(String cardName) {
        String name = cardName == null ? PlayerCards.DEFAULT_CARD : cardName.trim();
        if (name.isEmpty()) {
            name = PlayerCards.DEFAULT_CARD;
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
    }

    public static Path fileOf(String cardName) {
        Path dir = dir();
        return dir == null ? null : dir.resolve(fileNameOf(cardName));
    }

    public static boolean exists(String cardName) {
        Path file = fileOf(cardName);
        return file != null && Files.isRegularFile(file);
    }

    public static JsonObject read(String cardName) {
        Path file = fileOf(cardName);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static boolean write(String cardName, JsonObject data) {
        Path file = fileOf(cardName);
        if (file == null || data == null) {
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private CardFiles() {
    }
}
