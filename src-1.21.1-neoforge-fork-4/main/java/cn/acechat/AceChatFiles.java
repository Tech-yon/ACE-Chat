package cn.acechat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 统一原子写：临时文件 + 替换，避免崩溃/断电留下半截 JSON。 */
public final class AceChatFiles {
    private AceChatFiles() {
    }

    public static void writeStringAtomic(Path path, String text) throws IOException {
        if (path == null) {
            throw new IOException("path is null");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}