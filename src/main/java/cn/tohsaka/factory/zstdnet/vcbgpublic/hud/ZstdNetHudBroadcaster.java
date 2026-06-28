package cn.tohsaka.factory.zstdnet.vcbgpublic.hud;

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.stats.TrafficStats;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfig;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ZstdNetHudBroadcaster {
    private static final MinecraftChannelIdentifier FORGE_1201_CHANNEL = MinecraftChannelIdentifier.create("zstdnet", "lan_compression");
    private static final MinecraftChannelIdentifier SERVER_HUD_CHANNEL = MinecraftChannelIdentifier.create("zstdnet", "server_hud");
    private static final int FORGE_1201_SERVER_HUD_MESSAGE_ID = 3;

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Logger logger;
    private final TrafficStats stats;
    private final AtomicLong previousRawUp = new AtomicLong();
    private final AtomicLong previousRawDown = new AtomicLong();
    private final AtomicLong previousZstdUp = new AtomicLong();
    private final AtomicLong previousZstdDown = new AtomicLong();
    private final Set<UUID> channelCapablePlayers = ConcurrentHashMap.newKeySet();

    private volatile VcbgPublicConfig config;
    private volatile ScheduledTask task;
    private volatile boolean channelsRegistered;

    public ZstdNetHudBroadcaster(ProxyServer proxyServer, Object plugin, Logger logger, TrafficStats stats) {
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.logger = logger;
        this.stats = stats;
    }

    public synchronized void start(VcbgPublicConfig config) {
        this.config = config;
        proxyServer.getChannelRegistrar().register(FORGE_1201_CHANNEL, SERVER_HUD_CHANNEL);
        channelsRegistered = true;
        task = proxyServer.getScheduler()
                .buildTask(plugin, this::broadcast)
                .delay(Duration.ofSeconds(1))
                .repeat(1L, TimeUnit.SECONDS)
                .schedule();
        logger.info("zstdnet-velocity client HUD sync enabled for ZstdNet mod clients");
    }

    public synchronized void stop() {
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
        if (channelsRegistered) {
            channelsRegistered = false;
            proxyServer.getChannelRegistrar().unregister(FORGE_1201_CHANNEL, SERVER_HUD_CHANNEL);
        }
    }

    private void broadcast() {
        if (stats == null || config == null) {
            return;
        }
        HudSnapshot snapshot = buildSnapshot();
        byte[] modernPayload = encodeHudSnapshot(snapshot, false);
        byte[] forge1201Payload = encodeHudSnapshot(snapshot, true);
        for (Player player : proxyServer.getAllPlayers()) {
            if (!hasZstdNetMod(player)) {
                continue;
            }
            player.sendPluginMessage(SERVER_HUD_CHANNEL, modernPayload);
            player.sendPluginMessage(FORGE_1201_CHANNEL, forge1201Payload);
        }
    }

    private HudSnapshot buildSnapshot() {
        long rawUp = stats.rawUpBytes();
        long rawDown = stats.rawDownBytes();
        long zstdUp = stats.zstdUpBytes();
        long zstdDown = stats.zstdDownBytes();
        long raw = stats.rawBytes();
        long zstd = stats.zstdBytes();
        long rawUpRate = rawUp - previousRawUp.getAndSet(rawUp);
        long rawDownRate = rawDown - previousRawDown.getAndSet(rawDown);
        long zstdUpRate = zstdUp - previousZstdUp.getAndSet(zstdUp);
        long zstdDownRate = zstdDown - previousZstdDown.getAndSet(zstdDown);
        return new HudSnapshot(
                "DEDICATED",
                config.bridgeListenHost(),
                config.bridgeListenPort(),
                raw,
                zstd,
                rawUp,
                rawDown,
                zstdUp,
                zstdDown,
                rawUpRate,
                rawDownRate,
                zstdUpRate,
                zstdDownRate,
                rawUpRate + rawDownRate,
                zstdUpRate + zstdDownRate,
                raw <= 0 ? 0.0D : (double) zstd * 100.0D / (double) raw,
                stats.activeConnections()
        );
    }

    private boolean hasZstdNetMod(Player player) {
        if (channelCapablePlayers.contains(player.getUniqueId())) {
            return true;
        }
        return player.getModInfo()
                .map(info -> info.getMods().stream().anyMatch(mod -> "zstdnet".equalsIgnoreCase(mod.getId())))
                .orElse(false);
    }

    public void markRegisteredChannels(Player player, Collection<ChannelIdentifier> channels) {
        if (player == null || channels == null || channels.isEmpty()) {
            return;
        }
        for (ChannelIdentifier channel : channels) {
            String id = channel.getId();
            if (id != null && id.startsWith("zstdnet:")) {
                channelCapablePlayers.add(player.getUniqueId());
                return;
            }
        }
    }

    public void forget(Player player) {
        if (player != null) {
            channelCapablePlayers.remove(player.getUniqueId());
        }
    }

    private byte[] encodeHudSnapshot(HudSnapshot snapshot, boolean forge1201SimpleChannel) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(192);
        if (forge1201SimpleChannel) {
            writeVarInt(out, FORGE_1201_SERVER_HUD_MESSAGE_ID);
        }
        writeUtf(out, snapshot.mode());
        writeUtf(out, snapshot.listenHost());
        writeVarInt(out, snapshot.listenPort());
        writeLong(out, snapshot.rawBytes());
        writeLong(out, snapshot.zstdBytes());
        writeLong(out, snapshot.rawUpBytes());
        writeLong(out, snapshot.rawDownBytes());
        writeLong(out, snapshot.zstdUpBytes());
        writeLong(out, snapshot.zstdDownBytes());
        writeLong(out, snapshot.rawUpRate());
        writeLong(out, snapshot.rawDownRate());
        writeLong(out, snapshot.zstdUpRate());
        writeLong(out, snapshot.zstdDownRate());
        writeLong(out, snapshot.rawRate());
        writeLong(out, snapshot.zstdRate());
        writeDouble(out, snapshot.ratioPercent());
        writeVarInt(out, snapshot.connections());
        return out.toByteArray();
    }

    private void writeUtf(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private void writeVarInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(VarIntCodec.encode(value));
    }

    private void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((byte) ((value >>> shift) & 0xFF));
        }
    }

    private void writeDouble(ByteArrayOutputStream out, double value) {
        writeLong(out, Double.doubleToLongBits(value));
    }

    private record HudSnapshot(
            String mode,
            String listenHost,
            int listenPort,
            long rawBytes,
            long zstdBytes,
            long rawUpBytes,
            long rawDownBytes,
            long zstdUpBytes,
            long zstdDownBytes,
            long rawUpRate,
            long rawDownRate,
            long zstdUpRate,
            long zstdDownRate,
            long rawRate,
            long zstdRate,
            double ratioPercent,
            int connections
    ) {
        private HudSnapshot {
            listenHost = listenHost == null || listenHost.isBlank() ? "0.0.0.0" : listenHost.trim().toLowerCase(Locale.ROOT);
        }
    }
}
