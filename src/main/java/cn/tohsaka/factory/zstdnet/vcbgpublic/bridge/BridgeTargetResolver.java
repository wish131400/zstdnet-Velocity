package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;

@FunctionalInterface
public interface BridgeTargetResolver {
    Optional<BridgeTarget> resolve(String serverName);

    static Optional<BridgeTarget> fromRegisteredServer(RegisteredServer server) {
        if (server == null) {
            return Optional.empty();
        }
        var info = server.getServerInfo();
        return Optional.of(new BridgeTarget(
                info.getName(),
                info.getAddress().getHostString(),
                info.getAddress().getPort()
        ));
    }
}
