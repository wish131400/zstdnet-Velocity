package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import cn.tohsaka.factory.zstdnet.core.io.CountingInputStream;
import cn.tohsaka.factory.zstdnet.core.io.CountingOutputStream;
import cn.tohsaka.factory.zstdnet.core.io.StreamTransfer;
import cn.tohsaka.factory.zstdnet.core.limit.TokenBucketLimiter;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.stats.TrafficStats;
import cn.tohsaka.factory.zstdnet.vcbgpublic.config.VcbgPublicConfig;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.slf4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class TcpBridgeService {
    private static final int CLIENT_PEEK_BUFFER = 4096;
    private static final int MAX_HANDSHAKE_PACKET_SIZE = 2048;
    private static final String ZSTD_ADDRESS_HINT = "当前服务器启用了 ZSTD 连接，请联系服务器管理员获取正确的连接方式。";
    private static final int BRIDGE_COMPRESSION_THRESHOLD = 1048576;
    private static final int LOGIN_SET_COMPRESSION_PACKET_ID = 0x03;
    private static final byte[] PROXY_V2_SIGNATURE = new byte[]{
            0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a
    };

    private final Logger logger;
    private final BridgeTargetResolver targetResolver;
    private final TrafficStats stats = new TrafficStats();

    private volatile boolean running;
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;
    private volatile ExecutorService workers;
    private volatile ScheduledExecutorService statsTicker;
    private volatile FloodGuard guard;
    private volatile TokenBucketLimiter globalLimiter;
    private volatile List<UdpForwarder> udpForwarders = List.of();

    public TcpBridgeService(Logger logger, BridgeTargetResolver targetResolver) {
        this.logger = logger;
        this.targetResolver = targetResolver;
    }

    public synchronized void start(VcbgPublicConfig config) throws IOException {
        BridgeRuntimeConfig runtimeConfig = BridgeRuntimeConfig.from(config);
        if (running) {
            return;
        }
        if (!config.bridgeEnabled()) {
            logger.info("zstdnet-velocity tcp bridge disabled by config");
            return;
        }
        if (runtimeConfig.listenPort() <= 0) {
            logger.warn("zstdnet-velocity tcp bridge not started: bridge_listen_port must be > 0 when enabled");
            return;
        }
        Optional<BridgeTarget> initialTarget = targetResolver.resolve("");
        if (initialTarget.isEmpty()) {
            logger.warn("zstdnet-velocity tcp bridge not started: no route/default target server could be resolved on Velocity");
            return;
        }

        ServerSocket bound = new ServerSocket();
        bound.bind(new InetSocketAddress(runtimeConfig.listenHost(), runtimeConfig.listenPort()));
        this.listener = bound;
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "zstdnet-velocity-bridge-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.statsTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "zstdnet-velocity-bridge-stats");
            thread.setDaemon(true);
            return thread;
        });
        this.guard = new FloodGuard(runtimeConfig);
        this.globalLimiter = TokenBucketLimiter.create(runtimeConfig.maxRateGlobalBps(), runtimeConfig.burstBytes());
        this.running = true;
        startUdpForwarders(config, runtimeConfig, initialTarget.get());

        Thread thread = new Thread(() -> acceptLoop(runtimeConfig), "zstdnet-velocity-bridge-accept");
        thread.setDaemon(true);
        this.acceptThread = thread;
        thread.start();
        startStatsPrinter(runtimeConfig);

        logger.info(
                "zstdnet-velocity tcp bridge listening on {}:{} -> velocity({}:{}); default UDP target={}({}:{})",
                runtimeConfig.listenHost(),
                runtimeConfig.listenPort(),
                runtimeConfig.upstreamVelocityHost(),
                runtimeConfig.upstreamVelocityPort(),
                initialTarget.get().serverName(),
                initialTarget.get().host(),
                initialTarget.get().port()
        );
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(listener);
        listener = null;

        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        ExecutorService executor = workers;
        workers = null;
        if (executor != null) {
            executor.shutdownNow();
        }

        ScheduledExecutorService ticker = statsTicker;
        statsTicker = null;
        if (ticker != null) {
            ticker.shutdownNow();
        }

        guard = null;
        globalLimiter = null;
        stopUdpForwarders();
    }

    public boolean isRunning() {
        return running;
    }

    private void startUdpForwarders(VcbgPublicConfig config, BridgeRuntimeConfig runtimeConfig, BridgeTarget target) {
        List<UdpRoute> routes = buildUdpRoutes(config, runtimeConfig, target);
        List<UdpForwarder> started = new ArrayList<>();
        for (UdpRoute route : routes) {
            try {
                UdpForwarder forwarder = new UdpForwarder(route, logger);
                forwarder.start();
                started.add(forwarder);
                logger.info("zstdnet-velocity UDP route armed [{}]: {} -> {}", route.label(), route.listen(), route.target());
            } catch (Exception e) {
                logger.warn("zstdnet-velocity UDP route skipped [{}] {} -> {}: {}", route.label(), route.listen(), route.target(), e.toString());
            }
        }
        this.udpForwarders = started;
    }

    private List<UdpRoute> buildUdpRoutes(VcbgPublicConfig config, BridgeRuntimeConfig runtimeConfig, BridgeTarget target) {
        List<UdpRoute> routes = new ArrayList<>();
        HostPort gameListen = new HostPort(runtimeConfig.listenHost(), runtimeConfig.listenPort());
        HostPort gameTarget = new HostPort(target.host(), target.port());
        routes.add(new UdpRoute("game", gameListen, gameTarget));

        VoiceChatPassthroughDecision voiceChat = VoiceChatRoutePlanner.resolveVoiceChatPassthrough(
                logger,
                gameListen,
                gameTarget,
                config.voiceChatPassthrough(),
                config.voiceChatListen(),
                config.voiceChatTarget(),
                null,
                "Velocity plugin config"
        );
        if (voiceChat.reuseGameRoute()) {
            logger.info("zstdnet-velocity voice chat UDP passthrough reuses the built-in game UDP route.");
            return routes;
        }
        if (voiceChat.route() != null) {
            routes.add(voiceChat.route());
            return routes;
        }

        if (config.voiceChatPassthrough()) {
            logger.warn("zstdnet-velocity voice chat UDP passthrough not armed: {}", voiceChat.reason());
        } else {
            logger.info("zstdnet-velocity voice chat UDP passthrough disabled.");
        }
        return routes;
    }

    private void stopUdpForwarders() {
        List<UdpForwarder> forwarders = this.udpForwarders;
        this.udpForwarders = List.of();
        for (UdpForwarder forwarder : forwarders) {
            try {
                forwarder.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private void acceptLoop(BridgeRuntimeConfig config) {
        while (running) {
            try {
                Socket client = listener.accept();
                ExecutorService executor = workers;
                if (executor == null) {
                    closeQuietly(client);
                    continue;
                }
                executor.execute(() -> handleClient(client, config));
            } catch (IOException e) {
                if (running) {
                    logger.warn("zstdnet-velocity tcp bridge accept error: {}", e.toString());
                }
            }
        }
    }

    private void handleClient(Socket client, BridgeRuntimeConfig config) {
        String clientRemote = String.valueOf(client.getRemoteSocketAddress());
        String sourceIp = sourceIp(client.getRemoteSocketAddress());
        Optional<BridgeTarget> resolved = targetResolver.resolve("");
        if (resolved.isEmpty()) {
            logger.warn(
                    "zstdnet-velocity tcp bridge refused client={} because no route/default target server could be resolved on Velocity",
                    clientRemote
            );
            closeQuietly(client);
            return;
        }

        BridgeTarget target = resolved.get();
        try (Socket clientSocket = client; Socket upstream = new Socket()) {
            stats.addConn(1);
            PushbackInputStream pushIn = new PushbackInputStream(clientSocket.getInputStream(), CLIENT_PEEK_BUFFER);
            ProxyInfo proxyInfo = parseProxyProtocolV2(pushIn);
            if (proxyInfo.valid && proxyInfo.sourceIp != null && !proxyInfo.sourceIp.isBlank()) {
                sourceIp = proxyInfo.sourceIp;
            }
            FloodGuard currentGuard = guard;
            if (currentGuard != null && !currentGuard.begin(sourceIp)) {
                logger.warn("zstdnet-velocity rejected connection by flood guard: source={} remote={}", sourceIp, clientRemote);
                return;
            }
            DetectedClientMode clientMode = detectClientMode(pushIn);
            if (clientMode.mode == ClientMode.RAW_LOGIN) {
                logger.warn("zstdnet-velocity rejected raw login attempt from {} on zstd-only entry", sourceIp);
                sendLoginDisconnect(clientSocket, ZSTD_ADDRESS_HINT);
                return;
            }

            upstream.connect(resolveUpstreamAddress(target, config), 5000);
            clientSocket.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            applyReadTimeout(upstream, config.idleTimeout());
            maybeSendProxyProtocolV2(upstream, clientSocket, proxyInfo, config);
            TokenBucketLimiter perConnLimiter = TokenBucketLimiter.create(config.maxRatePerConnBps(), config.burstBytes());
            TokenBucketLimiter currentGlobalLimiter = globalLimiter;

            logger.info(
                    "zstdnet-velocity tcp bridge accepted source={} remote={} -> {}({}:{}) upstream=velocity({}:{})",
                    sourceIp,
                    clientRemote,
                    target.serverName(),
                    target.host(),
                    target.port(),
                    config.upstreamVelocityHost(),
                    config.upstreamVelocityPort()
            );

            if (clientMode.mode == ClientMode.RAW_STATUS) {
                forwardRawPassthrough(clientSocket, pushIn, upstream, clientMode.initialWireData, stats);
                return;
            }

            VanillaCompressionBridge compressionBridge = new VanillaCompressionBridge();

            Future<Exception> c2s = workers.submit(() -> {
                try {
                    forwardDecompress(upstream, pushIn, stats, compressionBridge);
                    return null;
                } catch (Exception ex) {
                    return ex;
                } finally {
                    closeWrite(upstream);
                }
            });

            Future<Exception> s2c = workers.submit(() -> {
                try {
                    forwardCompress(clientSocket.getOutputStream(), upstream, config.level(), config.flushInterval(), stats, perConnLimiter, currentGlobalLimiter, compressionBridge);
                    return null;
                } catch (Exception ex) {
                    return ex;
                } finally {
                    closeWrite(clientSocket);
                }
            });

            Exception err1 = null;
            Exception err2 = null;
            while (!c2s.isDone() && !s2c.isDone()) {
                Thread.sleep(20L);
            }
            if (c2s.isDone()) {
                err1 = c2s.get();
            }
            if (s2c.isDone()) {
                err2 = s2c.get();
            }

            closeSocket(clientSocket);
            closeSocket(upstream);

            if (!c2s.isDone()) {
                err1 = c2s.get();
            }
            if (!s2c.isDone()) {
                err2 = s2c.get();
            }
            if (isRealPipeErr(err1)) {
                logger.warn("zstdnet-velocity pipe error source={} dir=client->backend remote={}: {}", sourceIp, clientRemote, err1.toString());
            }
            if (isRealPipeErr(err2)) {
                logger.warn("zstdnet-velocity pipe error source={} dir=backend->client remote={}: {}", sourceIp, clientRemote, err2.toString());
            }
        } catch (IOException e) {
            logger.warn(
                    "zstdnet-velocity tcp bridge connection error: source={} client={} target={}({}:{}) error={}",
                    sourceIp,
                    clientRemote,
                    target.serverName(),
                    target.host(),
                    target.port(),
                    e.toString()
            );
        } catch (Exception e) {
            if (isRealPipeErr(e)) {
                logger.warn("zstdnet-velocity connection error source={} remote={} target={}({}:{}) error={}", sourceIp, clientRemote, target.serverName(), target.host(), target.port(), e.toString());
            }
        } finally {
            FloodGuard currentGuard = guard;
            if (currentGuard != null) {
                currentGuard.end(sourceIp);
            }
            stats.addConn(-1);
        }
    }

    private static InetSocketAddress resolveUpstreamAddress(BridgeTarget target, BridgeRuntimeConfig config) {
        return new InetSocketAddress(config.upstreamVelocityHost(), config.upstreamVelocityPort());
    }

    private void maybeSendProxyProtocolV2(Socket upstream, Socket clientSocket, ProxyInfo proxyInfo, BridgeRuntimeConfig config) throws IOException {
        if (!config.upstreamProxyProtocol()) {
            return;
        }
        InetAddress srcInet;
        InetAddress dstInet;
        int sp;
        int dp;
        if (proxyInfo != null && proxyInfo.valid
                && proxyInfo.sourceIp != null && !proxyInfo.sourceIp.isBlank()
                && proxyInfo.targetIp != null && !proxyInfo.targetIp.isBlank()) {
            try {
                srcInet = InetAddress.getByName(proxyInfo.sourceIp);
                dstInet = InetAddress.getByName(proxyInfo.targetIp);
            } catch (UnknownHostException e) {
                logger.warn("zstdnet-velocity proxy protocol v2 skipped: cannot parse inbound proxy addresses src={} dst={}", proxyInfo.sourceIp, proxyInfo.targetIp);
                return;
            }
            sp = proxyInfo.sourcePort;
            dp = proxyInfo.targetPort;
        } else {
            SocketAddress src = clientSocket.getRemoteSocketAddress();
            SocketAddress dst = upstream.getRemoteSocketAddress();
            if (!(src instanceof InetSocketAddress) || !(dst instanceof InetSocketAddress)) {
                logger.warn("zstdnet-velocity proxy protocol v2 skipped: client/upstream not InetSocketAddress (src={} dst={})", src, dst);
                return;
            }
            InetSocketAddress srcAddr = (InetSocketAddress) src;
            InetSocketAddress dstAddr = (InetSocketAddress) dst;
            srcInet = srcAddr.getAddress();
            dstInet = dstAddr.getAddress();
            if (srcInet == null || dstInet == null) {
                logger.warn("zstdnet-velocity proxy protocol v2 skipped: unresolved addresses src={} dst={}", srcAddr, dstAddr);
                return;
            }
            sp = srcAddr.getPort();
            dp = dstAddr.getPort();
        }
        boolean v6 = srcInet instanceof Inet6Address || dstInet instanceof Inet6Address;
        int addrLen = v6 ? 16 : 4;
        int payloadLen = addrLen * 2 + 4;
        int total = 12 + 4 + payloadLen;
        byte[] buf = new byte[total];
        System.arraycopy(PROXY_V2_SIGNATURE, 0, buf, 0, PROXY_V2_SIGNATURE.length);
        buf[12] = 0x21;
        buf[13] = (byte) (v6 ? 0x21 : 0x11);
        buf[14] = (byte) ((payloadLen >> 8) & 0xFF);
        buf[15] = (byte) (payloadLen & 0xFF);
        int off = 16;
        byte[] sb = normalizeAddressBytes(srcInet, addrLen);
        byte[] db = normalizeAddressBytes(dstInet, addrLen);
        System.arraycopy(sb, 0, buf, off, addrLen);
        off += addrLen;
        System.arraycopy(db, 0, buf, off, addrLen);
        off += addrLen;
        buf[off++] = (byte) ((sp >> 8) & 0xFF);
        buf[off++] = (byte) (sp & 0xFF);
        buf[off++] = (byte) ((dp >> 8) & 0xFF);
        buf[off] = (byte) (dp & 0xFF);
        OutputStream out = upstream.getOutputStream();
        out.write(buf);
        out.flush();
    }

    private static byte[] normalizeAddressBytes(InetAddress addr, int target) {
        byte[] raw = addr.getAddress();
        if (raw.length == target) {
            return raw;
        }
        byte[] out = new byte[target];
        if (raw.length == 4 && target == 16) {
            // IPv4-mapped IPv6: ::ffff:a.b.c.d
            out[10] = (byte) 0xff;
            out[11] = (byte) 0xff;
            System.arraycopy(raw, 0, out, 12, 4);
            return out;
        }
        int copy = Math.min(raw.length, target);
        System.arraycopy(raw, raw.length - copy, out, target - copy, copy);
        return out;
    }

    private ProxyInfo parseProxyProtocolV2(PushbackInputStream in) throws IOException {
        byte[] first = new byte[PROXY_V2_SIGNATURE.length];
        int n = readSome(in, first);
        if (n < 0) {
            return ProxyInfo.invalid();
        }
        if (n < PROXY_V2_SIGNATURE.length) {
            in.unread(first, 0, n);
            return ProxyInfo.invalid();
        }
        if (!Arrays.equals(first, PROXY_V2_SIGNATURE)) {
            in.unread(first);
            return ProxyInfo.invalid();
        }

        byte[] fixed = PacketIo.readFully(in, 4);
        int verCmd = fixed[0] & 0xFF;
        int famProto = fixed[1] & 0xFF;
        int payloadLen = ((fixed[2] & 0xFF) << 8) | (fixed[3] & 0xFF);
        byte[] payload = PacketIo.readFully(in, payloadLen);

        int version = (verCmd & 0xF0) >> 4;
        int command = verCmd & 0x0F;
        int family = (famProto & 0xF0) >> 4;
        int protocol = famProto & 0x0F;

        if (version != 0x2 || command != 0x1 || protocol != 0x1) {
            return ProxyInfo.invalid();
        }

        if (family == 0x1 && payload.length >= 12) {
            String sourceIp = ipString(payload, 0, 4);
            String targetIp = ipString(payload, 4, 4);
            int sourcePort = u16(payload, 8);
            int targetPort = u16(payload, 10);
            return new ProxyInfo(true, sourceIp, sourcePort, targetIp, targetPort);
        }
        if (family == 0x2 && payload.length >= 36) {
            String sourceIp = ipString(payload, 0, 16);
            String targetIp = ipString(payload, 16, 16);
            int sourcePort = u16(payload, 32);
            int targetPort = u16(payload, 34);
            return new ProxyInfo(true, sourceIp, sourcePort, targetIp, targetPort);
        }
        return ProxyInfo.invalid();
    }

    private int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off == 0 ? -1 : off;
            }
            off += n;
            if (n == 0) {
                break;
            }
        }
        return off;
    }

    private DetectedClientMode detectClientMode(PushbackInputStream in) throws IOException {
        byte[] firstPacketWire = tryReadPacketWire(in, 1500);
        if (firstPacketWire == null || firstPacketWire.length == 0) {
            return DetectedClientMode.zstd();
        }

        byte[] firstPacket = PacketIo.extractPacketPayload(firstPacketWire);
        Integer nextState = extractHandshakeNextState(firstPacket);
        if (nextState != null && nextState == 1) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isStatusRequestPacket(secondPacket)) {
                    return new DetectedClientMode(ClientMode.RAW_STATUS, ByteArrayOps.concat(firstPacketWire, secondPacketWire));
                }
                in.unread(secondPacketWire);
            }
        } else if (nextState != null && nextState == 2) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isLoginStartPacket(secondPacket)) {
                    return new DetectedClientMode(ClientMode.RAW_LOGIN, ByteArrayOps.concat(firstPacketWire, secondPacketWire));
                }
                in.unread(secondPacketWire);
            }
        }

        in.unread(firstPacketWire);
        return DetectedClientMode.zstd();
    }

    private void forwardRawPassthrough(Socket clientSocket, InputStream clientIn, Socket upstream, byte[] initialWireData, TrafficStats stats) throws Exception {
        OutputStream upstreamOut = upstream.getOutputStream();
        upstreamOut.write(initialWireData);
        upstreamOut.flush();
        addRawPassthroughStats(initialWireData.length, stats);

        Future<?> upstreamWriter = workers.submit(() -> {
            try {
                streamRaw(clientIn, upstreamOut, stats);
            } catch (Exception ignored) {
            } finally {
                closeWrite(upstream);
            }
        });

        Future<?> downstreamWriter = workers.submit(() -> {
            try {
                streamRaw(upstream.getInputStream(), clientSocket.getOutputStream(), stats);
            } catch (Exception ignored) {
            } finally {
                closeWrite(clientSocket);
            }
        });

        upstreamWriter.get();
        downstreamWriter.get();
    }

    private void forwardDecompress(Socket dst, InputStream src, TrafficStats stats, VanillaCompressionBridge compressionBridge) throws IOException {
        try (ZstdInputStream zstdIn = new ZstdInputStream(new CountingInputStream(src, stats::addZstd))) {
            OutputStream dstOut = dst.getOutputStream();
            while (true) {
                byte[] payload = readNextPacketPayload(zstdIn);
                if (payload == null) {
                    return;
                }
                byte[] translated = compressionBridge.translateClientToBackend(payload);
                PacketIo.writePacket(dstOut, translated);
                stats.addRaw(packetWireLength(translated));
            }
        }
    }

    private void forwardCompress(OutputStream dst, Socket src, int level, Duration flushInterval, TrafficStats stats, TokenBucketLimiter perConnLimiter, TokenBucketLimiter globalLimiter, VanillaCompressionBridge compressionBridge) throws IOException {
        OutputStream limitedDst = new RateLimitedOutputStream(dst, perConnLimiter, globalLimiter);
        try (ZstdOutputStream zstdOut = new ZstdOutputStream(new CountingOutputStream(limitedDst, stats::addZstd), level)) {
            zstdOut.setCloseFrameOnFlush(false);
            InputStream srcIn = src.getInputStream();
            final long flushIntervalNs = Math.max(0L, flushInterval.toNanos());
            long lastFlushNs = System.nanoTime();
            int originalTimeout = src.getSoTimeout();
            int activeTimeout = originalTimeout;
            boolean hasPending = false;
            try {
                while (true) {
                    if (flushIntervalNs > 0L) {
                        int desiredTimeout = originalTimeout;
                        if (hasPending) {
                            long elapsedNs = System.nanoTime() - lastFlushNs;
                            long remainingNs = Math.max(1L, flushIntervalNs - elapsedNs);
                            long remainingMs = Math.max(1L, remainingNs / 1_000_000L);
                            long boundedMs = Math.min((long) Integer.MAX_VALUE, remainingMs);
                            desiredTimeout = (int) boundedMs;
                            if (originalTimeout > 0) {
                                desiredTimeout = Math.min(desiredTimeout, originalTimeout);
                            }
                        }
                        if (desiredTimeout != activeTimeout) {
                            src.setSoTimeout(desiredTimeout);
                            activeTimeout = desiredTimeout;
                        }
                    }

                    try {
                        byte[] payload = readNextPacketPayload(srcIn);
                        if (payload == null) {
                            break;
                        }
                        byte[] translated = compressionBridge.translateBackendToClient(payload);
                        stats.addRaw(packetWireLength(translated));
                        PacketIo.writePacket(zstdOut, translated);
                        hasPending = true;
                        if (flushIntervalNs == 0L || (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                            zstdOut.flush();
                            hasPending = false;
                            lastFlushNs = System.nanoTime();
                        }
                    } catch (SocketTimeoutException timeout) {
                        if (flushIntervalNs > 0L && hasPending && (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                            zstdOut.flush();
                            hasPending = false;
                            lastFlushNs = System.nanoTime();
                        }
                        continue;
                    }
                }
            } finally {
                src.setSoTimeout(originalTimeout);
            }

            zstdOut.flush();
        }
    }

    private void streamRaw(InputStream in, OutputStream out, TrafficStats stats) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                out.write(buf, 0, n);
                addRawPassthroughStats(n, stats);
            }
        }
    }

    private void addRawPassthroughStats(int bytes, TrafficStats stats) {
        if (stats == null || bytes <= 0) {
            return;
        }
        stats.addRaw(bytes);
        stats.addZstd(bytes);
    }

    private byte[] readNextPacketPayload(InputStream in) throws IOException {
        int length = VarIntCodec.read(in);
        if (length < 0) {
            return null;
        }
        if (length == 0) {
            return new byte[0];
        }
        return PacketIo.readFully(in, length);
    }

    private int packetWireLength(byte[] payload) {
        return VarIntCodec.encode(payload.length).length + payload.length;
    }

    private byte[] tryReadPacketWire(PushbackInputStream in, int maxWaitMillis) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(200L, maxWaitMillis);
        byte[] prefix = new byte[5];
        int prefixLength = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                int next = in.read();
                if (next < 0) {
                    if (prefixLength == 0) {
                        return new byte[0];
                    }
                    throw new EOFException("unexpected eof during packet read");
                }
                if (prefixLength >= prefix.length) {
                    throw new IOException("packet length varint too large");
                }
                prefix[prefixLength++] = (byte) next;
                VarIntRead packetLength = VarIntCodec.read(prefix, 0, prefixLength);
                if (packetLength == null) {
                    continue;
                }
                if (packetLength.value() <= 0 || packetLength.value() > MAX_HANDSHAKE_PACKET_SIZE) {
                    in.unread(prefix, 0, prefixLength);
                    return null;
                }
                byte[] payload = PacketIo.readFully(in, packetLength.value());
                byte[] packet = new byte[prefixLength + payload.length];
                System.arraycopy(prefix, 0, packet, 0, prefixLength);
                System.arraycopy(payload, 0, packet, prefixLength, payload.length);
                return packet;
            } catch (SocketTimeoutException ignored) {
            }
        }
        if (prefixLength > 0) {
            in.unread(prefix, 0, prefixLength);
        }
        return new byte[0];
    }

    private boolean isStatusRequestPacket(byte[] payload) {
        return payload != null && payload.length == 1 && payload[0] == 0;
    }

    private boolean isLoginStartPacket(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return false;
        }
        VarIntRead packetId = VarIntCodec.read(payload, 0, payload.length);
        if (packetId == null || packetId.value() != 0) {
            return false;
        }
        VarIntRead nameLength = VarIntCodec.read(payload, packetId.next(), payload.length);
        if (nameLength == null || nameLength.value() < 1 || nameLength.value() > 16) {
            return false;
        }
        int nameStart = nameLength.next();
        int nameEnd = nameStart + nameLength.value();
        if (nameEnd > payload.length) {
            return false;
        }
        for (int i = nameStart; i < nameEnd; i++) {
            int ch = payload[i] & 0xFF;
            boolean valid = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private Integer extractHandshakeNextState(byte[] handshakePayload) {
        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0, handshakePayload.length);
        if (packetId == null || packetId.value() != 0) {
            return null;
        }
        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next(), handshakePayload.length);
        if (protocol == null) {
            return null;
        }
        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next(), handshakePayload.length);
        if (hostLength == null || hostLength.value() < 0) {
            return null;
        }
        int afterHost = hostLength.next() + hostLength.value();
        int afterPort = afterHost + 2;
        if (afterPort > handshakePayload.length) {
            return null;
        }
        VarIntRead nextState = VarIntCodec.read(handshakePayload, afterPort, handshakePayload.length);
        if (nextState == null || (nextState.value() != 1 && nextState.value() != 2)) {
            return null;
        }
        return nextState.value();
    }

    private void sendLoginDisconnect(Socket clientSocket, String message) {
        if (clientSocket == null || message == null || message.isBlank()) {
            return;
        }
        try {
            OutputStream out = clientSocket.getOutputStream();
            byte[] packet = buildLoginDisconnectPacket(message);
            out.write(packet);
            out.flush();
        } catch (IOException e) {
            logger.debug("zstdnet-velocity failed to send raw-login disconnect packet: {}", e.toString());
        }
    }

    private byte[] buildLoginDisconnectPacket(String message) throws IOException {
        byte[] componentJson = buildTextComponentJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] packetId = VarIntCodec.encode(0);
        byte[] componentLength = VarIntCodec.encode(componentJson.length);
        byte[] payload = new byte[packetId.length + componentLength.length + componentJson.length];
        int offset = 0;
        System.arraycopy(packetId, 0, payload, offset, packetId.length);
        offset += packetId.length;
        System.arraycopy(componentLength, 0, payload, offset, componentLength.length);
        offset += componentLength.length;
        System.arraycopy(componentJson, 0, payload, offset, componentJson.length);
        byte[] packetLength = VarIntCodec.encode(payload.length);
        byte[] packet = new byte[packetLength.length + payload.length];
        System.arraycopy(packetLength, 0, packet, 0, packetLength.length);
        System.arraycopy(payload, 0, packet, packetLength.length, payload.length);
        return packet;
    }

    private String buildTextComponentJson(String text) {
        return "{\"text\":\"" + escapeJson(text) + "\"}";
    }

    private String escapeJson(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private final class VanillaCompressionBridge {
        private volatile int backendThreshold = -1;
        private volatile int clientThreshold = -1;

        byte[] translateBackendToClient(byte[] payload) throws IOException {
            if (backendThreshold < 0) {
                Integer threshold = tryParseSetCompressionThreshold(payload);
                if (threshold != null) {
                    backendThreshold = threshold;
                    clientThreshold = Math.max(threshold, BRIDGE_COMPRESSION_THRESHOLD);
                    logger.info("zstdnet-velocity rewrote backend compression threshold {} -> {} for bridge-side Zstd efficiency.", backendThreshold, clientThreshold);
                    return buildSetCompressionPayload(clientThreshold);
                }
                return payload;
            }

            VarIntRead dataLength = VarIntCodec.read(payload, 0, payload.length);
            if (dataLength == null) {
                throw new IOException("invalid compressed packet from backend");
            }
            int declaredUncompressed = dataLength.value();
            byte[] body = Arrays.copyOfRange(payload, dataLength.next(), payload.length);
            if (declaredUncompressed == 0) {
                return payload;
            }
            byte[] rawPacket = inflate(body, declaredUncompressed);
            return encodeForThreshold(rawPacket, clientThreshold);
        }

        byte[] translateClientToBackend(byte[] payload) throws IOException {
            if (backendThreshold < 0) {
                return payload;
            }

            VarIntRead dataLength = VarIntCodec.read(payload, 0, payload.length);
            if (dataLength == null) {
                throw new IOException("invalid compressed packet from client");
            }
            int declaredUncompressed = dataLength.value();
            byte[] body = Arrays.copyOfRange(payload, dataLength.next(), payload.length);
            byte[] rawPacket = declaredUncompressed == 0 ? body : inflate(body, declaredUncompressed);
            return encodeForThreshold(rawPacket, backendThreshold);
        }

        private Integer tryParseSetCompressionThreshold(byte[] payload) {
            VarIntRead packetId = VarIntCodec.read(payload, 0, payload.length);
            if (packetId == null || packetId.value() != LOGIN_SET_COMPRESSION_PACKET_ID) {
                return null;
            }
            VarIntRead threshold = VarIntCodec.read(payload, packetId.next(), payload.length);
            return threshold == null ? null : threshold.value();
        }

        private byte[] buildSetCompressionPayload(int threshold) {
            return ByteArrayOps.concat(VarIntCodec.encode(LOGIN_SET_COMPRESSION_PACKET_ID), VarIntCodec.encode(threshold));
        }

        private byte[] encodeForThreshold(byte[] rawPacket, int threshold) throws IOException {
            if (threshold < 0) {
                return rawPacket;
            }
            if (rawPacket.length < threshold) {
                return ByteArrayOps.concat(VarIntCodec.encode(0), rawPacket);
            }
            byte[] compressed = deflate(rawPacket);
            return ByteArrayOps.concat(VarIntCodec.encode(rawPacket.length), compressed);
        }

        private byte[] deflate(byte[] rawPacket) {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            deflater.setInput(rawPacket);
            deflater.finish();
            byte[] buffer = new byte[Math.max(256, rawPacket.length + 64)];
            int offset = 0;
            while (!deflater.finished()) {
                if (offset == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
                offset += deflater.deflate(buffer, offset, buffer.length - offset);
            }
            deflater.end();
            return Arrays.copyOf(buffer, offset);
        }

        private byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            byte[] buffer = new byte[Math.max(expectedLength, 256)];
            int offset = 0;
            try {
                while (!inflater.finished()) {
                    if (offset == buffer.length) {
                        buffer = Arrays.copyOf(buffer, buffer.length * 2);
                    }
                    int inflated = inflater.inflate(buffer, offset, buffer.length - offset);
                    if (inflated == 0) {
                        if (inflater.needsInput()) {
                            break;
                        }
                        if (inflater.needsDictionary()) {
                            throw new IOException("backend requested unsupported compression dictionary");
                        }
                    }
                    offset += inflated;
                }
            } catch (DataFormatException e) {
                throw new IOException("invalid backend compression payload", e);
            } finally {
                inflater.end();
            }
            if (expectedLength > 0 && offset != expectedLength) {
                throw new IOException("unexpected inflated packet size: expected=" + expectedLength + ", actual=" + offset);
            }
            return Arrays.copyOf(buffer, offset);
        }
    }

    private String ipString(byte[] data, int offset, int len) throws IOException {
        byte[] raw = Arrays.copyOfRange(data, offset, offset + len);
        return InetAddress.getByAddress(raw).getHostAddress();
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private void startStatsPrinter(BridgeRuntimeConfig config) {
        ScheduledExecutorService ticker = statsTicker;
        if (ticker == null) {
            return;
        }
        long periodMs = Math.max(250L, config.statsInterval().toMillis());
        AtomicLong prevRaw = new AtomicLong();
        AtomicLong prevZstd = new AtomicLong();
        ticker.scheduleAtFixedRate(() -> {
            FloodGuard currentGuard = guard;
            if (currentGuard != null) {
                currentGuard.sweepExpired();
            }

            long raw = stats.rawBytes.get();
            long zstd = stats.zstdBytes.get();
            int conns = stats.activeConn.get();

            long dr = raw - prevRaw.getAndSet(raw);
            long dz = zstd - prevZstd.getAndSet(zstd);
            long rawPerSec = (long) (dr * (1000.0 / periodMs));
            long zstdPerSec = (long) (dz * (1000.0 / periodMs));
            double ratio = raw <= 0 ? 0.0 : ((double) zstd * 100.0 / (double) raw);

            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logger.info("[{}] Raw: {} ({}) | Zstd: {} ({}) | Ratio: {}% | Conns: {}",
                    now,
                    formatSize(raw),
                    formatRate(rawPerSec),
                    formatSize(zstd),
                    formatRate(zstdPerSec),
                    String.format(Locale.ROOT, "%.2f", ratio),
                    conns);
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[idx]);
    }

    private String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) {
            return bytesPerSec + "B/s";
        }
        String[] units = {"KB/s", "MB/s", "GB/s", "TB/s"};
        double v = bytesPerSec / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.1f%s", v, units[idx]);
    }

    private String sourceIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress ip = inet.getAddress();
            return ip != null ? ip.getHostAddress() : inet.getHostString();
        }
        return String.valueOf(address);
    }

    private void applyReadTimeout(Socket socket, Duration timeout) {
        if (socket == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long timeoutMs = Math.max(1L, timeout.toMillis());
        int bounded = (int) Math.min((long) Integer.MAX_VALUE, timeoutMs);
        try {
            socket.setSoTimeout(bounded);
        } catch (Exception ignored) {
        }
    }

    private boolean isRealPipeErr(Exception err) {
        if (err == null || err instanceof EOFException) {
            return false;
        }
        String msg = err.toString().toLowerCase(Locale.ROOT);
        return !(msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("socket closed"));
    }

    private static void waitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (Exception ignored) {
        }
    }

    private static void closeWrite(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private record ProxyInfo(boolean valid, String sourceIp, int sourcePort, String targetIp, int targetPort) {
        private static ProxyInfo invalid() {
            return new ProxyInfo(false, null, 0, null, 0);
        }
    }

    private enum ClientMode {
        ZSTD,
        RAW_STATUS,
        RAW_LOGIN
    }

    private record DetectedClientMode(ClientMode mode, byte[] initialWireData) {
        private static DetectedClientMode zstd() {
            return new DetectedClientMode(ClientMode.ZSTD, null);
        }
    }

    private static final class FloodGuard {
        private final Map<String, GuardEntry> state = new ConcurrentHashMap<>();
        private final BridgeRuntimeConfig cfg;

        private FloodGuard(BridgeRuntimeConfig cfg) {
            this.cfg = cfg;
        }

        private synchronized boolean begin(String ip) {
            long now = System.currentTimeMillis();
            GuardEntry entry = state.computeIfAbsent(ip, key -> new GuardEntry());
            pruneRequests(entry, now);

            if (entry.bannedUntilMs > now) {
                return false;
            }

            if (cfg.maxReqPerWindow() > 0 && !cfg.window().isZero() && !cfg.window().isNegative()) {
                entry.requestsMs.addLast(now);
                if (entry.requestsMs.size() > cfg.maxReqPerWindow()) {
                    entry.bannedUntilMs = now + cfg.banDuration().toMillis();
                    return false;
                }
            }

            if (cfg.maxConnPerIp() > 0 && entry.activeConn >= cfg.maxConnPerIp()) {
                return false;
            }

            entry.activeConn++;
            return true;
        }

        private synchronized void end(String ip) {
            GuardEntry entry = state.get(ip);
            if (entry == null) {
                return;
            }
            if (entry.activeConn > 0) {
                entry.activeConn--;
            }
            long now = System.currentTimeMillis();
            pruneRequests(entry, now);
            if (isRemovable(entry, now)) {
                state.remove(ip);
            }
        }

        private synchronized void sweepExpired() {
            long now = System.currentTimeMillis();
            state.entrySet().removeIf(entry -> {
                GuardEntry guardEntry = entry.getValue();
                pruneRequests(guardEntry, now);
                return isRemovable(guardEntry, now);
            });
        }

        private void pruneRequests(GuardEntry entry, long now) {
            if (cfg.window().isZero() || cfg.window().isNegative()) {
                entry.requestsMs.clear();
                return;
            }
            long cutoff = now - cfg.window().toMillis();
            while (!entry.requestsMs.isEmpty() && entry.requestsMs.peekFirst() < cutoff) {
                entry.requestsMs.removeFirst();
            }
        }

        private boolean isRemovable(GuardEntry entry, long now) {
            return entry.activeConn == 0 && entry.requestsMs.isEmpty() && entry.bannedUntilMs <= now;
        }

        private static final class GuardEntry {
            private int activeConn;
            private long bannedUntilMs;
            private final Deque<Long> requestsMs = new ArrayDeque<>();
        }
    }

    private static final class RateLimitedOutputStream extends OutputStream {
        private static final int CHUNK_SIZE = 16 * 1024;

        private final OutputStream delegate;
        private final TokenBucketLimiter perConnLimiter;
        private final TokenBucketLimiter globalLimiter;

        private RateLimitedOutputStream(OutputStream delegate, TokenBucketLimiter perConnLimiter, TokenBucketLimiter globalLimiter) {
            this.delegate = delegate;
            this.perConnLimiter = perConnLimiter;
            this.globalLimiter = globalLimiter;
        }

        @Override
        public void write(int b) throws IOException {
            throttle(1);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int written = 0;
            while (written < len) {
                int chunk = Math.min(CHUNK_SIZE, len - written);
                throttle(chunk);
                delegate.write(b, off + written, chunk);
                written += chunk;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void throttle(int n) {
            if (perConnLimiter != null) {
                perConnLimiter.waitBytes(n);
            }
            if (globalLimiter != null) {
                globalLimiter.waitBytes(n);
            }
        }
    }
}
