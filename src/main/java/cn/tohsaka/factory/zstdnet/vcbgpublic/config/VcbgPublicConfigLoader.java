package cn.tohsaka.factory.zstdnet.vcbgpublic.config;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

public final class VcbgPublicConfigLoader {
    private VcbgPublicConfigLoader() {
    }

    public static VcbgPublicConfig load(Path dataDirectory, Logger logger) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("zstdnet-velocity.properties");
        ensureDefaultConfig(configPath);

        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        boolean bridgeEnabled = Boolean.parseBoolean(properties.getProperty("bridge_enabled", "false").trim());
        String bridgeListenHost = properties.getProperty("bridge_listen_host", "127.0.0.1").trim();
        int bridgeListenPort = parsePort(properties.getProperty("bridge_listen_port"), 0);
        String bridgeDefaultTargetServer = properties.getProperty("bridge_default_target_server", "").trim();
        String bridgeUpstreamVelocityHost = properties.getProperty("bridge_upstream_velocity_host", "127.0.0.1").trim();
        int bridgeUpstreamVelocityPort = parsePortClamped(
                properties.getProperty("bridge_upstream_velocity_port"),
                25565
        );
        boolean bridgeUpstreamProxyProtocol = Boolean.parseBoolean(
                properties.getProperty("bridge_upstream_proxy_protocol", "false").trim()
        );
        boolean voiceChatPassthrough = Boolean.parseBoolean(properties.getProperty("voice_chat_passthrough", "true").trim());
        String voiceChatListen = properties.getProperty("voice_chat_listen", "").trim();
        String voiceChatTarget = properties.getProperty("voice_chat_target", "").trim();
        int maxConnPerIp = Math.max(0, parseInt(properties.getProperty("max_conn_per_ip"), 9999));
        int maxReqPerWindow = Math.max(0, parseInt(properties.getProperty("max_req_per_window"), 50));
        Duration window = parseDuration(properties.getProperty("request_window"), Duration.ofSeconds(10));
        Duration banDuration = parseDuration(properties.getProperty("ban_duration"), Duration.ofMinutes(1));
        Duration statsInterval = parseDuration(properties.getProperty("stats_interval"), Duration.ofSeconds(10));
        int level = clamp(parseInt(properties.getProperty("level"), 9), 1, 22);
        Duration flushInterval = parseDuration(properties.getProperty("flush_interval"), Duration.ofMillis(2));
        Duration idleTimeout = parseDuration(properties.getProperty("idle_timeout"), Duration.ZERO);
        long maxRatePerConnBps = Math.max(0L, parseLong(properties.getProperty("max_rate_per_conn_bps"), 0L));
        long maxRateGlobalBps = Math.max(0L, parseLong(properties.getProperty("max_rate_global_bps"), 0L));
        int burstBytes = parsePositiveInt(properties.getProperty("burst_bytes"), 262144);

        logger.info(
                "zstdnet-velocity config loaded: bridge_enabled={} bridge_listen={}:{} bridge_default_target_server={} bridge_upstream_velocity={}:{} bridge_upstream_proxy_protocol={} voice_chat_passthrough={} voice_chat_listen={} voice_chat_target={} max_conn_per_ip={} max_req_per_window={} request_window={} ban_duration={} stats_interval={} level={} flush_interval={} idle_timeout={} rate_per_conn={} rate_global={} burst_bytes={}",
                bridgeEnabled,
                printable(bridgeListenHost),
                bridgeListenPort,
                printable(bridgeDefaultTargetServer),
                printable(bridgeUpstreamVelocityHost),
                bridgeUpstreamVelocityPort,
                bridgeUpstreamProxyProtocol,
                voiceChatPassthrough,
                printable(voiceChatListen),
                printable(voiceChatTarget),
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                level,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes
        );

        return new VcbgPublicConfig(
                bridgeEnabled,
                blankAsDefault(bridgeListenHost, "127.0.0.1"),
                bridgeListenPort,
                bridgeDefaultTargetServer,
                blankAsDefault(bridgeUpstreamVelocityHost, "127.0.0.1"),
                bridgeUpstreamVelocityPort,
                bridgeUpstreamProxyProtocol,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                level,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes
        );
    }

    private static void ensureDefaultConfig(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            return;
        }
        String template = """
                # ZstdNet Velocity 配置文件
                # 本插件运行在 Velocity / VC 代理端，用于把客户端 ZstdNet 连接桥接到后端 Minecraft 服务器。
                # 时间格式支持：ms、s、m、h、d；例如 2ms、10s、1m。部分数值填 0 表示关闭限制或关闭功能。
                #
                # 路由由 Velocity 自身的 velocity.toml 负责（servers 区域 + try 列表）。
                # 本插件不再处理服务器选择 / 切服 / 回退，只做 ZstdNet 桥接与统计。

                # 是否启用 ZstdNet 桥接监听。
                # false：插件加载但不开放 ZstdNet 桥接端口。
                # true：在 bridge_listen_host:bridge_listen_port 上监听客户端 ZstdNet 连接。
                bridge_enabled=false

                # ZstdNet 桥接监听地址。
                # 127.0.0.1 表示只允许本机访问；0.0.0.0 表示监听所有网卡，公网使用时请确认防火墙和安全组配置。
                bridge_listen_host=127.0.0.1

                # ZstdNet 桥接监听端口。
                # 玩家客户端连接这个端口后，插件会解压并转发到 Velocity 自身入口。
                bridge_listen_port=25580

                # 桥接的"默认后端服务器名"，用于启动校验、UDP 同端口转发和日志显示。
                # 这里填写 Velocity 配置文件 servers 区域里的服务器名（不是 IP:端口）。
                # 注意：TCP 多子服路由由 Velocity 处理；本字段不会让 TCP 桥接直连后端。
                # 例：velocity.toml 里有 lobby = "127.0.0.1:25566"，这里填 lobby 即可。
                bridge_default_target_server=lobby

                # Velocity 自身监听的 host。
                # 桥接解压后固定回连 Velocity 自身的 Minecraft 入口端口，玩家可以使用 /server 在不同子服间跳转。
                # 同机部署时填 127.0.0.1 即可，无需公网暴露。
                bridge_upstream_velocity_host=127.0.0.1

                # Velocity 自身监听的端口。
                # 即 velocity.toml 的 bind 端口（默认 25577，许多服主会改成 25565）。
                bridge_upstream_velocity_port=25565

                # 是否在上游连接前发送 PROXY v2 头以保留客户端真实 IP。
                # 启用此项需要同时把 velocity.toml 中 advanced.haproxy-protocol 设置为 true。
                # 注意：开启 velocity 端的 haproxy-protocol 后，所有连接到 Velocity 的入口都必须带 PROXY 头，
                # 因此在公网开放该端口前需要先用防火墙限制为仅本机/仅本插件来源。
                # 默认 false：保留 Velocity 默认行为，桥接玩家在 Velocity 端日志中显示为 127.0.0.1。
                bridge_upstream_proxy_protocol=false

                # 是否启用语音/同端口 UDP 原样透传。
                # true：尝试启动 UDP 转发，兼容 Sable/机械动力：航空学同端口 UDP，以及可选的 Simple Voice Chat UDP。
                # false：不启动 UDP 透传，只处理 TCP ZstdNet 桥接。
                voice_chat_passthrough=true

                # 语音 UDP 监听地址。
                # 留空时不单独启动语音 UDP 端口；如需 Simple Voice Chat，可填 0.0.0.0:24455。
                voice_chat_listen=

                # 语音 UDP 目标地址。
                # 留空时不会启动独立语音 UDP 转发；如后端语音端口是 24454，可填 127.0.0.1:24454。
                voice_chat_target=

                # 单个来源 IP 同时允许的最大连接数。
                # 设置为 0 表示不限制；默认值较大，主要用于防止异常连接刷爆代理。
                max_conn_per_ip=9999

                # 单个来源 IP 在 request_window 时间窗口内允许的新连接请求数量。
                # 超过后会临时封禁 ban_duration。
                max_req_per_window=50

                # 请求统计窗口长度。
                request_window=10s

                # 触发请求频率限制后的临时封禁时长。
                ban_duration=1m

                # 运行时统计日志输出间隔。
                # 0 表示关闭周期统计日志。
                stats_interval=10s

                # Zstd 压缩等级，范围 1 到 22。
                # 数值越高压缩率通常越高，但 CPU 消耗也越高；推荐 3 到 9。
                level=9

                # 后端到客户端方向压缩流的 flush 间隔。
                # 数值越小延迟越低，但包数量可能增加；推荐 2ms。
                flush_interval=2ms

                # 后端连接读超时时间。
                # 0 表示不额外设置超时。
                idle_timeout=0

                # 单连接最大输出速率，单位 byte/s。
                # 0 表示不限制。
                max_rate_per_conn_bps=0

                # 全局最大输出速率，单位 byte/s。
                # 0 表示不限制。
                max_rate_global_bps=0

                # 限速令牌桶突发容量，单位 byte。
                # 只有开启限速时才明显生效。
                burst_bytes=262144
                """;
        Files.writeString(configPath, template, StandardCharsets.UTF_8);
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 && value <= 65535 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parsePortClamped(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 1 && value <= 65535 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        int value = parseInt(raw, fallback);
        return value > 0 ? value : fallback;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            }
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String blankAsDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String printable(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }
}
