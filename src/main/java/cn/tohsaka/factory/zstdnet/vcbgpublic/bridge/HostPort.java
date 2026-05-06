package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import java.net.InetSocketAddress;

record HostPort(String host, int port) {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;

    HostPort {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    static HostPort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty host:port");
        }
        String value = raw.trim();

        if (value.startsWith("[") && value.contains("]")) {
            int end = value.indexOf(']');
            String host = value.substring(1, end);
            int port = DEFAULT_MINECRAFT_PORT;
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                port = Integer.parseInt(value.substring(end + 2).trim());
            }
            return new HostPort(normalizeHost(host), port);
        }

        int lastColon = value.lastIndexOf(':');
        int firstColon = value.indexOf(':');
        if (lastColon > 0 && firstColon == lastColon) {
            String host = value.substring(0, lastColon).trim();
            int port = Integer.parseInt(value.substring(lastColon + 1).trim());
            return new HostPort(normalizeHost(host), port);
        }
        return new HostPort(normalizeHost(value), DEFAULT_MINECRAFT_PORT);
    }

    InetSocketAddress toAddress() {
        return new InetSocketAddress(host, port);
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
