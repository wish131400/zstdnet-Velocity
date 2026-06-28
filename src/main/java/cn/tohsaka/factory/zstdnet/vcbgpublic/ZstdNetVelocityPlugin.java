package cn.tohsaka.factory.zstdnet.vcbgpublic;

import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.BridgeTarget;
import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.BridgeTargetResolver;
import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.TcpBridgeService;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.ProxyProtocolMode;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfig;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfigLoader;
import cn.tohsaka.factory.zstdnet.vcbgpublic.hud.ZstdNetHudBroadcaster;
import cn.tohsaka.factory.zstdnet.vcbgpublic.profile.MojangPremiumProfileProvider;
import cn.tohsaka.factory.zstdnet.vcbgpublic.profile.PremiumProfileProvider;
import com.velocitypowered.api.event.EventTask;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Plugin(
        id = "zstdnet-velocity",
        name = "ZstdNet Velocity",
        version = "1.4.3",
        authors = {"tohsaka"},
        description = "Velocity bridge plugin for ZstdNet."
)
public final class ZstdNetVelocityPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final TcpBridgeService tcpBridgeService;
    private final ZstdNetHudBroadcaster hudBroadcaster;

    private volatile VcbgPublicConfig config = VcbgPublicConfig.defaults();
    private volatile PremiumProfileProvider premiumProfileProvider;

    @Inject
    public ZstdNetVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.tcpBridgeService = new TcpBridgeService(logger, this::resolveBridgeTarget);
        this.hudBroadcaster = new ZstdNetHudBroadcaster(proxyServer, this, logger, tcpBridgeService.stats());
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws IOException {
        this.config = VcbgPublicConfigLoader.load(dataDirectory, logger);
        if (config.premiumProfileForwarding()) {
            this.premiumProfileProvider = new MojangPremiumProfileProvider(
                    logger,
                    "https://sessionserver.mojang.com",
                    Duration.ofSeconds(5),
                    false
            );
            logger.info("zstdnet-velocity premium profile forwarding enabled");
        } else {
            this.premiumProfileProvider = null;
            logger.info("zstdnet-velocity premium profile forwarding disabled by config");
        }
        ResolvedProxyProtocolSettings proxyProtocolSettings = resolveProxyProtocolSettings(config);
        this.tcpBridgeService.start(
                config,
                proxyProtocolSettings.upstreamProxyProtocol(),
                proxyProtocolSettings.inboundProxyProtocol()
        );
        if (this.tcpBridgeService.isRunning() && config.clientHudSync()) {
            this.hudBroadcaster.start(config);
        } else if (this.tcpBridgeService.isRunning()) {
            logger.info("zstdnet-velocity client HUD sync disabled by config");
        }
        logger.info(
                "zstdnet-velocity loaded: bridge_enabled={} listen={}:{} bridge_default_target_server={}",
                config.bridgeEnabled(),
                printable(config.bridgeListenHost()),
                config.bridgeListenPort(),
                printable(config.bridgeDefaultTargetServer())
        );
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        hudBroadcaster.stop();
        tcpBridgeService.stop();
    }

    @Subscribe
    public void onPlayerChannelRegister(PlayerChannelRegisterEvent event) {
        hudBroadcaster.markRegisteredChannels(event.getPlayer(), event.getChannels());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        hudBroadcaster.forget(event.getPlayer());
    }

    @Subscribe
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        PremiumProfileProvider provider = premiumProfileProvider;
        if (provider == null) {
            return null;
        }

        String username = event.getUsername();
        if (username == null || username.isBlank()) {
            return null;
        }

        return EventTask.resumeWhenComplete(provider.findProfile(username).thenAccept(profile -> profile.ifPresentOrElse(
                premiumProfile -> {
                    List<GameProfile.Property> properties = premiumProfile.properties().stream()
                            .map(property -> new GameProfile.Property(property.name(), property.value(), property.signature()))
                            .toList();
                    GameProfile gameProfile = new GameProfile(premiumProfile.uuid(), premiumProfile.name(), properties);
                    event.setGameProfile(gameProfile);
                    logger.info(
                            "zstdnet-velocity premium profile fixed: name={} uuid={} properties={}",
                            premiumProfile.name(),
                            premiumProfile.uuid(),
                            properties.size()
                    );
                },
                () -> logger.info("zstdnet-velocity premium profile lookup missed: name={}", username)
        )));
    }

    private Optional<BridgeTarget> resolveBridgeTarget(String preferredServerName) {
        String candidate = preferredServerName;
        if (candidate == null || candidate.isBlank()) {
            candidate = config.bridgeDefaultTargetServer();
        }
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        return resolveRegisteredServer(candidate).flatMap(BridgeTargetResolver::fromRegisteredServer);
    }

    private Optional<RegisteredServer> resolveRegisteredServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return Optional.empty();
        }
        Optional<RegisteredServer> server = proxyServer.getServer(serverName);
        if (server.isEmpty()) {
            logger.warn("zstdnet-velocity bridge target '{}' not found in velocity.toml [servers]", serverName);
        }
        return server;
    }

    private ResolvedProxyProtocolSettings resolveProxyProtocolSettings(VcbgPublicConfig config) {
        Optional<Boolean> velocityHaproxyProtocol = readVelocityHaproxyProtocol();
        boolean upstreamProxyProtocol = switch (config.bridgeUpstreamProxyProtocol()) {
            case TRUE -> true;
            case FALSE -> false;
            case AUTO -> velocityHaproxyProtocol.orElseGet(() -> {
                logger.warn(
                        "zstdnet-velocity bridge_upstream_proxy_protocol=auto could not read velocity.toml advanced.haproxy-protocol; falling back to false"
                );
                return false;
            });
        };

        boolean listenHostLoopback = isLoopbackHost(config.bridgeListenHost());
        boolean inboundProxyProtocol = switch (config.bridgeInboundProxyProtocol()) {
            case TRUE -> {
                if (!listenHostLoopback) {
                    logger.warn(
                            "zstdnet-velocity bridge_inbound_proxy_protocol=true while bridge_listen_host={} is not loopback; only use this behind a trusted front proxy",
                            printable(config.bridgeListenHost())
                    );
                }
                yield true;
            }
            case FALSE -> false;
            case AUTO -> listenHostLoopback;
        };
        if (config.bridgeInboundProxyProtocol() == ProxyProtocolMode.AUTO && !inboundProxyProtocol) {
            logger.warn(
                    "zstdnet-velocity bridge_inbound_proxy_protocol=auto disabled because bridge_listen_host={} is not loopback; set true only when the bridge port is reachable solely by a trusted front proxy",
                    printable(config.bridgeListenHost())
            );
        }
        logger.info(
                "zstdnet-velocity proxy protocol resolved: upstream_mode={} velocity_haproxy_protocol={} upstream={} inbound_mode={} inbound={}",
                config.bridgeUpstreamProxyProtocol(),
                velocityHaproxyProtocol.map(String::valueOf).orElse("unknown"),
                upstreamProxyProtocol,
                config.bridgeInboundProxyProtocol(),
                inboundProxyProtocol
        );
        return new ResolvedProxyProtocolSettings(upstreamProxyProtocol, inboundProxyProtocol);
    }

    private Optional<Boolean> readVelocityHaproxyProtocol() {
        for (Path candidate : velocityTomlCandidates()) {
            Optional<Boolean> value = readVelocityHaproxyProtocol(candidate);
            if (value.isPresent()) {
                logger.info("zstdnet-velocity detected velocity.toml haproxy-protocol={} at {}", value.get(), candidate);
                return value;
            }
        }
        return Optional.empty();
    }

    private List<Path> velocityTomlCandidates() {
        List<Path> candidates = new ArrayList<>();
        Path pluginsDirectory = dataDirectory.getParent();
        if (pluginsDirectory != null && pluginsDirectory.getParent() != null) {
            candidates.add(pluginsDirectory.getParent().resolve("velocity.toml"));
        }
        candidates.add(Path.of("velocity.toml").toAbsolutePath());
        return candidates;
    }

    private Optional<Boolean> readVelocityHaproxyProtocol(Path velocityToml) {
        if (velocityToml == null || !Files.isRegularFile(velocityToml)) {
            return Optional.empty();
        }
        try {
            boolean inAdvancedSection = false;
            for (String rawLine : Files.readAllLines(velocityToml, StandardCharsets.UTF_8)) {
                String line = stripTomlComment(rawLine).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inAdvancedSection = "advanced".equalsIgnoreCase(line.substring(1, line.length() - 1).trim());
                    continue;
                }
                if (!inAdvancedSection) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                if (!"haproxy-protocol".equals(key)) {
                    continue;
                }
                String value = line.substring(equals + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "true" -> Optional.of(true);
                    case "false" -> Optional.of(false);
                    default -> Optional.empty();
                };
            }
        } catch (IOException e) {
            logger.warn("zstdnet-velocity failed to read velocity.toml at {}: {}", velocityToml, e.toString());
        }
        return Optional.empty();
    }

    private static String stripTomlComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (ch == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host.trim());
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String printable(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }

    private record ResolvedProxyProtocolSettings(boolean upstreamProxyProtocol, boolean inboundProxyProtocol) {
    }
}
