package cn.tohsaka.factory.zstdnet.vcbgpublic.profile;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MojangPremiumProfileProvider implements PremiumProfileProvider {
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient;
    private final Logger logger;
    private final String sessionServerBaseUrl;
    private final Duration timeout;
    private final boolean debug;

    public MojangPremiumProfileProvider(Logger logger, String sessionServerBaseUrl, Duration timeout, boolean debug) {
        this.logger = logger;
        this.sessionServerBaseUrl = trimTrailingSlash(sessionServerBaseUrl == null || sessionServerBaseUrl.isBlank()
                ? "https://sessionserver.mojang.com"
                : sessionServerBaseUrl.trim());
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
        this.debug = debug;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public CompletableFuture<Optional<PremiumProfile>> findProfile(String username) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = "https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(username.trim(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> handleProfileResponse(username, response))
                .exceptionally(error -> {
                    logger.warn("[zstdnet-velocity] Premium profile lookup failed: name={}", username, error);
                    return Optional.empty();
                });
    }

    private CompletableFuture<Optional<PremiumProfile>> handleProfileResponse(String username, HttpResponse<String> response) {
        if (debug) {
            logger.info("[zstdnet-velocity] Mojang profile lookup: name={} status={} body={}", username, response.statusCode(), response.body());
        }
        if (response.statusCode() != 200) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        NameProfile profile = GSON.fromJson(response.body(), NameProfile.class);
        if (profile == null || profile.id == null || profile.name == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID uuid = parseUndashedUuid(profile.id);
        return fetchSignedProperties(uuid, profile.name);
    }

    private CompletableFuture<Optional<PremiumProfile>> fetchSignedProperties(UUID uuid, String name) {
        String undashed = uuid.toString().replace("-", "");
        String url = sessionServerBaseUrl + "/session/minecraft/profile/" + undashed + "?unsigned=false";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (debug) {
                        logger.info("[zstdnet-velocity] Mojang signed profile: name={} status={} body={}", name, response.statusCode(), response.body());
                    }
                    if (response.statusCode() != 200) {
                        return Optional.<PremiumProfile>empty();
                    }
                    SignedProfile signedProfile = GSON.fromJson(response.body(), SignedProfile.class);
                    if (signedProfile == null || signedProfile.id == null || signedProfile.name == null) {
                        return Optional.<PremiumProfile>empty();
                    }
                    UUID signedUuid = parseUndashedUuid(signedProfile.id);
                    List<Property> properties = signedProfile.properties == null ? List.of() : signedProfile.properties.stream()
                            .map(property -> new Property(property.name, property.value, property.signature))
                            .toList();
                    return Optional.of(new PremiumProfile(signedUuid, signedProfile.name, properties));
                });
    }

    private static UUID parseUndashedUuid(String raw) {
        return UUID.fromString(raw.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5"));
    }

    private static String trimTrailingSlash(String value) {
        String text = value;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static final class NameProfile {
        String id;
        String name;
    }

    private static final class SignedProfile {
        String id;
        String name;
        List<SignedProperty> properties;
    }

    private static final class SignedProperty {
        String name;
        String value;
        @SerializedName("signature")
        String signature;
    }
}
