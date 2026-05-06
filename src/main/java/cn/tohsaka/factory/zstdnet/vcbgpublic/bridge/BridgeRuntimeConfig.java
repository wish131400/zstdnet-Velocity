package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfig;

import java.time.Duration;
import java.util.Objects;

record BridgeRuntimeConfig(
        String listenHost,
        int listenPort,
        String upstreamVelocityHost,
        int upstreamVelocityPort,
        boolean upstreamProxyProtocol,
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
    BridgeRuntimeConfig {
        Objects.requireNonNull(listenHost, "listenHost");
        Objects.requireNonNull(upstreamVelocityHost, "upstreamVelocityHost");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(banDuration, "banDuration");
        Objects.requireNonNull(statsInterval, "statsInterval");
        Objects.requireNonNull(flushInterval, "flushInterval");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
    }

    static BridgeRuntimeConfig from(VcbgPublicConfig config) {
        return new BridgeRuntimeConfig(
                config.bridgeListenHost(),
                config.bridgeListenPort(),
                config.bridgeUpstreamVelocityHost(),
                config.bridgeUpstreamVelocityPort(),
                config.bridgeUpstreamProxyProtocol(),
                config.maxConnPerIp(),
                config.maxReqPerWindow(),
                config.window(),
                config.banDuration(),
                config.statsInterval(),
                config.level(),
                config.flushInterval(),
                config.idleTimeout(),
                config.maxRatePerConnBps(),
                config.maxRateGlobalBps(),
                config.burstBytes()
        );
    }
}
