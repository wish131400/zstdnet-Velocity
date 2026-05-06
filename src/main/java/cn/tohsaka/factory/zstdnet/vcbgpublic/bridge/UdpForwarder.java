package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class UdpForwarder {
    private static final int UDP_BUF_SIZE = 65535;
    private static final long SESSION_TIMEOUT_MS = 60_000L;

    private final UdpRoute route;
    private final Logger logger;
    private volatile boolean running;
    private DatagramSocket serverSocket;
    private Thread forwardThread;
    private final Map<SocketAddress, UdpSession> sessions = new ConcurrentHashMap<>();

    UdpForwarder(UdpRoute route, Logger logger) {
        this.route = route;
        this.logger = logger;
    }

    void start() throws IOException {
        serverSocket = new DatagramSocket(null);
        serverSocket.setReuseAddress(true);
        serverSocket.bind(route.listen().toAddress());
        serverSocket.setSoTimeout(1000);
        running = true;

        forwardThread = new Thread(this::forwardLoop, "zstdnet-velocity-udp-fwd-" + route.label());
        forwardThread.setDaemon(true);
        forwardThread.start();
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
        for (UdpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (forwardThread != null) {
            try {
                forwardThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void forwardLoop() {
        byte[] buf = new byte[UDP_BUF_SIZE];
        InetSocketAddress targetAddr = route.target().toAddress();
        long lastSweep = System.currentTimeMillis();

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    serverSocket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    sweepIfNeeded(lastSweep);
                    lastSweep = System.currentTimeMillis();
                    continue;
                }

                SocketAddress clientAddr = packet.getSocketAddress();
                UdpSession session = sessions.computeIfAbsent(clientAddr, key -> createSession(key));
                if (session == null) {
                    continue;
                }
                session.lastActivity = System.currentTimeMillis();

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                DatagramPacket forward = new DatagramPacket(data, data.length, targetAddr);
                session.socket.send(forward);

                long now = System.currentTimeMillis();
                if (now - lastSweep > 10_000L) {
                    sweepIfNeeded(lastSweep);
                    lastSweep = now;
                }
            } catch (IOException e) {
                if (running) {
                    logger.debug("zstdnet-velocity UDP forward error [{}]: {}", route.label(), e.toString());
                }
            }
        }
    }

    private UdpSession createSession(SocketAddress clientAddr) {
        try {
            DatagramSocket clientSocket = new DatagramSocket();
            clientSocket.setSoTimeout(1000);
            UdpSession session = new UdpSession(clientSocket, clientAddr);

            Thread returnThread = new Thread(() -> returnLoop(session), "zstdnet-velocity-udp-ret-" + clientAddr);
            returnThread.setDaemon(true);
            returnThread.start();
            session.returnThread = returnThread;
            return session;
        } catch (IOException e) {
            logger.debug("zstdnet-velocity UDP session create failed [{}] for {}: {}", route.label(), clientAddr, e.toString());
            return null;
        }
    }

    private void returnLoop(UdpSession session) {
        byte[] buf = new byte[UDP_BUF_SIZE];
        while (running && !session.socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    session.socket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT_MS) {
                        break;
                    }
                    continue;
                }
                session.lastActivity = System.currentTimeMillis();

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                DatagramPacket returnPacket = new DatagramPacket(data, data.length, (InetSocketAddress) session.clientAddr);
                serverSocket.send(returnPacket);
            } catch (IOException e) {
                if (running && !session.socket.isClosed()) {
                    logger.debug("zstdnet-velocity UDP return error [{}] for {}: {}", route.label(), session.clientAddr, e.toString());
                }
                break;
            }
        }
        sessions.remove(session.clientAddr);
        session.close();
    }

    private void sweepIfNeeded(long lastSweep) {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 10_000L) {
            return;
        }
        sessions.entrySet().removeIf(entry -> {
            UdpSession session = entry.getValue();
            if (now - session.lastActivity > SESSION_TIMEOUT_MS) {
                session.close();
                return true;
            }
            return false;
        });
    }

    private static final class UdpSession {
        final DatagramSocket socket;
        final SocketAddress clientAddr;
        volatile long lastActivity;
        volatile Thread returnThread;

        UdpSession(DatagramSocket socket, SocketAddress clientAddr) {
            this.socket = socket;
            this.clientAddr = clientAddr;
            this.lastActivity = System.currentTimeMillis();
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
