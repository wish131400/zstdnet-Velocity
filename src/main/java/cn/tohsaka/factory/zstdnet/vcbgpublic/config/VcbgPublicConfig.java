package cn.tohsaka.factory.zstdnet.vcbgpublic.config;

import java.time.Duration;
import java.util.Objects;

public record VcbgPublicConfig(
        boolean bridgeEnabled,
        String bridgeListenHost,
        int bridgeListenPort,
        String bridgeDefaultTargetServer,
        String bridgeUpstreamVelocityHost,
        int bridgeUpstreamVelocityPort,
        ProxyProtocolMode bridgeUpstreamProxyProtocol,
        ProxyProtocolMode bridgeInboundProxyProtocol,
        boolean bridgeRewriteCompressionThreshold,
        boolean premiumProfileForwarding,
        boolean clientHudSync,
        boolean voiceChatPassthrough,
        String voiceChatListen,
        String voiceChatTarget,
        String udpCustomRoutes,
        int maxConnPerIp,
        int maxReqPerWindow,
        Duration window,
        Duration banDuration,
        Duration statsInterval,
        int level,
        Duration flushInterval,
        Duration idleTimeout,
        long maxRatePerConnBps,
        long maxRateGlobalBps,
        int burstBytes
) {
    public VcbgPublicConfig {
        Objects.requireNonNull(bridgeListenHost, "bridgeListenHost");
        Objects.requireNonNull(bridgeDefaultTargetServer, "bridgeDefaultTargetServer");
        Objects.requireNonNull(bridgeUpstreamVelocityHost, "bridgeUpstreamVelocityHost");
        Objects.requireNonNull(bridgeUpstreamProxyProtocol, "bridgeUpstreamProxyProtocol");
        Objects.requireNonNull(bridgeInboundProxyProtocol, "bridgeInboundProxyProtocol");
        Objects.requireNonNull(voiceChatListen, "voiceChatListen");
        Objects.requireNonNull(voiceChatTarget, "voiceChatTarget");
        Objects.requireNonNull(udpCustomRoutes, "udpCustomRoutes");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(banDuration, "banDuration");
        Objects.requireNonNull(statsInterval, "statsInterval");
        Objects.requireNonNull(flushInterval, "flushInterval");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (bridgeListenPort < 0 || bridgeListenPort > 65535) {
            throw new IllegalArgumentException("bridgeListenPort out of range: " + bridgeListenPort);
        }
        if (bridgeUpstreamVelocityPort < 1 || bridgeUpstreamVelocityPort > 65535) {
            throw new IllegalArgumentException("bridgeUpstreamVelocityPort out of range: " + bridgeUpstreamVelocityPort);
        }
        if (maxConnPerIp < 0) {
            throw new IllegalArgumentException("maxConnPerIp must be >= 0");
        }
        if (maxReqPerWindow < 0) {
            throw new IllegalArgumentException("maxReqPerWindow must be >= 0");
        }
        if (level < 1 || level > 22) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        if (maxRatePerConnBps < 0) {
            throw new IllegalArgumentException("maxRatePerConnBps must be >= 0");
        }
        if (maxRateGlobalBps < 0) {
            throw new IllegalArgumentException("maxRateGlobalBps must be >= 0");
        }
        if (burstBytes <= 0) {
            throw new IllegalArgumentException("burstBytes must be > 0");
        }
    }

    public static VcbgPublicConfig defaults() {
        return new VcbgPublicConfig(
                false,
                "127.0.0.1",
                0,
                "",
                "127.0.0.1",
                25565,
                ProxyProtocolMode.AUTO,
                ProxyProtocolMode.AUTO,
                false,
                true,
                false,
                true,
                "",
                "",
                "",
                9999,
                50,
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                Duration.ZERO,
                9,
                Duration.ofMillis(2),
                Duration.ZERO,
                0L,
                0L,
                262144
        );
    }
}
