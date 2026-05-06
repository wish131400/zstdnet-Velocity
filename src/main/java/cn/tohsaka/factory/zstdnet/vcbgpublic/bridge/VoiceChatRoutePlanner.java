package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import org.slf4j.Logger;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

final class VoiceChatRoutePlanner {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";

    private VoiceChatRoutePlanner() {
    }

    static VoiceChatPassthroughDecision resolveVoiceChatPassthrough(
            Logger logger,
            HostPort gameListen,
            HostPort gameTarget,
            boolean voiceChatPassthrough,
            String configuredListen,
            String configuredTarget,
            Integer simpleVoiceChatPort,
            String simpleVoiceChatSource
    ) {
        if (!voiceChatPassthrough) {
            return VoiceChatPassthroughDecision.disabled("voice chat UDP passthrough disabled by config");
        }
        if (gameListen == null || gameTarget == null) {
            return VoiceChatPassthroughDecision.disabled("game UDP route is unavailable");
        }

        boolean samePortMode = simpleVoiceChatPort != null && simpleVoiceChatPort == -1;
        HostPort target;
        try {
            target = parseOptionalHostPort(configuredTarget);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_target: " + e.getMessage());
        }
        if (target == null) {
            if (simpleVoiceChatPort == null) {
                return VoiceChatPassthroughDecision.disabled(
                        "voice_chat_target is blank and Simple Voice Chat config was not found at " + simpleVoiceChatSource
                );
            }
            if (samePortMode) {
                target = gameTarget;
            } else if (simpleVoiceChatPort >= 1 && simpleVoiceChatPort <= 65535) {
                target = new HostPort("127.0.0.1", simpleVoiceChatPort);
            } else {
                return VoiceChatPassthroughDecision.disabled("invalid Simple Voice Chat port " + simpleVoiceChatPort);
            }
        }

        HostPort listen;
        try {
            listen = parseOptionalHostPort(configuredListen);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_listen: " + e.getMessage());
        }
        if (listen == null) {
            if (simpleVoiceChatPort == null) {
                listen = new HostPort(gameListen.host(), target.port());
            } else if (samePortMode) {
                listen = gameListen;
            } else {
                return VoiceChatPassthroughDecision.disabled(
                        "Simple Voice Chat uses a separate port, so voice_chat_listen must be set explicitly"
                );
            }
        }

        if (listen.equals(gameListen) && target.equals(gameTarget)) {
            return VoiceChatPassthroughDecision.reuseGameRoute("voice chat UDP reuses the built-in game UDP route");
        }

        if (listen.port() == target.port() && isLocalHost(target.host())) {
            HostPort adjustedListen = chooseAlternateVoiceChatListen(listen, target);
            if (!adjustedListen.equals(listen)) {
                logger.warn(
                        "zstdnet-velocity voice chat listen {} collides with local target {}; using {} instead.",
                        listen,
                        target,
                        adjustedListen
                );
                listen = adjustedListen;
            }
        }

        return VoiceChatPassthroughDecision.route(new UdpRoute("simple_voice_chat", listen, target));
    }

    static Integer readSimpleVoiceChatPort(Path configPath, Logger logger) {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }

        Properties props = new Properties();
        try (var in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("zstdnet-velocity failed reading Simple Voice Chat config {}: {}", configPath, e.toString());
            return null;
        }
        String rawPort = props.getProperty("port");
        Integer parsedPort = parseSimpleVoiceChatPort(rawPort);
        if (parsedPort == null && rawPort != null && !rawPort.isBlank()) {
            logger.warn("zstdnet-velocity invalid Simple Voice Chat port '{}' in {}", rawPort, configPath);
        }
        return parsedPort;
    }

    static Integer parseSimpleVoiceChatPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static HostPort parseOptionalHostPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return HostPort.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(raw.trim(), e);
        }
    }

    private static HostPort chooseAlternateVoiceChatListen(HostPort listen, HostPort target) {
        int preferred = target.port() >= MAX_PORT ? MIN_PORT : target.port() + 1;
        int port = findFreeUdpPort(listen.host(), preferred, listen.port(), target.port());
        return port == listen.port() ? listen : new HostPort(listen.host(), port);
    }

    private static int findFreeUdpPort(String host, int preferredPort, int... reservedPorts) {
        String hostToProbe = host == null || host.isBlank() ? DEFAULT_LISTEN_HOST : host.trim();
        int start = Math.max(MIN_PORT, Math.min(MAX_PORT, preferredPort));

        for (int port = start; port <= MAX_PORT; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < start; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }

        throw new IllegalStateException("no free UDP port available for voice chat passthrough");
    }

    private static boolean isReservedPort(int port, int... reservedPorts) {
        if (reservedPorts == null) {
            return false;
        }
        for (int reserved : reservedPorts) {
            if (reserved == port) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUdpBindable(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            InetSocketAddress address;
            if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
                address = new InetSocketAddress(port);
            } else {
                address = new InetSocketAddress(InetAddress.getByName(host), port);
            }
            socket.bind(address);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.startsWith("127.")
                || normalized.equals("::1")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::");
    }
}
