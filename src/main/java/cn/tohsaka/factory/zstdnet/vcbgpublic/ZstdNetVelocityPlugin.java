package cn.tohsaka.factory.zstdnet.vcbgpublic;

import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.BridgeTarget;
import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.BridgeTargetResolver;
import cn.tohsaka.factory.zstdnet.vcbgpublic.bridge.TcpBridgeService;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfig;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfigLoader;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@Plugin(
        id = "zstdnet-velocity",
        name = "ZstdNet Velocity",
        version = "1.4.0",
        authors = {"tohsaka"},
        description = "Velocity bridge plugin for ZstdNet."
)
public final class ZstdNetVelocityPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final TcpBridgeService tcpBridgeService;

    private volatile VcbgPublicConfig config = VcbgPublicConfig.defaults();

    @Inject
    public ZstdNetVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.tcpBridgeService = new TcpBridgeService(logger, this::resolveBridgeTarget);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws IOException {
        this.config = VcbgPublicConfigLoader.load(dataDirectory, logger);
        this.tcpBridgeService.start(config);
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
        tcpBridgeService.stop();
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

    private static String printable(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }
}
