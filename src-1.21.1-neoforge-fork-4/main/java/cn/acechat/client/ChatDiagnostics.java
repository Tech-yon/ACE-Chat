package cn.acechat.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/** NCR 适配的轻量日志：只在“认出/认不出”时记前几条，避免刷屏。 */
public final class ChatDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    private static final Set<String> SEEN = new LinkedHashSet<>();
    private static final int LIMIT = 12;

    public static void note(String kind, String detail, String text) {
        if (SEEN.size() >= LIMIT) {
            return;
        }
        String body = text == null ? "" : text.replace('\n', ' ');
        if (body.length() > 120) {
            body = body.substring(0, 120);
        }
        if (!SEEN.add(kind + "|" + body)) {
            return;
        }
        LOGGER.info("[acechat] ncr {} {} :: {}", kind, detail == null ? "-" : detail, body);
    }

    private ChatDiagnostics() {
    }
}
