package cn.tohsaka.factory.zstdnet.vcbgpublic.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Provides Mojang premium profile data for Velocity-side forwarding.
 *
 * <p>This is intentionally owned by zstdnet-velocity. TrueUUID remains a Minecraft mod;
 * the proxy only forwards the corrected profile to backend mods through Velocity/PCF.</p>
 */
public interface PremiumProfileProvider {
    CompletableFuture<Optional<PremiumProfile>> findProfile(String username);

    record PremiumProfile(UUID uuid, String name, List<Property> properties) {
        public PremiumProfile {
            properties = List.copyOf(properties);
        }
    }

    record Property(String name, String value, String signature) {
    }
}
