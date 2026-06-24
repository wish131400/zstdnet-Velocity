package cn.tohsaka.factory.zstdnet.vcbgpublic.config;

import java.util.Locale;

public enum ProxyProtocolMode {
    AUTO,
    TRUE,
    FALSE;

    public static ProxyProtocolMode parse(String raw, ProxyProtocolMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "true", "yes", "on", "enabled", "1" -> TRUE;
            case "false", "no", "off", "disabled", "0" -> FALSE;
            default -> fallback;
        };
    }
}
